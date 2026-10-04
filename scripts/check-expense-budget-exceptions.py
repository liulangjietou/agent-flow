#!/usr/bin/env python3
"""固定 V118 升级至 V119：真实预算节点、原键恢复、强退及配套备份恢复。"""

import argparse
from concurrent.futures import ThreadPoolExecutor
import copy
from http.cookiejar import CookieJar
import importlib.util
import json
from pathlib import Path
import shutil
import threading
from types import SimpleNamespace
from urllib.request import build_opener, HTTPCookieProcessor, ProxyHandler
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("prior_runtime", ROOT / "scripts/check-expense-prior-controls.py")
prior = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prior)
split, risk = prior.split, prior.risk
save, digest, instant, wait_for = risk.save, risk.digest, risk.instant, risk.wait_for
BASELINE_SHA256 = "33f721c9913d8cdf854663de2d8f2e13bd6b9a7fa63f839294182a0349edbe3f"
NEW_TABLES = {"EXPENSE_BUDGET_REVIEW", "EXPENSE_BUDGET_REVIEW_REVISION"}
POLICY = "synthetic-budget-policy-v1"


def sources_for(directory):
    """合成接收端持久保留原命令；仅由明确用例改变外部处理结果。"""
    sources = risk.Sources(directory)
    sources.server.RequestHandlerClass.protocol_version = "HTTP/1.1"
    sources.base.server.RequestHandlerClass.protocol_version = "HTTP/1.1"
    original, lock = sources.finance, threading.RLock()
    modes = {}
    hold = {"reportId": None, "entered": threading.Event(), "release": threading.Event()}

    def observation(command, fingerprint, outcome, policy):
        value = {"operationId": command["id"], "commandDigest": fingerprint}
        if outcome == "APPLIED":
            value.update(status="APPLIED", ledgerRevision=(command.get("expected") or {}).get("revision", 0) + 1,
                         reference="synthetic-budget-" + command["id"], appliedAt=instant())
        elif outcome == "PENDING":
            value["status"] = "PENDING"
        else:
            value.update(status="REJECTED", rejection="BUDGET_EXCEPTION_REQUIRED" if outcome == "SOFT" else "BUDGET_INSUFFICIENT")
            if outcome == "SOFT":
                assert policy
                value["exceptionOffer"] = {"policyReference": policy, "reference": "synthetic-offer-" + command["id"]}
        return value

    def move(operation_id, outcome):
        with lock:
            stored = sources.commands[operation_id]
            report_id = stored["command"]["position"]["reportId"]
            stored["observation"] = observation(stored["command"], stored["observation"]["commandDigest"], outcome, modes[report_id]["policy"])

    def finance(operation, request):
        data = request["data"]
        if operation == "budget-precheck":
            result, status = original(operation, request)
            if modes.get(data["reportId"], {}).get("policy"):
                result["data"]["exceptionPolicy"] = {"reference": modes[data["reportId"]]["policy"]}
            return result, status
        if operation not in ("budget-command", "budget-query"):
            return original(operation, request)
        with lock:
            if operation == "budget-command":
                command, fingerprint = data["command"], data["commandDigest"]
                identifier, report_id = command["id"], command["position"]["reportId"]
                mode = modes.get(report_id, {"policy": None, "original": "APPLIED", "authorized": "APPLIED"})
                approval = command.get("exceptionApproval")
                if approval:
                    previous = sources.commands[approval["originalOperationId"]]
                    assert previous["observation"]["status"] == "REJECTED"
                    assert approval["originalCommandDigest"] == previous["observation"]["commandDigest"]
                    assert approval["policyReference"] == mode["policy"]
                    assert approval["offerReference"] == previous["observation"]["exceptionOffer"]["reference"]
                    assert command["position"] == previous["command"]["position"] and command["action"] == previous["command"]["action"]
                    assert command.get("expected") == previous["command"].get("expected") and identifier != previous["command"]["id"]
                    assert approval["actorId"] == "manager" and approval["taskId"] and approval["auditEventId"]
                if identifier not in sources.commands:
                    sources.commands[identifier] = {"command": command, "observation": observation(command, fingerprint,
                        mode["authorized" if approval else "original"], mode["policy"])}
                stored = sources.commands[identifier]
                assert stored["command"] == command and stored["observation"]["commandDigest"] == fingerprint
                value = copy.deepcopy(stored["observation"])
                save(directory / "synthetic-peer-state.json", sources.commands)
            else:
                stored = sources.commands.get(data["operationId"])
                value = copy.deepcopy(stored["observation"]) if stored else {**data, "status": "NOT_FOUND"}
                assert value["commandDigest"] == data["commandDigest"]
        if operation == "budget-command" and command.get("exceptionApproval") and report_id == hold["reportId"]:
            hold["entered"].set()
            assert hold["release"].wait(45), "Held budget reply was not released"
        return {"contractVersion": 1, "tenantId": "demo", "requestId": request["requestId"], "outcome": "SUCCESS", "data": value}, 200

    sources.finance = finance
    return sources, modes, hold, move


