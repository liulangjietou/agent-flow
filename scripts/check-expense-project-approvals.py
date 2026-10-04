#!/usr/bin/env python3
"""固定 V119 升级至 V120：项目全员会签、原键恢复与独立配套还原。"""

import argparse
import copy
import importlib.util
import json
from pathlib import Path
import shutil
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("prior_runtime", ROOT / "scripts/check-expense-prior-controls.py")
prior = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prior)
split, risk = prior.split, prior.risk
save, digest, instant, wait_for = risk.save, risk.digest, risk.instant, risk.wait_for
BASELINE_SHA256 = "e5978f9fe03dd8ff37416e42f9baf61a9103853703920860038adc6d3f21629e"
NEW_TABLES = {"EXPENSE_PROJECT_APPROVAL"}


def sources_for(directory):
    """通过真实财务协议返回项目负责人，旧包阶段保持旧目录形状。"""
    sources = risk.Sources(directory)
    sources.server.RequestHandlerClass.protocol_version = "HTTP/1.1"
    sources.base.server.RequestHandlerClass.protocol_version = "HTTP/1.1"
    original = sources.finance
    owners = {}

    def finance(operation, request):
        value, status = original(operation, request)
        if operation == "catalog" and owners:
            value["data"]["sourceVersion"] = "synthetic-project-v1"
            value["data"]["projects"] = [{"legalEntityId": sources.base.entity, "code": code,
                "name": "合成项目 " + code, "ownerSubject": owner} for code, owner in owners.items()]
        return value, status

    sources.finance = finance
    return sources, owners


def template(runtime, fixture, hidden=False):
    """复制新包的真实 v7 模板，仅绑定普通组织角色，保留项目专用规则。"""
    item = next(value for value in runtime.call("GET", "/process-templates", user="admin") if value["key"] == "expense-report")
    assert item["templateVersion"] == 7
    draft = runtime.call("POST", "/process-templates/expense-report/copy", {"key": "f14-" + uuid4().hex,
        "name": "合成项目会签", "templateVersion": item["templateVersion"]}, "admin")
    graph, schema = copy.deepcopy(draft["graph"]), copy.deepcopy(draft["formSchema"])
    for node in graph["nodes"]:
        if node["type"] == "USER_TASK" and node["id"] != "projectReview":
            actor = "finance" if node["id"] in ("receipt", "finance", "recheck") else "manager"
            node["properties"]["assigneeRule"] = "role:ORG_PERSON_" + fixture["people"][actor]
    if hidden:
        next(field for field in schema["fields"] if field["key"] == "expenseDetails")["nodeAccess"]["supervisor"] = "HIDDEN"
    updated = runtime.call("PUT", "/process-definitions/" + draft["id"], {"name": draft["name"], "graph": graph,
        "formSchema": schema, "notificationTexts": draft["notificationTexts"], "expectedRevision": draft["revision"]}, "admin")
    return runtime.call("POST", "/process-definitions/" + draft["id"] + "/publish?expectedRevision=" + str(updated["revision"]),
                        {"changeNote": "固定原项目责任验收"}, "admin")


def person(runtime, subject, eligible):
    value = next(row for row in runtime.call("GET", "/organization/people?limit=100", user="admin")["items"] if row["subject"] == subject)
    return runtime.call("PUT", "/organization/people/" + value["id"], {"displayName": value["displayName"], "active": True,
        "approvalEligible": eligible, "expectedRevision": value["revision"]}, "admin")


def supervisor(runtime, fixture, subject):
    value = next(row for row in runtime.call("GET", "/organization/appointments?personId=" + fixture["people"]["alice"], user="admin")["items"]
                 if row["id"] == fixture["appointments"]["alice"])
    return runtime.call("PUT", "/organization/appointments/" + value["id"] + "/supervisor",
        {"appointmentId": fixture["appointments"][subject], "expectedRevision": value["revision"]}, "admin")


