#!/usr/bin/env python3
"""固定 V117 升级至 V118：真实事前计划、累计例外审批、强退和独立配套恢复。"""

import argparse
from concurrent.futures import ThreadPoolExecutor
import copy
from http.cookiejar import CookieJar
import importlib.util
from pathlib import Path
import json
import shutil
import subprocess
import threading
from types import SimpleNamespace
from urllib.request import build_opener, HTTPCookieProcessor, ProxyHandler
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("split_runtime", ROOT / "scripts/check-expense-split-routing.py")
split = importlib.util.module_from_spec(spec)
spec.loader.exec_module(split)
risk = split.risk
save, digest, instant, wait_for = risk.save, risk.digest, risk.instant, risk.wait_for
BASELINE_SHA256 = "d151fc3f7462b6ba28225e7478277df6b088d2662bb6e18e77aa37f507358b90"
NEW_TABLES = {"EXPENSE_PRIOR_CONTROL", "EXPENSE_PRIOR_CONTROL_SOURCE"}


def money(value):
    return {"value": str(value), "currency": "CNY"}


def template(runtime, fixture, key, hidden=False):
    """复制随安装包发布的真实模板，仅将角色绑定到合成组织的既有人。"""
    catalog = runtime.call("GET", "/process-templates", user="admin")
    source = next(item for item in catalog if item["key"] == key)
    draft = runtime.call("POST", "/process-templates/" + key + "/copy", {
        "key": "f05-" + key + "-" + uuid4().hex[:8], "name": "合成事前验收 " + key,
        "templateVersion": source["templateVersion"]}, "admin")
    graph, schema = copy.deepcopy(draft["graph"]), copy.deepcopy(draft["formSchema"])
    for node in graph["nodes"]:
        if node["type"] == "USER_TASK":
            actor = "finance" if node["id"] in ("receipt", "finance", "recheck") else "manager"
            node["properties"]["assigneeRule"] = "role:ORG_PERSON_" + fixture["people"][actor]
    if hidden:
        next(field for field in schema["fields"] if field["key"] == "expenseDetails")["nodeAccess"]["supervisor"] = "HIDDEN"
    updated = runtime.call("PUT", "/process-definitions/" + draft["id"], {"name": draft["name"], "graph": graph,
        "formSchema": schema, "notificationTexts": draft["notificationTexts"], "expectedRevision": draft["revision"]}, "admin")
    return runtime.call("POST", "/process-definitions/" + draft["id"] + "/publish?expectedRevision=" + str(updated["revision"]),
                        {"changeNote": "合成组织实际办理模板"}, "admin")


def configure(runtime, mode):
    previous = runtime.call("GET", "/admin/expense-categories", user="admin")
    control = {"mode": mode}
    if mode == "TOLERANCE":
        control["toleranceFraction"] = 0.1
    saved = runtime.call("PUT", "/admin/expense-categories", {"expectedVersion": previous["version"],
        "categories": [{"code": "OFFICE", "name": "合成办公费", "units": ["ITEM"], "active": True, "priorControl": control}],
        "comment": "明确设置合成验收控制"}, "admin")
    active = runtime.call("GET", "/admin/expense-policies/current", user="admin")
    if active["activePolicy"] is None:
        definition = {"name": "合成允许制度", "rules": [{"key": "allow", "name": "合成允许规则",
            "match": {"legalEntityIds": [], "categoryCodes": ["OFFICE"], "cityTiers": [], "employeeGrades": []},
            "constraints": {"effect": "ALLOW", "allowedServiceLevels": [], "priorRequestRequired": False}}]}
        draft = runtime.call("PUT", "/admin/expense-policies/f05-runtime/draft", {"expectedRevision": 0,
            "definition": definition, "comment": "合成制度初始化"}, "admin")
        runtime.call("POST", "/admin/expense-policies/f05-runtime/publish", {"expectedDraftRevision": draft["revision"],
            "expectedCategoryRevision": saved["version"], "expectedActiveRevision": active["activeRevision"],
            "comment": "显式启用平台类别与制度"}, "admin")
    return saved


