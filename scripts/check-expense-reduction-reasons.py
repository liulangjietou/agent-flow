#!/usr/bin/env python3
"""逐行核减原因固定包验收：旧记录原值保留、一次原子写入、原键恢复与强退恢复。"""

import argparse
import copy
import importlib.util
import json
from pathlib import Path
import re
from uuid import uuid4
import zipfile


spec = importlib.util.spec_from_file_location("reduction_runtime", Path(__file__).with_name("check-precheck-explanation.py"))
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)


class Sources(base.Sources):
    """预算对端只在本地回环接收合成命令，保留原编号与摘要用于检测重复外发。"""

    def __init__(self, directory):
        super().__init__(directory)
        self.budget = "SUCCESS"
        self.commands = {}

    def finance(self, operation, request):
        data = request["data"]
        if operation == "budget-command":
            command, digest = data["command"], data["commandDigest"]
            identifier = command["id"]
            if identifier not in self.commands:
                expected = command.get("expected")
                self.commands[identifier] = {"command": command, "received": 0, "observation": {
                    "operationId": identifier, "commandDigest": digest, "status": "APPLIED",
                    "ledgerRevision": 1 if expected is None else expected["revision"] + 1,
                    "reference": "synthetic-budget-" + identifier, "appliedAt": base.instant()}}
            saved = self.commands[identifier]
            assert saved["command"] == command and saved["observation"]["commandDigest"] == digest
            saved["received"] += 1
            value = saved["observation"]
        elif operation == "budget-query":
            saved = self.commands.get(data["operationId"])
            value = saved["observation"] if saved else {**data, "status": "NOT_FOUND"}
            assert value["commandDigest"] == data["commandDigest"]
        else:
            return super().finance(operation, request)
        return {"contractVersion": 1, "tenantId": "demo", "requestId": request["requestId"], "outcome": "SUCCESS", "data": value}, 200


def workflow(runtime, path, application):
    """按当前应用选择真实财务待办，不能从其他验收申请借用任务编号。"""
    for actor in ("finance", "bob"):
        tasks = [task for task in runtime.call("GET", "/tasks", user=actor) if task["applicationId"] == application]
        if tasks:
            assert len(tasks) == 1, tasks
            value = runtime.call("GET", path + "/workflow?taskId=" + tasks[0]["taskId"], user=actor)
            value["fixtureActor"] = actor
            return value
    raise AssertionError("No matching expense task exists")


def confirmed(runtime, path, application):
    return base.wait_for(lambda: workflow(runtime, path, application), lambda value: value["budget"]["confirmedCurrent"])


def enter_finance(runtime, fixture):
    """经过预检、提交和人工审批接口进入财务核减节点，不写数据库状态。"""
    report = fixture["report"]; path = "/expense-reports/" + report["id"]
    check = base.precheck(runtime, fixture)
    assert check["job"]["status"] == "READY" and check["usable"], check
    runtime.call("POST", path + "/submit", {"applicationVersion": report["applicationVersion"],
        "financialVersion": report["financialVersion"], "precheckId": check["job"]["id"]})
    for _ in range(8):
        state = confirmed(runtime, path, report["applicationId"])
        if state["task"]["canReduce"]:
            return state
        if state["task"]["canReceive"]:
            runtime.call("POST", path + "/tasks/" + state["task"]["taskId"] + "/receive", {
                "applicationVersion": state["applicationVersion"], "financialVersion": state["financialVersion"], "comment": "合成材料签收"}, state["fixtureActor"])
            state = workflow(runtime, path, report["applicationId"])
        runtime.call("POST", "/tasks/" + state["task"]["taskId"] + "/actions", {
            "action": "APPROVE", "expectedVersion": state["applicationVersion"], "comment": "合成人工审批"}, state["fixtureActor"])
    raise AssertionError("Financial review task was not reached")


def reduction_input(state, lines):
    return {"applicationVersion": state["applicationVersion"], "financialVersion": state["financialVersion"],
            "lines": lines, "reasonCode": "OTHER", "comment": "核对各行依据后一次核减"}