def draft(runtime, fixture, definition, allocated=True):
    content = {"legalEntityId": fixture["entityId"], "type": "DAILY", "title": "合成项目分摊", "advanceOffsets": [], "lines": [
        {"lineNo": 1, "categoryCode": "OFFICE", "incurredOn": instant()[:10], "cityCode": "SH", "quantity": 1, "unit": "ITEM",
         "claimedGross": prior.money("100"), "claimedTax": prior.money("0"), "invoiceIds": [], "description": "合成项目费用",
         "allocations": [{"costCenter": "IT", "projectCode": "A", "amount": prior.money("40")},
                         {"costCenter": "IT", "projectCode": "B", "amount": prior.money("60")}] if allocated
                        else [{"costCenter": "IT", "amount": prior.money("100")}]}]}
    return runtime.call("POST", "/expense-reports", {"businessNo": "PROJECT-" + uuid4().hex, "processKey": definition["key"],
                        "definitionVersion": definition["version"], "content": content}, expected=201)


def view(runtime, report, round_no=1, actor="alice", expected=200):
    return runtime.call("GET", "/expense-reports/" + report["id"] + "/project-approval?roundNo=" + str(round_no), user=actor, expected=expected)


def submit(runtime, fixture, report):
    body, key = split.prepare(runtime, fixture, report), str(uuid4())
    path = "/expense-reports/" + report["id"] + "/submit"
    result = runtime.call("POST", path, body, key=key)
    split.budget(runtime, report)
    return {"path": path, "body": body, "key": key, "result": result}


def members(runtime, report, actor):
    task = split.task(runtime, report, actor)
    value = runtime.call("GET", "/tasks/" + task["taskId"] + "/countersign-members", user=actor)
    assert value["issue"] == "EXPENSE_PROJECT_MEMBERS_FIXED" and not value["canChange"] and not value["canAdd"]
    return task, value


def retained_budget(runtime, report, status):
    """撤回和退回保留原冻结；重提才重新绑定，不能把停止轮次误判成预算释放。"""
    assert split.detail(runtime, report)["applicationStatus"] == status
    budget = runtime.call("GET", "/expense-reports/" + report["id"] + "/workflow")["budget"]
    assert budget["ledgerStatus"] == "FROZEN" and budget["operationStatus"] == "APPLIED" and not budget["confirmedCurrent"]


