#!/usr/bin/env python3
"""已批准报销撤销固定包验收：旧批准原值、真实占用释放、升级和强退后原请求恢复。"""

import argparse
import copy
import importlib.util
import json
from pathlib import Path
import re
from uuid import uuid4
import zipfile

spec = importlib.util.spec_from_file_location("revocation_finance", Path(__file__).with_name("check-expense-reduction-reasons.py"))
finance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(finance)
base = finance.base


class Sources(finance.Sources):
    """只处理回环合成发票与预算；任何凭证外发都应被本期撤销阻止。"""

    def finance(self, operation, request):
        if operation != "invoice-verification":
            return super().finance(operation, request)
        value = {"key": {"type": "DIGITAL", "code": None, "number": "12345678901234567890"}, "legalEntityId": self.entity,
                 "gross": {"value": "100.00", "currency": "CNY"}, "tax": {"value": "0.00", "currency": "CNY"},
                 "issueDate": "2026-10-07", "originalDigest": request["data"]["originalDigest"], "reference": "synthetic-revocation-invoice",
                 "verifiedAt": base.instant(-1), "validUntil": base.instant(self.valid_seconds)}
        return {"contractVersion": 1, "tenantId": "demo", "requestId": request["requestId"], "outcome": "SUCCESS", "data": value}, 200


def approve(runtime, fixture):
    """仅通过原模板的真实业务、收单和财务任务批准，不改数据库状态。"""
    state = finance.enter_finance(runtime, fixture)
    path = "/expense-reports/" + fixture["report"]["id"]
    for _ in range(6):
        runtime.call("POST", "/tasks/" + state["task"]["taskId"] + "/actions", {
            "action": "APPROVE", "expectedVersion": state["applicationVersion"], "comment": "撤销验收前的合成批准"}, state["fixtureActor"])
        current = runtime.call("GET", path)
        if current["applicationStatus"] == "APPROVED":
            return current
        state = finance.confirmed(runtime, path, current["applicationId"])
    raise AssertionError("Expense approval did not complete")