def setup(runtime, sources, template_version):
    """保留旧在审与文件夹具，另配置业务审批人与财务分离的真实费用模板。"""
    fixture = base.setup(runtime, sources, template_version)
    department = runtime.call("POST", "/organization/units", {"kind": "DEPARTMENT", "name": "核减验收业务部门", "legalEntityId": sources.entity, "active": True}, "admin", 201)
    position = runtime.call("POST", "/organization/units", {"kind": "POSITION", "name": "核减验收业务岗位", "legalEntityId": sources.entity, "active": True}, "admin", 201)
    person = runtime.call("POST", "/organization/people", {"subject": "bob", "displayName": "核减验收业务审批人", "active": True, "approvalEligible": True}, "admin", 201)
    runtime.call("POST", "/organization/appointments", {"personId": person["id"], "departmentId": department["id"], "positionId": position["id"], "active": True}, "admin", 201)
    draft = runtime.call("POST", "/process-templates/expense-report/copy", {"key": "reduction-reasons", "name": "逐行原因验收", "templateVersion": template_version}, "admin")
    finance = next(node["properties"]["assigneeRule"] for record in runtime.records if record["method"] == "PUT" and record["path"].startswith("/process-definitions/")
                   for node in record["request"]["graph"]["nodes"] if node["id"] == "finance")
    for node in draft["graph"]["nodes"]:
        if node["type"] == "USER_TASK" and node["properties"].get("expenseStage") != "PROJECT_REVIEW":
            node["properties"]["assigneeRule"] = finance if node["properties"].get("expenseStage") else "role:ORG_PERSON_" + person["id"]
    draft = runtime.call("PUT", "/process-definitions/" + draft["id"], {"name": draft["name"], "graph": draft["graph"], "formSchema": draft["formSchema"],
        "notificationTexts": draft.get("notificationTexts", {}), "expectedRevision": draft["revision"]}, "admin")
    definition = runtime.call("POST", "/process-definitions/" + draft["id"] + "/publish?expectedRevision=" + str(draft["revision"]), {"changeNote": "合成业务与财务职责分离核减验收"}, "admin")
    fixture["report"] = runtime.call("POST", "/expense-reports", {"businessNo": "REDUCTION-REASONS", "processKey": definition["key"],
        "definitionVersion": definition["version"], "content": fixture["report"]["content"]}, expected=201)
    return fixture