def plan(runtime, fixture, definition, amount="100"):
    content = {"legalEntityId": fixture["entityId"], "type": "DAILY", "title": "合成事前计划", "lines": [
        {"lineNo": 1, "categoryCode": "OFFICE", "plannedOn": instant()[:10], "cityCode": "SH", "amount": money(amount),
         "allocations": [{"costCenter": "IT", "amount": money(amount)}], "description": "合成计划用途"}]}
    value = runtime.call("POST", "/expense-plans", {"businessNo": "F05-PLAN-" + uuid4().hex, "processKey": definition["key"],
        "definitionVersion": definition["version"], "content": content}, expected=201)
    path = "/expense-plans/" + value["id"]
    options = runtime.call("GET", path + "/prechecks/options")
    check = runtime.call("POST", path + "/prechecks", {**{k: value[k] for k in ("applicationVersion", "planVersion")},
        "initiatorAppointmentId": fixture["appointments"]["alice"], "targetDigest": options["targetDigest"]}, expected=202)
    checked = wait_for(lambda: runtime.call("GET", path + "/prechecks/" + check["id"]),
                       lambda item: item["job"]["status"] not in ("QUEUED", "RUNNING"), 30)
    assert checked["job"]["status"] == "READY" and checked["usable"], checked
    runtime.call("POST", path + "/submit", {**{k: value[k] for k in ("applicationVersion", "planVersion")}, "precheckId": check["id"]})
    split.action(runtime, value, "manager"); split.action(runtime, value, "finance")
    approved = runtime.call("GET", path)
    assert approved["status"] == "APPROVED", approved
    return approved


def draft(runtime, fixture, definition, credit, amounts, reason=None):
    content = {"legalEntityId": fixture["entityId"], "type": "DAILY", "title": "合成额度报销", "advanceOffsets": [], "lines": []}
    for index, amount in enumerate(amounts, 1):
        line = {"lineNo": index, "categoryCode": "OFFICE", "incurredOn": instant()[:10], "cityCode": "SH", "quantity": 1,
                "unit": "ITEM", "claimedGross": money(amount), "claimedTax": money("0"), "invoiceIds": [],
                "allocations": [{"costCenter": "IT", "amount": money(amount)}], "description": "合成用途",
                "priorRequest": {"requestId": credit["id"], "lineNo": 1}}
        if reason is not None:
            line["exceptionReason"] = reason
        content["lines"].append(line)
    return runtime.call("POST", "/expense-reports", {"businessNo": "F05-EXP-" + uuid4().hex, "processKey": definition["key"],
        "definitionVersion": definition["version"], "content": content}, expected=201)


def check(runtime, fixture, report):
    return risk.common.precheck(runtime, {"report": report, "appointmentId": fixture["appointments"]["alice"]})


def view(runtime, report, round_no=1, user="alice", expected=200):
    return runtime.call("GET", "/expense-reports/" + report["id"] + "/prior-control?roundNo=" + str(round_no), user=user, expected=expected)


def credit_view(runtime, credit):
    page = runtime.call("GET", "/expense-requests?limit=100")
    return next(item for item in page["items"] if item["id"] == credit["id"])


def assert_task(runtime, report, definition, node_id, actor):
    current = split.task(runtime, report, actor)
    expected = next(node["name"] for node in definition["graph"]["nodes"] if node["id"] == node_id)
    assert current["taskName"] == expected, (node_id, current)
    return current