def run(java, legacy, fixed, directory, result):
    sources = Sources(directory)
    runtime = base.Runtime(java, directory, sources)
    original_call = runtime.call
    # 原始 HTTP 响应与夹具工作对象分开，财务任务的 fixtureActor 标记不能污染证据。
    def detached_call(*args, **kwargs):
        return copy.deepcopy(original_call(*args, **kwargs))
    runtime.call = detached_call
    runtime.settings.update({"agentflow.budgets.worker-enabled": True, "agentflow.budgets.poll-delay-ms": 200,
                             "agentflow.invoices.verification-worker-enabled": True, "agentflow.invoices.verification-poll-delay-ms": 200})
    with zipfile.ZipFile(fixed) as archive:
        entry = next(name for name in archive.namelist() if re.fullmatch(r"BOOT-INF/lib/h2-[^/]+\.jar", name))
        h2 = directory / "h2.jar"; h2.write_bytes(archive.read(entry))
    try:
        runtime.start(legacy)
        template = next(item for item in runtime.call("GET", "/process-templates", user="admin") if item["key"] == "expense-report")
        fixture = finance.setup(runtime, sources, template["templateVersion"])
        invoice_path = "/invoices/" + fixture["invoiceId"]
        options = runtime.call("GET", invoice_path + "/verification-options")
        job = runtime.call("POST", invoice_path + "/verifications", {"expectedInvoiceVersion": options["invoiceVersion"],
            "legalEntityId": sources.entity, "targetDigest": options["targetDigest"]}, expected=202)
        verified = base.wait_for(lambda: runtime.call("GET", invoice_path + "/verifications/" + job["id"]),
                                 lambda value: value["status"] not in ("QUEUED", "RUNNING"))
        assert verified["status"] == "SUCCEEDED", verified
        report = fixture["report"]; path = "/expense-reports/" + report["id"]
        content = copy.deepcopy(report["content"]); content["lines"][0]["invoiceIds"] = [fixture["invoiceId"]]
        fixture["report"] = runtime.call("POST", path + "/revise", {"applicationVersion": report["applicationVersion"],
            "financialVersion": report["financialVersion"], "content": content})
        approved = approve(runtime, fixture)
        application_path = "/applications/" + report["applicationId"]
        rounds = runtime.call("GET", application_path + "/rounds")
        unrelated = runtime.call("GET", "/applications/" + fixture["applicationId"])
        invoice_before = runtime.call("GET", invoice_path); assert invoice_before["occupation"] == "OCCUPIED"
        request = {"applicationVersion": approved["applicationVersion"], "financialVersion": approved["financialVersion"], "comment": "经财务确认撤销未外发报销"}
        key = str(uuid4())
        runtime.call("POST", path + "/revoke", request, "finance", 404, key=key)
        runtime.settings["agentflow.budgets.worker-enabled"] = False
        runtime.stop(); before = base.snapshot(runtime, h2, "before-upgrade")
        runtime.start(fixed, login=False); runtime.stop(); after = base.snapshot(runtime, h2, "after-upgrade")
        assert before == after, "Upgrade changed old approval, financial or resource facts"
        result["upgrade"] = {"preservedTables": len(before), "preservedRows": sum(value["rows"] for value in before.values()), "newMigrations": 0}
        runtime.start(fixed)
        assert runtime.call("GET", path) == approved
        assert runtime.call("GET", application_path + "/rounds") == rounds
        capability = runtime.call("GET", path + "/workflow", user="finance")["revocation"]
        assert capability["allowed"] is True and capability.get("unavailable") is None
        assert not runtime.call("GET", path + "/workflow").get("revocation")
        for actor in ("alice", "admin"):
            runtime.call("POST", path + "/revoke", request, actor, 403)
        receipt = runtime.call("POST", path + "/revoke", request, "finance", key=key)
        assert receipt["status"] == "REVOKED" and receipt["applicationVersion"] == approved["applicationVersion"] + 1
        assert receipt["financialVersion"] == approved["financialVersion"]
        assert runtime.call("GET", invoice_path)["occupation"] == "AVAILABLE"
        waiting = runtime.call("GET", path + "/workflow", user="finance")
        assert waiting["budget"]["ledgerStatus"] == "FROZEN" and waiting["budget"]["operationStatus"] == "QUEUED"
        runtime.stop(force=True)
        runtime.settings.update({"agentflow.budgets.worker-enabled": True, "agentflow.vouchers.preparation-worker-enabled": True,
                                 "agentflow.vouchers.worker-enabled": True, "agentflow.vouchers.preparation-poll-delay-ms": 200})
        runtime.start(fixed)
        assert runtime.call("POST", path + "/revoke", request, "finance", key=key) == receipt
        assert runtime.records[-1]["replayed"] == "true"
        finished = base.wait_for(lambda: runtime.call("GET", path + "/workflow", user="finance"),
                                 lambda value: value["budget"]["ledgerStatus"] == "RELEASED")
        voucher = base.wait_for(lambda: runtime.call("GET", application_path + "/vouchers?roundNo=1", user="finance"),
                                lambda value: value.get("preparation", {}).get("status") == "VOIDED")
        assert voucher.get("operation") is None and not voucher["actions"]["prepare"] and not voucher["actions"]["resendOriginal"]
        current = runtime.call("GET", path)
        assert current == {**approved, "applicationStatus": "REVOKED", "applicationVersion": receipt["applicationVersion"]}
        assert runtime.call("GET", application_path + "/rounds") == rounds
        assert len(runtime.call("GET", application_path + "/audit?action=REVOKE", user="finance")["items"]) == 1
        assert runtime.call("GET", "/applications/" + fixture["applicationId"]) == unrelated
        assert base.hashlib.sha256(runtime.call("GET", invoice_path + "/content")).hexdigest() == fixture["originalSha256"]
        operations = list(sources.commands.values())
        assert sorted(value["command"]["action"] for value in operations) == ["FREEZE", "RELEASE"]
        assert all(value["received"] == 1 for value in operations)
        assert not sources.errors and not sources.model_calls
        assert {call["operation"] for call in sources.calls} <= {"catalog", "employee-account", "exchange-rate", "expense-policy", "budget-precheck", "budget-command", "budget-query", "invoice-verification"}
        base.save(directory / "verified-revocation.json", {"receipt": receipt, "workflow": finished, "report": current, "rounds": rounds})
        base.save(directory / "synthetic-budget-commands.json", sources.commands)
        result.update(status="PASS", runtimeBoots=runtime.starts, httpRequests=len(runtime.records), originalApprovalPreserved=True,
                      idempotentRevocation=True, invoiceReleased=True, externalBudgetReleaseConfirmed=True, financialSnapshotPreserved=True,
                      originalFilePreserved=True, voucherWrites=0)
    finally:
        runtime.stop(); sources.close()
        base.save(directory / "requests.json", runtime.records)


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
              "checkerSha256": base.digest(Path(__file__)), "sharedCheckerSha256": base.digest(Path(base.__file__)),
              "financeCheckerSha256": base.digest(Path(finance.__file__))}
    try:
        run(args.java, args.legacy_jar.resolve(), args.fixed_jar.resolve(), directory, result)
    except Exception as error:
        result.update(status="FAILED", failureType=type(error).__name__, failure=str(error)); raise
    finally:
        base.save(directory / "evidence.json", result)
    print(json.dumps(result, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
