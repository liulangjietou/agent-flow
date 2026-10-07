#!/usr/bin/env python3
"""发票占用提示固定包验收：真实提交竞争、只读授权来源、旧包升级和强退恢复。"""

import argparse
import copy
import importlib.util
import json
from pathlib import Path
import re
from uuid import uuid4
import zipfile

spec = importlib.util.spec_from_file_location("occupation_runtime", Path(__file__).with_name("check-precheck-explanation.py"))
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)


class Sources(base.Sources):
    """回环合成查验将不同原件确认成同一票号，绝不连接企业或模型服务。"""

    def __init__(self, directory):
        super().__init__(directory)
        self.budget = "SUCCESS"

    def finance(self, operation, request):
        if operation != "invoice-verification":
            return super().finance(operation, request)
        data = request["data"]
        value = {"key": {"type": "DIGITAL", "code": None, "number": "12345678901234567890"}, "legalEntityId": self.entity,
                 "gross": {"value": "100.00", "currency": "CNY"}, "tax": {"value": "0.00", "currency": "CNY"},
                 "issueDate": "2026-10-07", "originalDigest": data["originalDigest"], "reference": "synthetic-duplicate-invoice",
                 "verifiedAt": base.instant(-1), "validUntil": base.instant(self.valid_seconds)}
        return {"contractVersion": 1, "tenantId": "demo", "requestId": request["requestId"], "outcome": "SUCCESS", "data": value}, 200


def verify(runtime, invoice, entity):
    """实际查验任务完成后读取原票面，不从文件名或本地脚本直接生成查验状态。"""
    path = "/invoices/" + invoice
    options = runtime.call("GET", path + "/verification-options")
    job = runtime.call("POST", path + "/verifications", {"expectedInvoiceVersion": options["invoiceVersion"],
        "legalEntityId": entity, "targetDigest": options["targetDigest"]}, expected=202)
    outcome = base.wait_for(lambda: runtime.call("GET", path + "/verifications/" + job["id"]),
                            lambda value: value["status"] not in ("QUEUED", "RUNNING"))
    assert outcome["status"] == "SUCCEEDED", outcome
    return runtime.call("GET", path)


def submission(report, check):
    return {"applicationVersion": report["applicationVersion"], "financialVersion": report["financialVersion"], "precheckId": check["job"]["id"]}