def view(runtime, report, round_no=1, user="alice", expected=200):
    return runtime.call("GET", "/expense-reports/" + report["id"] + "/budget-review?roundNo=" + str(round_no), user=user, expected=expected)


def reviewed(runtime, report, status, round_no=1, timeout=40):
    return wait_for(lambda: view(runtime, report, round_no), lambda value: value.get("details", {}).get("status") == status, timeout)


def submit(runtime, fixture, report):
    body = split.prepare(runtime, fixture, report)
    path, key = "/expense-reports/" + report["id"] + "/submit", str(uuid4())
    result = runtime.call("POST", path, body, key=key)
    return {"path": path, "body": body, "key": key, "result": result}


def new_report(runtime, fixture, definition, modes, original="APPLIED", authorized="APPLIED", flexible=False):
    report = split.draft(runtime, fixture, definition, "100")
    modes[report["id"]] = {"policy": POLICY if flexible else None, "original": original, "authorized": authorized}
    return report


def original_commands(sources, report):
    return {key: copy.deepcopy(value) for key, value in sources.commands.items() if value["command"]["position"]["reportId"] == report["id"]}


def stop_round(runtime, report, action):
    current = split.detail(runtime, report)
    runtime.call("POST", "/expense-reports/" + report["id"] + "/" + action,
                 {**{key: current[key] for key in ("applicationVersion", "financialVersion")}, "comment": "合成原轮次结束"})


def concurrent_approval(runtime, idp, report):
    """两个独立 HTTP 连接竞争同一原任务，不能生成第二份审批授权。"""
    task = split.task(runtime, report, "manager")
    body = {"action": "APPROVE", "expectedVersion": task["version"], "comment": "合成并发预算批准"}
    path, barrier, clients = "/tasks/" + task["taskId"] + "/actions", threading.Barrier(2), []
    keys = [str(uuid4()), str(uuid4())]
    for index in range(2):
        directory = runtime.directory / ("budget-concurrent-" + str(index)); directory.mkdir()
        client = risk.Client(SimpleNamespace(base=runtime.base, starts=runtime.starts, directory=directory), idp)
        cookies = CookieJar()
        for cookie in runtime.client.sessions["manager"][1]:
            cookies.set_cookie(copy.copy(cookie))
        client.sessions["manager"] = (build_opener(ProxyHandler({}), HTTPCookieProcessor(cookies), risk.NoRedirect()), cookies)
        clients.append(client)
    def send(index):
        barrier.wait(timeout=10)
        return clients[index].call("POST", path, body, "manager", key=keys[index], expected=(200, 404, 409))
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(send, index) for index in range(2)]
        replies = [future.result(timeout=40) for future in futures]
    statuses = [client.records[-1]["status"] for client in clients]
    assert statuses.count(200) == 1, statuses
    winner = statuses.index(200)
    for client in clients:
        runtime.client.records.extend(client.records)
    save(runtime.directory / "http-records.json", runtime.client.records)
    return {"path": path, "body": body, "key": keys[winner], "result": replies[winner], "statuses": statuses}