def run(args):
    directory = Path(args.output)
    assert str(directory).startswith("/fyoung/tmp/") and not directory.exists(), "Use a fresh owned temporary directory"
    directory.mkdir(parents=True)
    before_jar, after_jar = Path(args.before).resolve(), Path(args.after).resolve()
    assert digest(before_jar) == BASELINE_SHA256, "V119 baseline differs from preserved package"
    sources, owners = sources_for(directory)
    risk.ROLES["bob"].add("APPROVER")
    idp, runtime, restored = None, None, None
    evidence = {"status": "RUNNING", "startedAt": instant(), "beforeSha256": digest(before_jar), "afterSha256": digest(after_jar),
                "database": "H2", "browserVerified": False, "postgresqlVerified": False, "checks": []}
    save(directory / "evidence.json", evidence)
    for name in ("scripts/check-expense-project-approvals.py", "scripts/check-expense-prior-controls.py", "scripts/check-expense-split-routing.py",
                 "scripts/check-expense-risk.py", "scripts/check-precheck-explanation.py", "scripts/payment-due-dates-support/CompareH2Schema.java",
                 "scripts/fixtures/ExpenseRiskOidcFixture.java", "agentflow-server/src/test/java/io/agentflow/auth/OidcTestProvider.java"):
        target = directory / "harness-source" / name; target.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(ROOT / name, target)
    try:
        idp = risk.IdentityProvider(args.java, directory, after_jar, bob_approver=True)
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
        evidence["checks"].append({"name": "nonempty-v119-upgrade", "oldTables": len(before["tables"]), "oldRows": sum(item["rows"] for item in before["tables"].values()), "changedOldTables": changed, "addedTables": sorted(NEW_TABLES)})
        risk.stage("UPGRADE_VERIFIED", oldTables=len(before["tables"]))
        runtime.start(after_jar)
        assert split.detail(runtime, legacy) == old_report and split.task(runtime, legacy, "manager") == old_task
        assert view(runtime, legacy)["status"] == "NOT_RECORDED"
        assert runtime.call("POST", original_submit["path"].removeprefix("/api/v1"), original_submit["request"], key=original_submit["key"]) == original_submit["response"]
        assert runtime.client.records[-1]["replayed"] == "true"

        # 合成组织变更仍走公开版本化入口，原已提交轮次不重新解析。
        person(runtime, "alice", True)
        bob = runtime.call("POST", "/organization/people", {"subject": "bob", "displayName": "合成项目负责人", "active": True, "approvalEligible": True}, "admin", 201)
        fixture["people"]["bob"] = bob["id"]
        manager_appointment = next(row for row in runtime.call("GET", "/organization/appointments?personId=" + fixture["people"]["manager"], user="admin")["items"])
        fixture["appointments"]["bob"] = runtime.call("POST", "/organization/appointments", {"personId": bob["id"],
            "departmentId": manager_appointment["departmentId"], "positionId": manager_appointment["positionId"], "active": True}, "admin", 201)["id"]
        supervisor(runtime, fixture, "manager")
        owners.update(A="manager", B="bob")
        definition = template(runtime, fixture)
        missing = draft(runtime, fixture, definition)
        owners["A"] = None
        checked = risk.common.precheck(runtime, {"report": missing, "appointmentId": fixture["appointments"]["alice"]})
        assert checked["job"]["status"] == "BLOCKED" and any(item["code"] == "EXPENSE_PROJECT_OWNER_UNAVAILABLE" for item in checked["findings"])
        owners["A"] = "manager"
        incompatible = draft(runtime, fixture, {"key": "risk-runtime", "version": 1})
        body = split.prepare(runtime, fixture, incompatible)
        failure = runtime.call("POST", "/expense-reports/" + incompatible["id"] + "/submit", body, expected=422)
        assert failure["code"] == "EXPENSE_PROJECT_APPROVAL_REQUIRED"
        assert split.detail(runtime, incompatible)["applicationStatus"] == "DRAFT"
        empty = draft(runtime, fixture, definition, allocated=False); submit(runtime, fixture, empty)
        assert view(runtime, empty)["status"] == "NO_PROJECT"
        split.action(runtime, empty); prior.assert_task(runtime, empty, definition, "receipt", "finance")
        evidence["checks"].append({"name": "legacy-missing-owner-and-no-project", "oldSubmitReplay": True, "missingOwnerBlocked": True, "unsafeDefinitionBlocked": True, "noProjectNodeSkipped": True})

        owners.update(A="alice", B="bob")
        active = draft(runtime, fixture, definition); submitted = submit(runtime, fixture, active)
        original = view(runtime, active)
        assert original["details"]["responsibility"]["originalSubjects"] == ["alice", "bob"]
        assert original["details"]["responsibility"]["candidateSubjects"] == ["bob", "manager"]
        owners.update(A="finance", B="finance"); supervisor(runtime, fixture, "bob")
        split.action(runtime, active)
        manager_task, fixed = members(runtime, active, "manager"); bob_task, _ = members(runtime, active, "bob")
        assert fixed["total"] == 2 and fixed["completed"] == 0 and view(runtime, active) == original
        for body in [{"action": "ADD", "targetUser": "finance"}, {"action": "REMOVE", "targetTaskId": bob_task["taskId"]}]:
            denied = runtime.call("POST", "/tasks/" + manager_task["taskId"] + "/countersign-changes",
                {**body, "reason": "合成固定项目责任检查", "expectedVersion": manager_task["version"]}, "manager", expected=409)
            assert denied["code"] == "EXPENSE_PROJECT_MEMBERS_FIXED"
        person(runtime, "bob", False)
        runtime.call("POST", "/tasks/" + bob_task["taskId"] + "/actions", {"action": "APPROVE", "expectedVersion": bob_task["version"], "comment": "合成失效负责人"}, "bob", expected=403)
        assert split.detail(runtime, active)["applicationVersion"] == manager_task["version"]
        person(runtime, "bob", True)
        crash_path, crash_key = "/tasks/" + manager_task["taskId"] + "/actions", str(uuid4())
        crash_body = {"action": "APPROVE", "expectedVersion": manager_task["version"], "comment": "合成丢失第一位项目批准回执"}
        lost = runtime.client.lose_response(crash_path, crash_body, crash_key, 200)
        pending_bob, half = members(runtime, active, "bob")
        assert half["completed"] == 1 and half["total"] == 2 and half["completedUsers"] == ["manager"]
        commands = copy.deepcopy(sources.commands)
        runtime.stop(force=True); runtime.start(after_jar)
        assert runtime.call("POST", crash_path, crash_body, "manager", key=crash_key) == lost and runtime.client.records[-1]["replayed"] == "true"
        assert split.task(runtime, active, "bob") == pending_bob and members(runtime, active, "bob")[1] == half
        assert view(runtime, active) == original and sources.commands == commands
        assert runtime.call("POST", submitted["path"], submitted["body"], key=submitted["key"]) == submitted["result"]
        split.action(runtime, active, "bob"); prior.assert_task(runtime, active, definition, "receipt", "finance")
        split.action(runtime, active, "finance")
        financial_task = prior.assert_task(runtime, active, definition, "finance", "finance")
        current = split.detail(runtime, active)
        runtime.call("POST", "/expense-reports/" + active["id"] + "/tasks/" + financial_task["taskId"] + "/reduce",
            {**{key: current[key] for key in ("applicationVersion", "financialVersion")}, "lines": [{"lineNo": 1, "approvedGross": "0", "approvedTax": "0"}],
             "reasonCode": "INELIGIBLE_COST", "comment": "合成项目费用全额核减"}, "finance")
        split.budget(runtime, active); split.action(runtime, active, "finance")
        assert split.detail(runtime, active)["applicationStatus"] == "APPROVED" and view(runtime, active) == original
        evidence["checks"].append({"name": "all-responsibilities-frozen-crash-replay-and-zero-reduction", "projects": 2, "distinctApprovers": 2,
            "originalSupervisorPreserved": True, "sourceChangeIgnored": True, "eligibilityRevocationBlocked": True,
            "completedBeforeCrash": 1, "originalKeysReplayed": True, "secondApprovalStillRequired": True, "reductionKeptOriginalEvidence": True})
        risk.stage("PROJECT_CRASH_AND_REDUCTION_VERIFIED")

        owners.update(A="bob", B="bob")
        repeated = draft(runtime, fixture, definition); submit(runtime, fixture, repeated); split.action(runtime, repeated)
        first = view(runtime, repeated); assert len(first["details"]["source"]["projects"]) == 2
        assert members(runtime, repeated, "bob")[1]["total"] == 1
        current = split.detail(runtime, repeated)
        runtime.call("POST", "/expense-reports/" + repeated["id"] + "/withdraw", {**{key: current[key] for key in ("applicationVersion", "financialVersion")}, "comment": "合成重选项目轮次"})
        retained_budget(runtime, repeated, "WITHDRAWN"); owners.update(A="manager", B="bob")
        submit(runtime, fixture, repeated); split.action(runtime, repeated)
        second = view(runtime, repeated, 2); split.action(runtime, repeated, "bob", choice="RETURN")
        retained_budget(runtime, repeated, "RETURNED"); owners.update(A="alice", B="bob"); supervisor(runtime, fixture, "manager")
        submit(runtime, fixture, repeated); split.action(runtime, repeated)
        third = view(runtime, repeated, 3)
        assert view(runtime, repeated) == first and view(runtime, repeated, 2) == second
        assert third["details"]["responsibility"]["candidateSubjects"] == ["bob", "manager"]
        evidence["checks"].append({"name": "same-owner-and-three-rounds", "projectsPerRound": 2, "sameOwnerVotes": 1, "rounds": 3, "originalSourcesPreserved": True, "stoppedBudgetRetained": True})

        hidden_definition = template(runtime, fixture, hidden=True)
        hidden = draft(runtime, fixture, hidden_definition); submit(runtime, fixture, hidden)
        view(runtime, hidden, actor="manager", expected=403); view(runtime, hidden, actor="admin", expected=403); view(runtime, hidden, actor="bob", expected=404)
        runtime.call("GET", "/expense-reports/" + hidden["id"] + "/project-approval?roundNo=1&owner=alice", expected=400)
        # 备份保留第三轮只完成一人的原多实例；恢复不能重新生成名单或第一份批准。
        split.action(runtime, repeated, "manager"); restore_task, restore_members = members(runtime, repeated, "bob")
        assert restore_members["completed"] == 1 and restore_members["total"] == 2
        runtime.stop()
        preserved = split.columns_snapshot(runtime, idp.h2, "before-restore")
        backup = directory / "backup"; backup.mkdir()
        for name in ("data", "attachments"):
            shutil.copytree(runtime.directory / name, backup / name)
        save(backup / "synthetic-peer-state.json", {"commands": sources.commands, "owners": owners, "invoices": sources.invoices, "entity": sources.base.entity})
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
        owners.clear(); owners.update(peer["owners"])
        restored.start(after_jar); restored.client.sessions = runtime.client.sessions; restored.client.identities = copy.deepcopy(runtime.client.identities)
        split.detail(restored, repeated); split.detail(restored, legacy); split.detail(restored, active)
        assert split.task(restored, repeated, "bob") == restore_task and members(restored, repeated, "bob")[1] == restore_members
        assert view(restored, repeated) == first and view(restored, repeated, 2) == second and view(restored, repeated, 3) == third
        assert view(restored, legacy)["status"] == "NOT_RECORDED" and view(restored, active) == original
        split.detail(restored, empty); assert view(restored, empty)["status"] == "NO_PROJECT"
        for index, invoice in enumerate(fixture["invoices"]):
            assert restored.call("GET", "/invoices/" + invoice + "/content") == ("%PDF-1.7\nsynthetic invoice " + str(index) + "\n%%EOF").encode()
        split.action(restored, repeated, "bob"); prior.assert_task(restored, repeated, definition, "receipt", "finance")
        assert restored.call("POST", crash_path, crash_body, "manager", key=crash_key) == lost and restored.client.records[-1]["replayed"] == "true"
        restored.stop()
        assert digest(runtime.directory / "data/agentflow.mv.db") == source_database
        assert originals == {str(path.relative_to(restored.directory / "attachments")): digest(path) for path in (restored.directory / "attachments").rglob("*") if path.is_file()}
        evidence["checks"].append({"name": "sensitive-read-and-independent-paired-restore", "tables": len(preserved["tables"]), "rows": sum(item["rows"] for item in preserved["tables"].values()),
            "schemaStatements": schema["statements"], "originalDatabaseUnchanged": True, "attachments": len(originals), "pendingNativeTaskPreserved": True, "originalApprovedVotePreserved": True})
        assert not sources.errors and not sources.base.errors, (sources.errors, sources.base.errors)
        assert not sources.model_calls and not sources.base.model_calls
        save(directory / "fixture.json", {**fixture, "active": active, "repeated": repeated, "empty": empty, "definition": definition})
        save(directory / "after-http-records.json", [value for value in runtime.client.records if value["boot"] > 1] + restored.client.records)
        evidence.update(status="PASSED", finishedAt=instant(), httpRecords=len(runtime.client.records) + len(restored.client.records),
                        providerCalls=len(sources.finance_calls), distinctBudgetCommands=len(sources.commands), boots=runtime.starts + restored.starts)
        risk.stage("PROJECT_RESTORE_VERIFIED", httpRecords=evidence["httpRecords"])
    except BaseException as failure:
        evidence.update(status="FAILED", error=repr(failure), finishedAt=instant())
        raise
    finally:
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
    parser.add_argument("--before", required=True, help="Preserved V119 JAR")
    parser.add_argument("--after", required=True, help="Fixed V120 JAR")
    parser.add_argument("--java", required=True, help="Absolute Java 17 executable")
    parser.add_argument("--output", required=True, help="Fresh directory under /fyoung/tmp")
    run(parser.parse_args())