def run(java, legacy, fixed, directory, result):
    sources = Sources(directory)
    runtime = base.Runtime(java, directory, sources)
    runtime.settings.update({"agentflow.budgets.worker-enabled": True, "agentflow.budgets.poll-delay-ms": 200})
    with zipfile.ZipFile(fixed) as archive:
        entry = next(name for name in archive.namelist() if re.fullmatch(r"BOOT-INF/lib/h2-[^/]+\.jar", name))
        h2 = directory / "h2.jar"; h2.write_bytes(archive.read(entry))
    try:
        runtime.start(legacy)
        template = next(item for item in runtime.call("GET", "/process-templates", user="admin") if item["key"] == "expense-report")
        fixture = setup(runtime, sources, template["templateVersion"])
        report = fixture["report"]; path = "/expense-reports/" + report["id"]
        content = copy.deepcopy(report["content"]); content["lines"][0]["lineNo"] = 3
        content["lines"].append({**copy.deepcopy(content["lines"][0]), "lineNo": 7})
        fixture["report"] = runtime.call("POST", path + "/revise", {"applicationVersion": report["applicationVersion"],
            "financialVersion": report["financialVersion"], "content": content})
        state = enter_finance(runtime, fixture)
        route = path + "/tasks/" + state["task"]["taskId"] + "/reduce"
        legacy_lines = [{"lineNo": 3, "approvedGross": "90", "approvedTax": "0"}, {"lineNo": 7, "approvedGross": "95", "approvedTax": "0"}]
        runtime.call("POST", route, reduction_input(state, legacy_lines), "finance")
        confirmed(runtime, path, report["applicationId"])
        old = runtime.call("GET", path, user="finance")
        original_application = runtime.call("GET", "/applications/" + fixture["applicationId"])
        assert len(old["financialRound"]["adjustments"]) == 1
        assert all("reasonCode" not in line for line in old["financialRound"]["adjustments"][0]["lineChanges"])
        base.save(directory / "legacy-detail.json", old)
        runtime.stop(); before = base.snapshot(runtime, h2, "before-upgrade")
        runtime.settings["agentflow.budgets.worker-enabled"] = False
        runtime.start(fixed, login=False); runtime.stop()
        after = base.snapshot(runtime, h2, "after-upgrade")
        assert before == after, "Code-only upgrade changed stored facts"
        result["upgrade"] = {"preservedTables": len(before), "preservedRows": sum(value["rows"] for value in before.values()), "newMigrations": 0}
        runtime.settings["agentflow.budgets.worker-enabled"] = True
        runtime.start(fixed)
        assert runtime.call("GET", path, user="finance") == old
        assert runtime.call("GET", "/applications/" + fixture["applicationId"]) == original_application
        state = confirmed(runtime, path, report["applicationId"])
        lines = [{"lineNo": 7, "approvedGross": "85", "approvedTax": "0", "reasonCode": "INELIGIBLE_COST"},
                 {"lineNo": 3, "approvedGross": "80", "approvedTax": "0", "reasonCode": "INVALID_INVOICE"}]
        invalid = copy.deepcopy(lines); invalid[1]["reasonCode"] = "UNRECOGNIZED_REASON"
        runtime.call("POST", route, reduction_input(state, invalid), "finance", expected=400)
        assert runtime.call("GET", path, user="finance") == old
        request = reduction_input(state, lines); key = str(uuid4())
        receipt = runtime.call("POST", route, request, "finance", key=key)
        assert runtime.call("POST", route, request, "finance", key=key) == receipt
        confirmed(runtime, path, report["applicationId"])
        current = runtime.call("GET", path, user="finance")
        assert current["financialVersion"] == old["financialVersion"] + 1
        assert current["applicationVersion"] == old["applicationVersion"] + 1
        assert current["roundNo"] == old["roundNo"]
        assert current["financialRound"]["originalLines"] == old["financialRound"]["originalLines"]
        history = current["financialRound"]["adjustments"]
        assert len(history) == 2 and history[0] == old["financialRound"]["adjustments"][0]
        assert [(line["lineNo"], line["reasonCode"]) for line in history[1]["lineChanges"]] == [(3, "INVALID_INVOICE"), (7, "INELIGIBLE_COST")]
        assert current["financialRound"]["approvedGross"] == {"value": "165.00", "currency": "CNY"}
        assert len(sources.commands) == 3 and all(value["received"] == 1 for value in sources.commands.values())
        runtime.stop(force=True); runtime.start(fixed)
        assert runtime.call("GET", path, user="finance") == current
        assert runtime.call("POST", route, request, "finance", key=key) == receipt
        assert runtime.call("GET", "/applications/" + fixture["applicationId"]) == original_application
        assert base.hashlib.sha256(runtime.call("GET", "/invoices/" + fixture["invoiceId"] + "/content")).hexdigest() == fixture["originalSha256"]
        assert len(sources.commands) == 3 and all(value["received"] == 1 for value in sources.commands.values())
        assert not sources.errors and not sources.model_calls
        assert {call["operation"] for call in sources.calls} <= {"catalog", "employee-account", "exchange-rate", "expense-policy", "budget-precheck", "budget-command", "budget-query"}
        base.save(directory / "verified-detail.json", current)
        base.save(directory / "synthetic-budget-commands.json", sources.commands)
        result.update(status="PASS", runtimeBoots=runtime.starts, httpRequests=len(runtime.records), legacyHistoryUnchanged=True,
                      explicitLineReasons=[3, 7], newAdjustmentCount=1, newBudgetCommandCount=1,
                      forceRestartPreservedFacts=True, replayBeforeAndAfterRestart=True, modelRequests=0,
                      browserRun=False, postgresRun=False, realEnterpriseServices=False)
    finally:
        runtime.stop(); sources.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True)
    parser.add_argument("--legacy-jar", type=Path, required=True)
    parser.add_argument("--fixed-jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(); directory = args.output.resolve()
    assert directory.is_relative_to(Path("/fyoung/tmp").resolve()), "Evidence must stay under /fyoung/tmp"
    directory.mkdir(parents=True, exist_ok=False)
    result = {"status": "RUNNING", "legacyJarSha256": base.digest(args.legacy_jar), "fixedJarSha256": base.digest(args.fixed_jar),
              "checkerSha256": base.digest(Path(__file__)), "sharedCheckerSha256": base.digest(Path(base.__file__))}
    try:
        run(args.java, args.legacy_jar.resolve(), args.fixed_jar.resolve(), directory, result)
    except Exception as error:
        result.update(status="FAILED", failureType=type(error).__name__, failure=str(error)); raise
    finally:
        base.save(directory / "evidence.json", result)
    print(json.dumps(result, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
