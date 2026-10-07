#!/usr/bin/env python3
"""费用逐行反馈固定包验收：保留旧结果，核对新行号和补正后的只读预检。"""

import argparse
import copy
import importlib.util
import json
from pathlib import Path
import re
import zipfile


spec = importlib.util.spec_from_file_location("precheck_runtime", Path(__file__).with_name("check-precheck-explanation.py"))
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)


class Sources(base.Sources):
    """只有原第七行制度受阻，预算接口继续使用显式合成事实。"""

    decision = "DENIED"

    def finance(self, operation, request):
        value, status = super().finance(operation, request)
        if operation == "expense-policy" and request["data"]["line"]["lineNo"] == 7:
            value["data"]["policy"]["decision"] = self.decision
            value["data"]["policy"]["allowedGross"] = {"value": "0.00", "currency": "CNY"}
        return value, status


def revise(runtime, detail, content):
    """通过既有双版本写入口补正，不改库或绕过保存校验。"""
    return runtime.call("POST", "/expense-reports/" + detail["id"] + "/revise", {
        "applicationVersion": detail["applicationVersion"], "financialVersion": detail["financialVersion"], "content": content})


def run(java, legacy, fixed, directory, result):
    sources = Sources(directory)
    runtime = base.Runtime(java, directory, sources)
    with zipfile.ZipFile(fixed) as archive:
        entry = next(name for name in archive.namelist() if re.fullmatch(r"BOOT-INF/lib/h2-[^/]+\.jar", name))
        h2 = directory / "h2.jar"
        h2.write_bytes(archive.read(entry))
    try:
        runtime.start(legacy)
        template = next(item for item in runtime.call("GET", "/process-templates", user="admin") if item["key"] == "expense-report")
        fixture = base.setup(runtime, sources, template["templateVersion"])
        content = copy.deepcopy(fixture["report"]["content"])
        content["lines"][0]["lineNo"] = 3
        content["lines"].append({**copy.deepcopy(content["lines"][0]), "lineNo": 7})
        fixture["report"] = revise(runtime, fixture["report"], content)
        path = "/expense-reports/" + fixture["report"]["id"]
        old_check = base.precheck(runtime, fixture)
        assert old_check["job"]["status"] == "BLOCKED"
        assert old_check["findings"] == [{"stage": "INPUT", "nature": "REJECTED", "code": "EXPENSE_POLICY_DENIED"}], old_check
        original_application = runtime.call("GET", "/applications/" + fixture["applicationId"])
        original_report_application = runtime.call("GET", "/applications/" + fixture["report"]["applicationId"])
        assert not any(call["operation"] == "budget-precheck" for call in sources.calls)
        base.save(directory / "legacy-fixture.json", {**fixture, "precheck": old_check})
        runtime.stop()
        before = base.snapshot(runtime, h2, "before-upgrade")
        runtime.start(fixed, login=False)
        runtime.stop()
        after = base.snapshot(runtime, h2, "after-upgrade")
        assert after == before, "A code-only upgrade must preserve every table, column value and row"
        result["upgrade"] = {"preservedTables": len(before), "preservedRows": sum(value["rows"] for value in before.values()), "newMigrations": 0}
        runtime.start(fixed)
        assert runtime.call("GET", path) == fixture["report"]
        assert runtime.call("GET", path + "/prechecks/" + old_check["job"]["id"]) == old_check
        assert runtime.call("GET", "/applications/" + fixture["applicationId"]) == original_application
        assert base.hashlib.sha256(runtime.call("GET", "/invoices/" + fixture["invoiceId"] + "/content")).hexdigest() == fixture["originalSha256"]
        checks = []
        for decision, code in (("DENIED", "EXPENSE_POLICY_DENIED"), ("REQUIRES_EXCEPTION", "EXPENSE_EXCEPTION_REASON_REQUIRED")):
            sources.decision = decision
            check = base.precheck(runtime, fixture)
            assert check["job"]["status"] == "BLOCKED", check
            assert check["findings"] == [{"stage": "INPUT", "lineNo": 7, "nature": "REJECTED", "code": code}], check
            assert runtime.call("GET", path) == fixture["report"]
            assert runtime.call("GET", "/applications/" + fixture["report"]["applicationId"]) == original_report_application
            checks.append(check)
        assert not any(call["operation"] == "budget-precheck" for call in sources.calls)
        content["lines"][1]["exceptionReason"] = "本地验收明确说明原第七行超标原因"
        corrected = revise(runtime, fixture["report"], content)
        obsolete = runtime.call("GET", path + "/prechecks/" + checks[-1]["job"]["id"])
        assert not obsolete["usable"] and obsolete["job"]["financialVersion"] != corrected["financialVersion"]
        assert obsolete["findings"] == checks[-1]["findings"]
        sources.budget = "SUCCESS"
        ready = base.precheck(runtime, {**fixture, "report": corrected})
        assert ready["job"]["status"] == "READY" and ready["usable"] and not ready["findings"], ready
        assert runtime.call("GET", path) == corrected and corrected["applicationStatus"] == "DRAFT"
        assert {call["operation"] for call in sources.calls} <= {"catalog", "employee-account", "exchange-rate", "expense-policy", "budget-precheck"}
        assert not sources.errors and not sources.model_calls
        runtime.stop(force=True)
        runtime.start(fixed)
        assert runtime.call("GET", path) == corrected
        assert runtime.call("GET", path + "/prechecks/" + checks[-1]["job"]["id"])["findings"] == checks[-1]["findings"]
        assert runtime.call("GET", path + "/prechecks/" + ready["job"]["id"]) == ready
        assert runtime.call("GET", "/applications/" + fixture["applicationId"]) == original_application
        result.update(status="PASS", runtimeBoots=runtime.starts, httpRequests=len(runtime.records),
                      originalBlockedResultPreserved=True, newBlockedOriginalLineNumbers=[7, 7],
                      correctedReadyWithoutSubmission=True, forceRestartPreservedFacts=True,
                      financialWrites=0, modelRequests=0, browserRun=False, postgresRun=False)
        base.save(directory / "verified-prechecks.json", {"legacy": old_check, "currentBlocked": checks, "obsoleteAfterCorrection": obsolete, "correctedReady": ready})
    finally:
        runtime.stop()
        sources.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True)
    parser.add_argument("--legacy-jar", type=Path, required=True)
    parser.add_argument("--fixed-jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    directory = args.output.resolve()
    assert directory.is_relative_to(Path("/fyoung/tmp").resolve()), "Evidence must stay under /fyoung/tmp"
    directory.mkdir(parents=True, exist_ok=False)
    result = {"status": "RUNNING", "legacyJarSha256": base.digest(args.legacy_jar), "fixedJarSha256": base.digest(args.fixed_jar),
              "checkerSha256": base.digest(Path(__file__)), "sharedCheckerSha256": base.digest(Path(base.__file__))}
    try:
        run(args.java, args.legacy_jar.resolve(), args.fixed_jar.resolve(), directory, result)
    except Exception as error:
        result.update(status="FAILED", failureType=type(error).__name__, failure=str(error))
        raise
    finally:
        base.save(directory / "evidence.json", result)
    print(json.dumps(result, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