def run(java, legacy, fixed, directory, result):
    sources = Sources(directory)
    runtime = base.Runtime(java, directory, sources)
    runtime.settings.update({"agentflow.invoices.verification-worker-enabled": True, "agentflow.invoices.verification-poll-delay-ms": 200})
    with zipfile.ZipFile(fixed) as archive:
        name = next(name for name in archive.namelist() if re.fullmatch(r"BOOT-INF/lib/h2-[^/]+\.jar", name))
        h2 = directory / "h2.jar"; h2.write_bytes(archive.read(name))
    try:
        runtime.start(legacy)
        template = next(item for item in runtime.call("GET", "/process-templates", user="admin") if item["key"] == "expense-report")
        fixture = base.setup(runtime, sources, template["templateVersion"])
        verify(runtime, fixture["invoiceId"], sources.entity)
        report = fixture["report"]; path = "/expense-reports/" + report["id"]
        content = copy.deepcopy(report["content"]); content["lines"][0]["invoiceIds"] = [fixture["invoiceId"]]; content["lines"][0]["lineNo"] = 7
        fixture["report"] = runtime.call("POST", path + "/revise", {"applicationVersion": report["applicationVersion"],
            "financialVersion": report["financialVersion"], "content": content})
        report = fixture["report"]
        original = b"%PDF-1.7\nsecond original of the same synthetic invoice\n%%EOF"
        duplicate = runtime.call("POST", "/invoices", {"filename": "同票号另一原件.pdf", "size": len(original),
            "sha256": base.hashlib.sha256(original).hexdigest(), "format": "PDF"}, expected=201)
        invoice_path = "/invoices/" + duplicate["id"]
        runtime.call("PUT", invoice_path + "/content", raw=original)
        verify(runtime, duplicate["id"], sources.entity)
        application = runtime.call("GET", "/applications/" + report["applicationId"])
        content["lines"][0]["invoiceIds"] = [duplicate["id"]]
        other = runtime.call("POST", "/expense-reports", {"businessNo": "OCCUPATION-DUPLICATE", "processKey": application["processKey"],
            "definitionVersion": application["definitionVersion"], "content": content}, expected=201)
        duplicate_fixture = {**fixture, "report": other}; duplicate_path = "/expense-reports/" + other["id"]
        check = base.precheck(runtime, duplicate_fixture); assert check["usable"]
        winner_check = base.precheck(runtime, fixture); assert winner_check["usable"]
        receipt = runtime.call("POST", path + "/submit", submission(report, winner_check))
        request, key = submission(other, check), str(uuid4())
        old_failure = runtime.call("POST", duplicate_path + "/submit", request, expected=422, key=key)
        assert old_failure["code"] == "RESOURCES_CHANGED" and not old_failure.get("details")
        winner_before = runtime.call("GET", path); loser_before = runtime.call("GET", duplicate_path)
        wallet_before = runtime.call("GET", invoice_path); assert wallet_before["occupation"] == "AVAILABLE" and "activeClaim" not in wallet_before
        original_application = runtime.call("GET", "/applications/" + fixture["applicationId"])
        runtime.stop(); before = base.snapshot(runtime, h2, "before-upgrade")
        runtime.start(fixed, login=False); runtime.stop(); after = base.snapshot(runtime, h2, "after-upgrade")
        assert before == after, "Read-model-only upgrade changed persisted facts"
        result["upgrade"] = {"preservedTables": len(before), "preservedRows": sum(value["rows"] for value in before.values()), "newMigrations": 0}
        runtime.start(fixed)
        current = runtime.call("GET", invoice_path)
        assert {name: value for name, value in current.items() if name != "activeClaim"} == wallet_before
        reference = current["activeClaim"]["expense"]
        assert reference == {"reportId": report["id"], "applicationId": report["applicationId"], "businessNo": report["businessNo"], "roundNo": 1, "lineNo": 7}
        assert current["activeClaim"]["status"] == "OCCUPIED"
        failure = runtime.call("POST", duplicate_path + "/submit", request, expected=409, key=key)
        assert failure["code"] == old_failure["code"] and runtime.records[-1]["cacheControl"] == "no-store"
        conflicts = failure["details"]["invoiceConflicts"]
        assert conflicts == [{"lineNo": 7, "invoiceId": duplicate["id"], "occupation": current["activeClaim"]}]
        checked = runtime.call("GET", duplicate_path + "/prechecks/" + check["job"]["id"])
        assert checked["invoiceConflicts"] == conflicts and checked["findings"] == check["findings"]
        assert runtime.call("GET", path) == winner_before and runtime.call("GET", duplicate_path) == loser_before
        for actor in ("bob", "admin"):
            runtime.call("GET", invoice_path, user=actor, expected=404)
            runtime.call("GET", duplicate_path + "/prechecks/" + check["job"]["id"], user=actor, expected=404)
        runtime.stop(force=True); runtime.start(fixed)
        assert runtime.call("GET", invoice_path) == current
        recovered = runtime.call("POST", duplicate_path + "/submit", request, expected=409, key=key)
        assert recovered["details"] == failure["details"]
        assert runtime.call("GET", path) == winner_before and runtime.call("GET", duplicate_path) == loser_before
        assert runtime.call("GET", "/applications/" + fixture["applicationId"]) == original_application
        for action in ("withdraw", "cancel"):
            latest = runtime.call("GET", path)
            runtime.call("POST", path + "/" + action, {"applicationVersion": latest["applicationVersion"],
                "financialVersion": latest["financialVersion"], "comment": "合成占用释放验收"})
        assert not runtime.call("GET", invoice_path).get("activeClaim")
        released = runtime.call("GET", duplicate_path + "/prechecks/" + check["job"]["id"])
        assert not released["invoiceConflicts"] and released["findings"] == check["findings"]
        assert base.hashlib.sha256(runtime.call("GET", invoice_path + "/content")).hexdigest() == base.hashlib.sha256(original).hexdigest()
        assert not sources.errors and not sources.model_calls
        assert {call["operation"] for call in sources.calls} <= {"catalog", "employee-account", "exchange-rate", "expense-policy", "budget-precheck", "invoice-verification"}
        base.save(directory / "verified-conflict.json", failure)
        result.update(status="PASS", runtimeBoots=runtime.starts, httpRequests=len(runtime.records), oldFieldsUnchanged=True,
                      immutableFindingsUnchanged=True, failedSubmissionDoesNotChangeReports=True, identityIsolation=True,
                      currentClaimRemovedAfterRelease=True, forceRestartPreservedFacts=True, sameFailedRequestRefreshedAuthorization=True,
                      modelRequests=0, browserRun=False, postgresRun=False, realEnterpriseServices=False)
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