def concurrent(runtime, idp, fixture, reports):
    """独立连接同时提交同一额度上的旧预检，失败者只能重新预检。"""
    bodies = [split.prepare(runtime, fixture, item) for item in reports]
    barrier, clients = threading.Barrier(2), []
    for index in range(2):
        directory = runtime.directory / ("concurrent-" + str(index)); directory.mkdir()
        client = risk.Client(SimpleNamespace(base=runtime.base, starts=runtime.starts, directory=directory), idp)
        cookies = CookieJar()
        for cookie in runtime.client.sessions["alice"][1]:
            cookies.set_cookie(copy.copy(cookie))
        client.sessions["alice"] = (build_opener(ProxyHandler({}), HTTPCookieProcessor(cookies), risk.NoRedirect()), cookies)
        clients.append(client)
    keys = [str(uuid4()), str(uuid4())]
    def send(index):
        barrier.wait(timeout=10)
        return clients[index].call("POST", "/expense-reports/" + reports[index]["id"] + "/submit", bodies[index], key=keys[index], expected=(200, 422))
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(send, index) for index in range(2)]
        replies = [future.result(timeout=40) for future in futures]
    statuses = [client.records[-1]["status"] for client in clients]
    assert sorted(statuses) == [200, 422], statuses
    winner = statuses.index(200); loser = 1 - winner
    assert replies[loser]["code"] == "RESOURCES_CHANGED", replies
    for client in clients:
        runtime.client.records.extend(client.records)
    save(runtime.directory / "http-records.json", runtime.client.records)
    split.budget(runtime, reports[winner])
    assert not view(runtime, reports[winner])["requiresApproval"]
    view(runtime, reports[loser], expected=404)
    split.submit(runtime, fixture, reports[loser])
    assert view(runtime, reports[loser])["requiresApproval"]
    return reports[winner], reports[loser], {"path": "/expense-reports/" + reports[winner]["id"] + "/submit", "body": bodies[winner], "key": keys[winner], "result": replies[winner]}


def restore_schema(runtime, restored, h2):
    helper = ROOT / "scripts/payment-due-dates-support/CompareH2Schema.java"
    command = [runtime.java, "-Djava.io.tmpdir=/fyoung/tmp", "--class-path", str(h2), str(helper),
               str(runtime.directory / "before-restore-schema.sql"), str(restored.directory / "restored-before-start-schema.sql")]
    result = subprocess.run(command, check=True, text=True, capture_output=True, timeout=30)
    (restored.directory / "schema-comparison.json").write_text(result.stdout)
    return json.loads(result.stdout)