def run(args):
    directory = Path(args.output)
    assert str(directory).startswith("/fyoung/tmp/") and not directory.exists(), "Use a fresh owned temporary directory"
    directory.mkdir(parents=True)
    before_jar, after_jar = Path(args.before).resolve(), Path(args.after).resolve()
    assert digest(before_jar) == BASELINE_SHA256, "V118 baseline differs from preserved package"
    sources, modes, hold, move = sources_for(directory)
    idp, runtime, restored = None, None, None
    evidence = {"status": "RUNNING", "startedAt": instant(), "beforeSha256": digest(before_jar), "afterSha256": digest(after_jar),
                "database": "H2", "browserVerified": False, "postgresqlVerified": False, "checks": []}
    save(directory / "evidence.json", evidence)
    for name in ("scripts/check-expense-budget-exceptions.py", "scripts/check-expense-prior-controls.py", "scripts/check-expense-split-routing.py",
                 "scripts/check-expense-risk.py", "scripts/check-precheck-explanation.py", "scripts/payment-due-dates-support/CompareH2Schema.java",
                 "scripts/fixtures/ExpenseRiskOidcFixture.java", "agentflow-server/src/test/java/io/agentflow/auth/OidcTestProvider.java"):
        target = directory / "harness-source" / name; target.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(ROOT / name, target)
    try:
        idp = risk.IdentityProvider(args.java, directory, after_jar)
        runtime = risk.Runtime(args.java, directory / "original", sources, idp)
        runtime.settings["agentflow.expenses.budget-review-worker-enabled"] = True
        runtime.settings["agentflow.expenses.budget-review-poll-delay-ms"] = 200
        runtime.start(before_jar)
        fixture = risk.setup(runtime, sources); fixture["entityId"] = sources.base.entity
        for report in fixture["reports"]:
            split.budget(runtime, report)
        legacy = fixture["reports"][0]
        old_report, old_task = split.detail(runtime, legacy), split.task(runtime, legacy, "manager")
        original_submit = next(value for value in runtime.client.records if value["method"] == "POST" and value["path"].endswith("/" + legacy["id"] + "/submit"))
        runtime.stop()
        before = split.columns_snapshot(runtime, idp.h2, "before-upgrade")
        originals = {str(path.relative_to(runtime.directory / "attachments")): digest(path) for path in (runtime.directory / "attachments").rglob("*") if path.is_file()}
        assert len(originals) == 3 and not NEW_TABLES.intersection(before["tables"])
        runtime.start(after_jar); runtime.stop()
        after = split.columns_snapshot(runtime, idp.h2, "after-upgrade", runtime.directory / "before-upgrade-columns.tsv")
        assert set(after["tables"]) - set(before["tables"]) == NEW_TABLES
        changed = []
        for name, table in before["tables"].items():
            if (table["rows"], table["sha256"]) != (after["tables"][name]["rows"], after["tables"][name]["originalColumnsSha256"]):
                changed.append(name)
            assert before["columns"][name] == after["columns"][name] and before["metadata"][name] == after["metadata"][name], name
        assert changed == ["flyway_schema_history"], changed
        assert all(after["tables"][name]["rows"] == 0 for name in NEW_TABLES)
        evidence["checks"].append({"name": "nonempty-v118-upgrade", "oldTables": len(before["tables"]), "oldRows": sum(item["rows"] for item in before["tables"].values()), "changedOldTables": changed, "addedTables": sorted(NEW_TABLES)})
        risk.stage("UPGRADE_VERIFIED", oldTables=len(before["tables"]))
        runtime.start(after_jar)
        assert split.detail(runtime, legacy) == old_report and split.task(runtime, legacy, "manager") == old_task
        assert view(runtime, legacy)["status"] == "NOT_RECORDED"
        assert runtime.call("POST", original_submit["path"].removeprefix("/api/v1"), original_submit["request"], key=original_submit["key"]) == original_submit["response"]
        assert runtime.client.records[-1]["replayed"] == "true"
        old_definition = {"key": "risk-runtime", "version": 1}
        rigid = new_report(runtime, fixture, old_definition, modes, original="RIGID")
        submit(runtime, fixture, rigid)
        wait_for(lambda: split.detail(runtime, rigid), lambda value: value["applicationStatus"] == "RETURNED", 40)
        incompatible = new_report(runtime, fixture, old_definition, modes, original="SOFT", flexible=True)
        body = split.prepare(runtime, fixture, incompatible)
        failure = runtime.call("POST", "/expense-reports/" + incompatible["id"] + "/submit", body, expected=422)
        assert failure["code"] == "EXPENSE_BUDGET_APPROVAL_REQUIRED" and not original_commands(sources, incompatible)
        definition = prior.template(runtime, fixture, "expense-report")
        normal = new_report(runtime, fixture, definition, modes)
        submit(runtime, fixture, normal); reviewed(runtime, normal, "CONFIRMED")
        split.action(runtime, normal)
        prior.assert_task(runtime, normal, definition, "receipt", "finance")
        assert view(runtime, normal)["details"]["automaticPass"] and view(runtime, normal)["details"]["decision"] is None
        evidence["checks"].append({"name": "legacy-rigidity-and-v6-template", "oldSubmitReplay": True, "missingBudgetNodeBlocked": True, "confirmedAutomaticPass": True})
        risk.stage("LEGACY_AND_AUTOMATIC_PASS_VERIFIED")

        flexible = new_report(runtime, fixture, definition, modes, original="PENDING", authorized="PENDING", flexible=True)
        submit(runtime, fixture, flexible)
        waiting = reviewed(runtime, flexible, "WAITING_BUDGET")
        split.action(runtime, flexible)
        current = prior.assert_task(runtime, flexible, definition, "budgetReview", "manager")
        assert "APPROVE" not in current["allowedActions"] and "RETURN" in current["allowedActions"]
        workflow = runtime.call("GET", "/expense-reports/" + flexible["id"] + "/workflow?taskId=" + current["taskId"], user="manager")
        assert not workflow["task"]["canApprove"] and workflow["task"]["approvalUnavailable"] == "EXPENSE_BUDGET_RESULT_PENDING"
        denied = runtime.call("POST", "/tasks/" + current["taskId"] + "/actions", {"action": "APPROVE", "expectedVersion": current["version"], "comment": "合成越过预算尝试"}, "manager", expected=422)
        assert denied["code"] == "EXPENSE_BUDGET_RESULT_PENDING"
        wait_for(lambda: original_commands(sources, flexible), lambda value: waiting["details"]["originalOperationId"] in value, 20)
        move(waiting["details"]["originalOperationId"], "SOFT"); reviewed(runtime, flexible, "REVIEW_REQUIRED")
        decision = concurrent_approval(runtime, idp, flexible)
        authorized = reviewed(runtime, flexible, "AUTHORIZED")
        assert authorized["details"]["decision"]["actorId"] == "manager"
        assert runtime.call("POST", decision["path"], decision["body"], "manager", key=decision["key"]) == decision["result"]
        assert runtime.client.records[-1]["replayed"] == "true"
        wait_for(lambda: original_commands(sources, flexible), lambda value: len(value) == 2, 20)
        split.action(runtime, flexible, "finance")
        finance_task = prior.assert_task(runtime, flexible, definition, "finance", "finance")
        assert "APPROVE" not in finance_task["allowedActions"]
        blocked = runtime.call("POST", "/tasks/" + finance_task["taskId"] + "/actions", {"action": "APPROVE", "expectedVersion": finance_task["version"], "comment": "合成待预算财务尝试"}, "finance", expected=422)
        assert blocked["code"] == "EXPENSE_BUDGET_NOT_CONFIRMED"
        move(authorized["details"]["authorizedOperationId"], "APPLIED")
        reviewed(runtime, flexible, "CONFIRMED"); split.budget(runtime, flexible)
        evidence["checks"].append({"name": "dynamic-original-result-and-concurrent-authorization", "concurrentStatuses": decision["statuses"], "distinctBudgetCommands": 2, "originalKeyReplay": True, "pendingFinanceGuard": True})
        risk.stage("DYNAMIC_AND_CONCURRENT_APPROVAL_VERIFIED")

        split.action(runtime, flexible, "finance", choice="RETURN")
        first_round = view(runtime, flexible); assert first_round["details"]["closure"] == "RETURNED"
        modes[flexible["id"]]["original"] = "APPLIED"
        submit(runtime, fixture, flexible); reviewed(runtime, flexible, "CONFIRMED", 2)
        stop_round(runtime, flexible, "withdraw")
        second_round = view(runtime, flexible, 2); assert second_round["details"]["closure"] == "WITHDRAWN"
        submit(runtime, fixture, flexible); third_round = reviewed(runtime, flexible, "CONFIRMED", 3)
        assert third_round["details"]["decision"] is None and view(runtime, flexible) == first_round and view(runtime, flexible, 2) == second_round
        assert len({item["details"]["originalOperationId"] for item in (first_round, second_round, third_round)}) == 3

        refused = new_report(runtime, fixture, definition, modes, original="SOFT", authorized="RIGID", flexible=True)
        submit(runtime, fixture, refused); reviewed(runtime, refused, "REVIEW_REQUIRED"); split.action(runtime, refused)
        split.action(runtime, refused)
        wait_for(lambda: split.detail(runtime, refused), lambda value: value["applicationStatus"] == "RETURNED", 40)
        refusal = view(runtime, refused)
        assert refusal["details"]["closure"] == "RETURNED" and refusal["details"]["authorizedOperationStatus"] == "REJECTED"
        assert len(original_commands(sources, refused)) == 2
        evidence["checks"].append({"name": "three-rounds-and-refused-authorization", "rounds": 3, "originalFactsPreserved": True, "refusedCommands": 2})

        crash = new_report(runtime, fixture, definition, modes, original="SOFT", flexible=True)
        submit(runtime, fixture, crash); reviewed(runtime, crash, "REVIEW_REQUIRED"); split.action(runtime, crash)
        current = prior.assert_task(runtime, crash, definition, "budgetReview", "manager")
        path, key = "/tasks/" + current["taskId"] + "/actions", str(uuid4())
        body = {"action": "APPROVE", "expectedVersion": current["version"], "comment": "合成丢失预算审批回执"}
        hold["reportId"] = crash["id"]
        lost = runtime.client.lose_response(path, body, key, 200)
        assert hold["entered"].wait(10), "Authorized budget receiver not reached"
        accepted = original_commands(sources, crash)
        runtime.stop(force=True); hold["reportId"] = None; hold["release"].set()
        runtime.start(after_jar)
        assert runtime.call("POST", path, body, "manager", key=key) == lost and runtime.client.records[-1]["replayed"] == "true"
        recovered = reviewed(runtime, crash, "CONFIRMED", timeout=120)
        assert original_commands(sources, crash) == accepted
        operation_id = recovered["details"]["authorizedOperationId"]
        calls = [value for value in sources.finance_calls if value["operation"] in ("budget-command", "budget-query")]
        assert sum(value["operation"] == "budget-command" and value["request"]["data"]["command"]["id"] == operation_id for value in calls) == 1
        assert any(value["operation"] == "budget-query" and value["request"]["data"]["operationId"] == operation_id for value in calls)
        evidence["checks"].append({"name": "accepted-command-crash-and-original-approval-replay", "originalLeaseSeconds": 90, "authorizationWrites": 1, "receiverCommandSends": 1, "originalQueryConfirmed": True})
        risk.stage("CRASH_AND_ORIGINAL_QUERY_VERIFIED")

        hidden_definition = prior.template(runtime, fixture, "expense-report", hidden=True)
        hidden = new_report(runtime, fixture, hidden_definition, modes)
        submit(runtime, fixture, hidden); reviewed(runtime, hidden, "CONFIRMED")
        view(runtime, hidden, user="manager", expected=403); view(runtime, hidden, user="admin", expected=403); view(runtime, hidden, user="bob", expected=404)
        runtime.call("GET", "/expense-reports/" + hidden["id"] + "/budget-review?roundNo=1&actor=alice", expected=400)
        restore_target = new_report(runtime, fixture, definition, modes, original="SOFT", flexible=True)
        submit(runtime, fixture, restore_target); restored_evidence = reviewed(runtime, restore_target, "REVIEW_REQUIRED")
        split.action(runtime, restore_target); original_task = prior.assert_task(runtime, restore_target, definition, "budgetReview", "manager")
        runtime.stop()
        preserved = split.columns_snapshot(runtime, idp.h2, "before-restore")
        backup = directory / "backup"; backup.mkdir()
        for name in ("data", "attachments"):
            shutil.copytree(runtime.directory / name, backup / name)
        save(backup / "synthetic-peer-state.json", {"commands": sources.commands, "modes": modes, "invoices": sources.invoices, "entity": sources.base.entity})
        save(backup / "manifest.json", {"jarSha256": digest(after_jar), "files": {str(path.relative_to(backup)): digest(path) for path in backup.rglob("*") if path.is_file()}})
        source_database = digest(runtime.directory / "data/agentflow.mv.db")
        restored = risk.Runtime(args.java, directory / "restored", sources, idp)
        restored.settings["agentflow.expenses.budget-review-worker-enabled"] = True
        restored.settings["agentflow.expenses.budget-review-poll-delay-ms"] = 200
        for name in ("data", "attachments"):
            shutil.copytree(backup / name, restored.directory / name)
        restored.process = runtime.process
        assert split.columns_snapshot(restored, idp.h2, "restored-before-start") == preserved
        schema = prior.restore_schema(runtime, restored, idp.h2)
        peer = json.loads((backup / "synthetic-peer-state.json").read_text())
        sources.commands, sources.invoices, sources.base.entity = peer["commands"], peer["invoices"], peer["entity"]
        modes.clear(); modes.update(peer["modes"])
        restored.start(after_jar); restored.client.sessions = runtime.client.sessions; restored.client.identities = copy.deepcopy(runtime.client.identities)
        assert split.task(restored, restore_target, "manager") == original_task and view(restored, restore_target) == restored_evidence
        assert view(restored, flexible) == first_round and view(restored, flexible, 2) == second_round
        for index, invoice in enumerate(fixture["invoices"]):
            assert restored.call("GET", "/invoices/" + invoice + "/content") == ("%PDF-1.7\nsynthetic invoice " + str(index) + "\n%%EOF").encode()
        split.action(restored, restore_target); final = reviewed(restored, restore_target, "CONFIRMED")
        assert final["details"]["decision"]["taskId"] == original_task["taskId"] and len(original_commands(sources, restore_target)) == 2
        assert restored.call("POST", decision["path"], decision["body"], "manager", key=decision["key"]) == decision["result"]
        assert restored.client.records[-1]["replayed"] == "true"
        restored.stop()
        assert digest(runtime.directory / "data/agentflow.mv.db") == source_database
        assert originals == {str(path.relative_to(restored.directory / "attachments")): digest(path) for path in (restored.directory / "attachments").rglob("*") if path.is_file()}
        evidence["checks"].append({"name": "sensitive-read-and-independent-paired-restore", "tables": len(preserved["tables"]), "rows": sum(item["rows"] for item in preserved["tables"].values()),
            "schemaStatements": schema["statements"], "originalDatabaseUnchanged": True, "attachments": len(originals), "originalBudgetTaskApprovedOnce": True})
        assert not sources.errors and not sources.base.errors, (sources.errors, sources.base.errors)
        assert not sources.model_calls and not sources.base.model_calls
        save(directory / "fixture.json", {**fixture, "flexible": flexible, "crash": crash, "restored": restore_target})
        save(directory / "after-http-records.json", [value for value in runtime.client.records if value["boot"] > 1] + restored.client.records)
        evidence.update(status="PASSED", finishedAt=instant(), httpRecords=len(runtime.client.records) + len(restored.client.records),
                        providerCalls=len(sources.finance_calls), distinctBudgetCommands=len(sources.commands), boots=runtime.starts + restored.starts)
        risk.stage("PAIRED_RESTORE_VERIFIED", tables=len(preserved["tables"]))
    except BaseException as error:
        evidence.update(status="FAILED", finishedAt=instant(), failure=repr(error)); raise
    finally:
        hold["reportId"] = None; hold["release"].set()
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
    parser.add_argument("--before", required=True, help="Preserved V118 JAR")
    parser.add_argument("--after", required=True, help="Fixed V119 JAR")
    parser.add_argument("--java", required=True, help="Absolute Java 17 executable")
    parser.add_argument("--output", required=True, help="Fresh directory under /fyoung/tmp")
    run(parser.parse_args())