def run(args):
    directory = Path(args.output)
    assert str(directory).startswith("/fyoung/tmp/") and not directory.exists(), "Use a fresh owned temporary directory"
    directory.mkdir(parents=True)
    before_jar, after_jar = Path(args.before).resolve(), Path(args.after).resolve()
    assert digest(before_jar) == BASELINE_SHA256, "V117 baseline differs from preserved package"
    sources = risk.Sources(directory)
    # 复用连接遵循 HTTP/1.1，避免服务夹具关闭连接引起与业务无关的恢复噪声。
    sources.server.RequestHandlerClass.protocol_version = "HTTP/1.1"
    sources.base.server.RequestHandlerClass.protocol_version = "HTTP/1.1"
    idp, runtime, restored = None, None, None
    hold = {"enabled": False, "entered": threading.Event(), "release": threading.Event()}
    original_finance = sources.finance
    def finance(operation, request):
        if operation == "expense-policy" and request["data"].get("managedPolicy"):
            data = request["data"]; managed = data["managedPolicy"]; selection = managed["selection"]
            rule = next(rule for rule in managed["definition"]["rules"] if data["line"]["categoryCode"] in rule["match"]["categoryCodes"])
            assert rule["constraints"]["effect"] == "ALLOW"
            value = {"policy": {"policyId": selection["policyId"], "version": selection["policyVersion"],
                "assessedGross": data["line"]["claimedGross"], "allowedGross": data["line"]["claimedGross"], "decision": "WITHIN_LIMIT",
                "taxRuleReference": "synthetic-prior-tax", "evidenceReference": "synthetic-prior-assessment", "exceptionReasons": [],
                "managedPolicy": {"selection": selection, "ruleKey": rule["key"], "factSourceReference": "synthetic-prior-facts"}},
                "deductibleTax": data["line"]["claimedTax"], "priorRequestRequired": False, "validUntil": instant(3600)}
            result = ({"contractVersion": 1, "tenantId": "demo", "requestId": request["requestId"], "outcome": "SUCCESS", "data": value}, 200)
        else:
            result = original_finance(operation, request)
        if operation == "budget-command" and hold["enabled"]:
            save(directory / "held-peer-receipts.json", sources.commands)
            hold["entered"].set()
            assert hold["release"].wait(45), "Held budget reply was not released"
        return result
    sources.finance = finance
    evidence = {"status": "RUNNING", "startedAt": instant(), "beforeSha256": digest(before_jar), "afterSha256": digest(after_jar),
                "database": "H2", "browserVerified": False, "postgresqlVerified": False, "checks": []}
    save(directory / "evidence.json", evidence)
    for name in ("scripts/check-expense-prior-controls.py", "scripts/check-expense-split-routing.py", "scripts/check-expense-risk.py",
                 "scripts/check-precheck-explanation.py", "scripts/payment-due-dates-support/CompareH2Schema.java",
                 "scripts/fixtures/ExpenseRiskOidcFixture.java", "agentflow-server/src/test/java/io/agentflow/auth/OidcTestProvider.java"):
        target = directory / "harness-source" / name; target.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(ROOT / name, target)
    try:
        idp = risk.IdentityProvider(args.java, directory, after_jar)
        runtime = risk.Runtime(args.java, directory / "original", sources, idp)
        risk.ROLES["admin"] = risk.ROLES["admin"] | {"FINANCE_CONFIG_ADMIN"}
        runtime.settings["agentflow.auth.oidc.role-mappings.admins[1]"] = "FINANCE_CONFIG_ADMIN"
        runtime.settings["agentflow.expense-plans.precheck-worker-enabled"] = True
        runtime.settings["agentflow.expense-plans.precheck-poll-delay-ms"] = 200
        runtime.start(before_jar)
        fixture = risk.setup(runtime, sources); fixture["entityId"] = sources.base.entity
        for report in fixture["reports"]:
            split.budget(runtime, report)
        plan_definition = template(runtime, fixture, "expense-plan")
        legacy_credit = plan(runtime, fixture, plan_definition)
        legacy_definition = {"key": "risk-runtime", "version": 1}
        legacy = split.submit(runtime, fixture, draft(runtime, fixture, legacy_definition, legacy_credit, ["70"]))
        old_report, old_credit = split.detail(runtime, legacy), credit_view(runtime, legacy_credit)
        assert "control" not in old_credit["lines"][0] and "hardLimit" not in old_credit["lines"][0]
        fixture.update(legacyCredit=legacy_credit, legacyReport=legacy); save(directory / "fixture.json", fixture)
        runtime.stop()
        before = split.columns_snapshot(runtime, idp.h2, "before-upgrade")
        originals = {str(p.relative_to(runtime.directory / "attachments")): digest(p) for p in (runtime.directory / "attachments").rglob("*") if p.is_file()}
        assert len(originals) == 3 and not NEW_TABLES.intersection(before["tables"])
        runtime.start(after_jar); runtime.stop()
        after = split.columns_snapshot(runtime, idp.h2, "after-upgrade", runtime.directory / "before-upgrade-columns.tsv")
        assert set(after["tables"]) - set(before["tables"]) == NEW_TABLES
        changed = []
        for name, prior in before["tables"].items():
            current = after["tables"][name]
            if (prior["rows"], prior["sha256"]) != (current["rows"], current["originalColumnsSha256"]):
                changed.append(name)
            assert before["columns"][name] == after["columns"][name], name
            assert before["metadata"][name] == after["metadata"][name], name
        assert changed == ["flyway_schema_history"], changed
        assert all(after["tables"][table]["rows"] == 0 for table in NEW_TABLES)
        evidence["checks"].append({"name": "nonempty-upgrade", "oldTables": len(before["tables"]), "oldRows": sum(v["rows"] for v in before["tables"].values()),
            "changedOldTables": changed, "addedTables": sorted(NEW_TABLES), "originalFiles": len(originals)})
        risk.stage("UPGRADE_VERIFIED", oldTables=len(before["tables"]))
        runtime.start(after_jar)
        assert split.detail(runtime, legacy) == old_report
        assert runtime.call("GET", "/expense-plans/" + legacy_credit["id"]) == legacy_credit
        assert view(runtime, legacy)["status"] == "NOT_RECORDED"
        assert credit_view(runtime, legacy_credit)["lines"][0]["hardLimit"]
        blocked = check(runtime, fixture, draft(runtime, fixture, legacy_definition, legacy_credit, ["31"]))
        assert blocked["job"]["status"] == "BLOCKED", blocked
        definition = template(runtime, fixture, "expense-report")
        assert any(field["key"] == "priorRequestOverTolerance" for field in definition["formSchema"]["fields"])
        credits = {}
        for mode in ("STRICT", "TOLERANCE", "NONE"):
            configuration = configure(runtime, mode)
            credit = plan(runtime, fixture, plan_definition, "50" if mode == "NONE" else "100")
            row = credit_view(runtime, credit)["lines"][0]
            assert row["control"]["control"]["mode"] == mode and row["control"]["categoryRevision"] == configuration["version"]
            assert row["hardLimit"] == (mode == "STRICT")
            credits[mode] = credit
        assert check(runtime, fixture, draft(runtime, fixture, definition, credits["STRICT"], ["101"], "合成原因"))["job"]["status"] == "BLOCKED"
        missing = check(runtime, fixture, draft(runtime, fixture, definition, credits["TOLERANCE"], ["50", "70"]))
        assert missing["job"]["status"] == "BLOCKED" and any(item["code"] == "PRIOR_REQUEST_EXCEPTION_REASON_REQUIRED" for item in missing["findings"]), missing
        excess = split.submit(runtime, fixture, draft(runtime, fixture, definition, credits["TOLERANCE"], ["50", "70"], "合成累计超额说明"))
        frozen = view(runtime, excess)
        assert frozen["requiresApproval"] and len(frozen["details"]["assessments"]) == 2
        assert all(item["totalExposure"]["value"] == "120.00" and item["exceeded"]["value"] == "10.00" for item in frozen["details"]["assessments"])
        split.action(runtime, excess); assert_task(runtime, excess, definition, "priorReview", "manager")
        assert view(runtime, excess, user="manager") == frozen
        view(runtime, excess, user="admin", expected=403); view(runtime, excess, user="bob", expected=404)
        prior_task = split.task(runtime, excess, "manager")
        body = {"action": "APPROVE", "expectedVersion": prior_task["version"], "comment": "合成独立审批"}
        path, key = "/tasks/" + prior_task["taskId"] + "/actions", str(uuid4())
        lost = runtime.client.lose_response(path, body, key, 200)
        assert runtime.call("POST", path, body, "manager", key=key) == lost
        assert runtime.client.records[-1]["replayed"] == "true"
        assert_task(runtime, excess, definition, "receipt", "finance")
        none = split.submit(runtime, fixture, draft(runtime, fixture, definition, credits["NONE"], ["150"]))
        assert not view(runtime, none)["requiresApproval"]
        split.action(runtime, none); assert_task(runtime, none, definition, "receipt", "finance")
        assert credit_view(runtime, credits["NONE"])["lines"][0]["exceeded"]["value"] == "100.00"
        evidence["checks"].append({"name": "three-modes-frozen-config-multiline-and-template-v5", "legacyHardLimitPreserved": True,
            "modes": list(credits), "sameSourceLines": 2, "exposure": "120.00", "threshold": "110.00", "independentReviewDespiteSameApprover": True,
            "lostApprovalResponseReplayed": True, "adminSensitiveReadDenied": True})
        risk.stage("THREE_MODES_AND_INDEPENDENT_APPROVAL_VERIFIED")

        configure(runtime, "TOLERANCE")
        shared = plan(runtime, fixture, plan_definition, "150")
        reports = [draft(runtime, fixture, definition, shared, ["100"], "合成共享额度") for _ in range(2)]
        winner, loser, receipt = concurrent(runtime, idp, fixture, reports)
        initial_winner, initial_loser = view(runtime, winner), view(runtime, loser)
        assert initial_loser["details"]["assessments"][0]["otherReserved"]["value"] == "100.00"
        runtime.stop(force=True); runtime.start(after_jar)
        assert runtime.call("POST", receipt["path"], receipt["body"], key=receipt["key"]) == receipt["result"]
        assert runtime.client.records[-1]["replayed"] == "true" and view(runtime, winner) == initial_winner
        current = split.detail(runtime, winner)
        runtime.call("POST", "/expense-reports/" + winner["id"] + "/withdraw", {**{k: current[k] for k in ("applicationVersion", "financialVersion")}, "comment": "合成撤回"})
        # 撤回不代表新轮次预算已确认；保留原冻结后由重提重新绑定。
        assert split.detail(runtime, winner)["applicationStatus"] == "WITHDRAWN"
        split.submit(runtime, fixture, winner); second_round = view(runtime, winner, 2)
        assert second_round["details"]["assessments"][0]["totalExposure"]["value"] == "200.00"
        split.action(runtime, winner, choice="RETURN")
        split.submit(runtime, fixture, winner); third_round = view(runtime, winner, 3)
        assert third_round["requiresApproval"] and view(runtime, winner, 1) == initial_winner and view(runtime, winner, 2) == second_round
        assert credit_view(runtime, shared)["lines"][0]["reserved"]["value"] == "200.00"
        evidence["checks"].append({"name": "concurrent-stale-precheck-and-cross-round", "concurrentStatuses": [200, 422], "failureCode": "RESOURCES_CHANGED",
            "restartOriginalSubmitReplay": True, "rounds": 3, "currentReservation": "200.00", "oldEvidenceUnchanged": True})
        risk.stage("CONCURRENCY_AND_CROSS_ROUND_VERIFIED")

        close_body = {"expectedVersion": credit_view(runtime, credits["TOLERANCE"])["version"], "comment": "合成关闭原额度"}
        runtime.call("POST", "/expense-requests/" + credits["TOLERANCE"]["id"] + "/close", close_body)
        closed = check(runtime, fixture, draft(runtime, fixture, definition, credits["TOLERANCE"], ["1"], "合成关闭后新单"))
        assert closed["job"]["status"] == "BLOCKED", closed
        split.action(runtime, excess, "finance")
        current = split.detail(runtime, excess); current_task = assert_task(runtime, excess, definition, "finance", "finance")
        hold["enabled"] = True
        runtime.call("POST", "/expense-reports/" + excess["id"] + "/tasks/" + current_task["taskId"] + "/reduce", {
            **{k: current[k] for k in ("applicationVersion", "financialVersion")}, "lines": [
                {"lineNo": 1, "approvedGross": "30.00", "approvedTax": "0.00"}, {"lineNo": 2, "approvedGross": "40.00", "approvedTax": "0.00"}],
            "reasonCode": "INELIGIBLE_COST", "comment": "合成已关闭额度核减"}, "finance")
        assert hold["entered"].wait(10), "Budget receiver not reached"
        original_commands = copy.deepcopy(sources.commands)
        runtime.stop(force=True); hold["enabled"] = False; hold["release"].set()
        runtime.start(after_jar)
        split.budget(runtime, excess, timeout=120)
        assert sources.commands == original_commands, "Recovery must query original budget identities"
        assert view(runtime, excess) == frozen
        split.action(runtime, excess, "finance")
        assert split.detail(runtime, excess)["applicationStatus"] == "APPROVED"
        assert credit_view(runtime, credits["TOLERANCE"])["lines"][0]["reserved"]["value"] == "70.00"
        assert view(runtime, excess) == frozen
        evidence["checks"].append({"name": "closed-credit-reduction-and-lost-budget-reply", "frozenExposure": "120.00", "reducedReservation": "70.00",
            "originalBudgetIdentitiesPreserved": True, "newUsageBlocked": True})
        risk.stage("CLOSED_REDUCTION_AND_BUDGET_RESTART_VERIFIED")

        hidden_definition = template(runtime, fixture, "expense-report", hidden=True)
        hidden = split.submit(runtime, fixture, draft(runtime, fixture, hidden_definition, credits["NONE"], ["1"]))
        view(runtime, hidden, user="manager", expected=403)
        runtime.call("GET", "/expense-reports/" + winner["id"] + "/prior-control?roundNo=3&amount=1", expected=400)
        original_task = split.task(runtime, winner, "manager")
        runtime.stop()
        preserved = split.columns_snapshot(runtime, idp.h2, "before-restore")
        backup = directory / "backup"; backup.mkdir()
        for name in ("data", "attachments"):
            shutil.copytree(runtime.directory / name, backup / name)
        save(backup / "synthetic-peer-state.json", {"commands": sources.commands, "invoices": sources.invoices, "entity": sources.base.entity})
        save(backup / "manifest.json", {"jarSha256": digest(after_jar), "files": {str(p.relative_to(backup)): digest(p) for p in backup.rglob("*") if p.is_file()}})
        source_database = digest(runtime.directory / "data/agentflow.mv.db")
        restored = risk.Runtime(args.java, directory / "restored", sources, idp)
        restored.settings["agentflow.auth.oidc.role-mappings.admins[1]"] = "FINANCE_CONFIG_ADMIN"
        for name in ("data", "attachments"):
            shutil.copytree(backup / name, restored.directory / name)
        restored.process = runtime.process
        assert split.columns_snapshot(restored, idp.h2, "restored-before-start") == preserved
        schema = restore_schema(runtime, restored, idp.h2)
        peer = json.loads((backup / "synthetic-peer-state.json").read_text())
        sources.commands, sources.invoices, sources.base.entity = peer["commands"], peer["invoices"], peer["entity"]
        before_commands = copy.deepcopy(sources.commands)
        restored.start(after_jar); restored.client.sessions = runtime.client.sessions
        restored.client.identities = copy.deepcopy(runtime.client.identities)
        assert split.task(restored, winner, "manager") == original_task and view(restored, winner, 3) == third_round
        assert view(restored, excess) == frozen
        for index, invoice in enumerate(fixture["invoices"]):
            assert restored.call("GET", "/invoices/" + invoice + "/content") == ("%PDF-1.7\nsynthetic invoice " + str(index) + "\n%%EOF").encode()
        approval = split.action(restored, winner, key=str(uuid4()))
        assert_task(restored, winner, definition, "priorReview", "manager")
        assert restored.call("POST", approval["path"], approval["body"], "manager", key=approval["key"]) == approval["result"]
        assert restored.client.records[-1]["replayed"] == "true"
        split.action(restored, winner); assert_task(restored, winner, definition, "receipt", "finance")
        assert view(restored, winner, 3) == third_round and sources.commands == before_commands
        restored.stop()
        assert digest(runtime.directory / "data/agentflow.mv.db") == source_database
        assert originals == {str(p.relative_to(restored.directory / "attachments")): digest(p) for p in (restored.directory / "attachments").rglob("*") if p.is_file()}
        evidence["checks"].append({"name": "independent-paired-restore-original-review-and-no-new-funding", "tables": len(preserved["tables"]),
            "rows": sum(item["rows"] for item in preserved["tables"].values()), "schemaStatements": schema["statements"],
            "priorSnapshots": preserved["tables"]["EXPENSE_PRIOR_CONTROL"]["rows"], "originalDatabaseUnchanged": True, "attachments": len(originals)})
        assert not sources.errors and not sources.base.errors, (sources.errors, sources.base.errors)
        assert not sources.model_calls and not sources.base.model_calls
        evidence.update(status="PASSED", finishedAt=instant(), httpRecords=len(runtime.client.records) + len(restored.client.records),
                        providerCalls=len(sources.finance_calls), distinctBudgetCommands=len(sources.commands), boots=runtime.starts + restored.starts)
        risk.stage("PAIRED_RESTORE_VERIFIED", tables=len(preserved["tables"]))
    except BaseException as error:
        evidence.update(status="FAILED", finishedAt=instant(), failure=repr(error)); raise
    finally:
        hold["enabled"] = False; hold["release"].set()
        for owned in (restored, runtime):
            if owned is not None:
                owned.stop()
        if idp is not None:
            idp.close()
        sources.close()
        evidence["ownedProcessesStopped"] = all(owned is None or owned.process is None or owned.process.poll() is not None for owned in (runtime, restored, idp))
        save(directory / "evidence.json", evidence)
        risk.stage("FINISHED", status=evidence["status"], output=str(directory))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", required=True, help="Preserved V117 JAR")
    parser.add_argument("--after", required=True, help="Fixed V118 JAR")
    parser.add_argument("--java", required=True, help="Absolute Java 17 executable")
    parser.add_argument("--output", required=True, help="Fresh directory under /fyoung/tmp")
    run(parser.parse_args())
