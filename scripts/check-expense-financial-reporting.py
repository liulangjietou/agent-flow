#!/usr/bin/env python3
"""固定 V120 升级至 V121，真实财务报告、失败重试和独立配套恢复验收。"""

import argparse
import copy
from datetime import date, timedelta
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
from urllib.parse import urlencode
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("prior_runtime", ROOT / "scripts/check-expense-prior-controls.py")
prior = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prior)
split, risk = prior.split, prior.risk
save, digest, instant, wait_for = risk.save, risk.digest, risk.instant, risk.wait_for
BASELINE_SHA256 = "cf7b8815deda4fd4c42de938a9322d94e7b70bf9289bfaeebebda2c01b73a331"
NEW_TABLES = {"EXPENSE_SUBMISSION_REJECTION", "EXPENSE_REPORTING_COVERAGE"}


def sources_for(directory):
    """使用回环财务协议保存原命令，明确区分真实平台流转与合成银行事实。"""
    sources = risk.Sources(directory)
    sources.server.RequestHandlerClass.protocol_version = "HTTP/1.1"
    sources.base.server.RequestHandlerClass.protocol_version = "HTTP/1.1"
    original = sources.finance
    peer = {"verification": "SUCCEEDED", "payment": "SUCCEEDED", "receipts": {}}

    def finance(operation, request):
        data = request["data"]
        envelope = {"contractVersion": 1, "tenantId": "demo", "requestId": request["requestId"], "outcome": "SUCCESS"}
        if operation == "expense-policy" and data.get("managedPolicy"):
            managed = data["managedPolicy"]; selection = managed["selection"]
            rule = next(rule for rule in managed["definition"]["rules"] if data["line"]["categoryCode"] in rule["match"]["categoryCodes"])
            value = {"policy": {"policyId": selection["policyId"], "version": selection["policyVersion"],
                "assessedGross": data["line"]["claimedGross"], "allowedGross": data["line"]["claimedGross"], "decision": "WITHIN_LIMIT",
                "taxRuleReference": "synthetic-report-tax", "evidenceReference": "synthetic-report-policy", "exceptionReasons": [],
                "managedPolicy": {"selection": selection, "ruleKey": rule["key"], "factSourceReference": "synthetic-report-facts"}},
                "deductibleTax": data["line"]["claimedTax"], "priorRequestRequired": False, "validUntil": instant(3600)}
        elif operation == "invoice-verification" and peer["verification"] != "SUCCEEDED":
            if peer["verification"] == "REJECTED":
                return {**envelope, "outcome": "REJECTED", "reason": "INVOICE_INVALID"}, 200
            return {"synthetic": "verification unavailable"}, 503
        elif operation == "invoice-verification":
            result, status = original(operation, request)
            # 多次连续查验不能把新成功事实回填成上次拒绝之前的时间。
            result["data"]["verifiedAt"] = instant()
            return result, status
        elif operation == "accounting-period":
            day = date.fromisoformat(data["accountingDate"])
            value = {"request": data, "periodReference": "synthetic-report-period", "sourceVersion": "v1",
                "startsOn": str(day-timedelta(days=30)), "endsOn": str(day+timedelta(days=30)), "observedAt": instant(), "validUntil": instant(600)}
        elif operation == "account-mapping":
            assert data["managedMapping"] and data["managedMapping"]["legalEntityId"] == sources.base.entity
            value = {"request": data, "sourceVersion": "synthetic-report-erp", "observedAt": instant(), "validUntil": instant(600),
                     "entries": data["managedMapping"]["entries"]}
        elif operation == "debit-accounts":
            assert data["cashierId"] == "bob" and data["currency"] == "CNY"
            value = {"request": data, "sourceVersion": "synthetic-report-banks", "observedAt": instant(), "validUntil": instant(600),
                "accounts": [{"reference": "synthetic-report-bank", "displayName": "合成验收付款账户", "maskedAccount": "****5678", "currency": "CNY", "sourceVersion": "v1"}]}
        elif operation in ("voucher-command", "voucher-query", "payment-command", "payment-query"):
            payment = operation.startswith("payment")
            identifier = data["command"]["id"] if operation.endswith("command") else data["authorizationId" if payment else "operationId"]
            key = ("payment:" if payment else "voucher:") + identifier
            saved = peer["receipts"].get(key)
            if operation.endswith("command"):
                command = data["command"]
                if saved:
                    assert saved["command"] == command and saved["digest"] == data["commandDigest"]
                    saved["receivedCommands"] += 1
                else:
                    stamp = instant()
                    if payment:
                        value = {"authorizationId": identifier, "commandDigest": data["commandDigest"], "status": "SUCCEEDED", "revision": 1,
                            "observedAt": stamp, "paymentReference": "synthetic-report-payment-"+identifier, "paidAmount": command["amount"],
                            "accountDigest": command["payee"]["accountDigest"], "completedAt": stamp, "receiptReference": "synthetic-report-receipt-"+identifier, "failure": None}
                    else:
                        value = {"operationId": identifier, "commandDigest": data["commandDigest"], "status": "POSTED", "revision": 1,
                            "observedAt": stamp, "postingReference": "synthetic-report-posting-"+identifier, "voucherReference": "synthetic-report-voucher-"+identifier,
                            "periodReference": command["period"]["periodReference"], "accountingDate": command["accountingDate"],
                            "debitTotal": command["totals"]["gross"], "creditTotal": command["totals"]["gross"], "postedAt": stamp, "failure": None}
                    saved = {"command": command, "digest": data["commandDigest"], "receipt": value, "receivedCommands": 1}
                    peer["receipts"][key] = saved
                save(directory / "peer-receipts.json", peer)
            assert saved and saved["digest"] == data["commandDigest"], (operation, data)
            if payment and peer["payment"] == "UNKNOWN":
                return {"synthetic": "bank response lost"}, 503
            value = {**saved["receipt"], "observedAt": instant()}
            if payment and peer["payment"] == "REVERSED":
                # 同一退票版本保留固定完成时间，且完成不能晚于本次观察时间。
                if "reversal" not in saved:
                    saved["reversal"] = {**value, "status": "REVERSED", "revision": 2, "completedAt": value["observedAt"],
                        "receiptReference": "synthetic-report-return-"+identifier}
                    save(directory / "peer-receipts.json", peer)
                value = {**saved["reversal"], "observedAt": instant()}
        else:
            return original(operation, request)
        return {**envelope, "data": value}, 200

    sources.finance = finance
    return sources, peer


def runtime_for(java, directory, sources, idp):
    runtime = risk.Runtime(java, directory, sources, idp)
    runtime.settings["agentflow.auth.oidc.role-mappings.admins[1]"] = "FINANCE_CONFIG_ADMIN"
    runtime.settings["agentflow.auth.oidc.role-mappings.cashiers[0]"] = "CASHIER"
    for prefix in ("agentflow.expense-plans.precheck", "agentflow.advance-requests.precheck", "agentflow.vouchers.preparation",
                   "agentflow.vouchers", "agentflow.payments.request", "agentflow.payments", "agentflow.expenses.settlement"):
        runtime.settings[prefix+"-worker-enabled"] = True
        runtime.settings[prefix+"-poll-delay-ms"] = 200
    # 凭证、支付主调度的命名与 preparation/request 子调度不同。
    for prefix in ("agentflow.vouchers", "agentflow.payments"):
        runtime.settings.pop(prefix+"-worker-enabled")
        runtime.settings[prefix+".worker-enabled"] = True
        runtime.settings.pop(prefix+"-poll-delay-ms")
        runtime.settings[prefix+".poll-delay-ms"] = 200
    return runtime


def read_report(runtime, sources, filters=None, user="finance", expected=200):
    """报表读取不得主动产生财务协议调用；调用前先使业务工作队列稳定。"""
    before = len(sources.finance_calls)
    value = runtime.call("GET", "/reports/expense-finance" + ("?"+urlencode(filters) if filters else ""), user=user, expected=expected)
    assert len(sources.finance_calls) == before, "Report query initiated external finance work"
    assert runtime.client.records[-1]["cacheControl"] == "no-store" if expected == 200 else True
    if expected == 200:
        assert value["scope"] == "CURRENT_ACTOR_READABLE" and value["timeZone"] == "UTC"
        assert value["resources"]["asOf"] == value["backlog"]["asOf"] == value["generatedAt"]
    return value


def stable(value):
    value = copy.deepcopy(value)
    value.pop("generatedAt"); value["resources"].pop("asOf"); value["backlog"].pop("asOf")
    return value


def invoice(runtime, sources, peer, duplicate_of=None):
    original = ("%PDF-1.7\nsynthetic reporting invoice "+uuid4().hex+"\n%%EOF").encode()
    original_digest = hashlib.sha256(original).hexdigest()
    value = runtime.call("POST", "/invoices", {"filename": "合成财务报表原件.pdf", "size": len(original),
        "sha256": original_digest, "format": "PDF"}, expected=201)
    runtime.call("PUT", "/invoices/"+value["id"]+"/content", raw=original)
    invoice_digests = peer.setdefault("invoiceDigests", {})
    invoice_digests[value["id"]] = original_digest
    if duplicate_of is not None:
        sources.invoices[original_digest] = sources.invoices[invoice_digests[duplicate_of]]
    for status in ("REJECTED", "UNAVAILABLE", "SUCCEEDED"):
        peer["verification"] = status
        verify(runtime, sources, value["id"], status)
    return value["id"]


def verify(runtime, sources, identifier, status="SUCCEEDED"):
    path = "/invoices/"+identifier
    options = runtime.call("GET", path+"/verification-options")
    queued = runtime.call("POST", path+"/verifications", {"expectedInvoiceVersion": options["invoiceVersion"],
        "legalEntityId": sources.base.entity, "targetDigest": options["targetDigest"]}, expected=202)
    checked = wait_for(lambda: runtime.call("GET", path+"/verifications/"+queued["id"]), lambda value: value["status"] not in ("QUEUED", "RUNNING"), 30)
    assert checked["status"] == status, checked


def invoice_draft(runtime, fixture, identifier):
    report = split.draft(runtime, fixture, {"key": "risk-runtime", "version": 1}, "100")
    content = copy.deepcopy(report["content"])
    content["lines"][0].update(invoiceIds=[identifier], claimedTax=prior.money("6"))
    return runtime.call("POST", "/expense-reports/"+report["id"]+"/revise", {**{k: report[k] for k in ("applicationVersion", "financialVersion")}, "content": content})


def lifecycle(runtime, report, action):
    current = split.detail(runtime, report)
    return runtime.call("POST", "/expense-reports/"+report["id"]+"/"+action, {
        **{k: current[k] for k in ("applicationVersion", "financialVersion")}, "comment": "合成报表轮次生命周期"})


def configure_accounts(runtime, fixture):
    categories = prior.configure(runtime, "TOLERANCE")
    definition = {"name": "合成报表科目", "legalEntityId": fixture["entityId"], "currency": "CNY", "entries": [
        {"key": {"role": role, "selector": selector}, "accountCode": "SYNTHETIC."+role}
        for role, selector in (("EXPENSE", "OFFICE"), ("DEDUCTIBLE_TAX", ""), ("EMPLOYEE_RECEIVABLE", ""), ("EMPLOYEE_PAYABLE", ""), ("BANK", "synthetic-report-bank"))]}
    current = runtime.call("GET", "/admin/account-mappings/current?"+urlencode({"legalEntityId": fixture["entityId"], "currency": "CNY"}), user="admin")
    path = "/admin/account-mappings/financial-report-runtime"
    draft = runtime.call("PUT", path+"/draft", {"expectedRevision": 0, "definition": definition, "comment": "合成科目"}, "admin")
    runtime.call("POST", path+"/publish", {"expectedDraftRevision": draft["revision"], "expectedCategoryRevision": categories["version"],
        "expectedActiveRevision": current["activeRevision"], "comment": "明确发布合成科目"}, "admin")
    person = runtime.call("POST", "/organization/people", {"subject": "bob", "displayName": "合成出纳", "active": True, "approvalEligible": False}, "admin", 201)
    appointment = runtime.call("GET", "/organization/appointments?personId="+fixture["people"]["finance"], user="admin")["items"][0]
    runtime.call("POST", "/organization/appointments", {"personId": person["id"], "departmentId": appointment["departmentId"],
        "positionId": appointment["positionId"], "active": True}, "admin", 201)


def authorize(runtime, report):
    path = "/applications/"+report["applicationId"]+"/payments"
    def ready():
        value = runtime.call("GET", path, user="finance")
        if value["actions"]["authorize"]:
            return value
        voucher = runtime.call("GET", "/applications/"+report["applicationId"]+"/vouchers", user="finance")
        preparation = voucher.get("preparation")
        assert not preparation or preparation["status"] not in ("UNAVAILABLE", "BLOCKED"), voucher
        return None
    value = wait_for(ready, bool, 40)
    body = {k: value[k] for k in ("roundNo", "applicationVersion", "businessVersion", "voucherOperationId", "voucherVersion")}
    body.update(validitySeconds=3600, dueDate=instant()[:10], comment="财务核对原轮次和凭证后授权")
    return runtime.call("POST", path+"/authorizations", body, "finance", 202)["authorizationId"]


def pay(runtime, identifier, expected):
    path = "/cashier/payments/"+identifier
    accounts = runtime.call("GET", path+"/accounts", user="bob"); account = accounts["items"][0]
    runtime.call("POST", path+"/actions", {"action": "EXECUTE", "authorizationVersion": accounts["authorizationVersion"],
        "debitAccountReference": account["reference"], "debitAccountVersion": account["sourceVersion"], "comment": "出纳核对后执行合成付款"}, "bob", 202)
    return payment_state(runtime, identifier, expected)


def payment_state(runtime, identifier, expected):
    return wait_for(lambda: runtime.call("GET", "/cashier/payments/"+identifier, user="bob"),
        lambda value: (value["payment"].get("operation") or {}).get("status") == expected, 40)


def query_payment(runtime, identifier, expected):
    path = "/cashier/payments/"+identifier
    current = runtime.call("GET", path, user="bob")
    runtime.call("POST", path+"/actions", {"action": "QUERY", "authorizationVersion": current["payment"]["version"],
        "operationVersion": current["payment"]["operation"]["version"], "comment": "只查询原银行命令"}, "bob", 202)
    return payment_state(runtime, identifier, expected)


def payment_voucher(runtime, report):
    """到账会登记独立付款凭证，等原后台工作完成后再验证报表没有外部调用。"""
    path = "/applications/"+report["applicationId"]+"/vouchers/payment"
    def read():
        value = runtime.call("GET", path, user="finance")
        preparation = value.get("preparation")
        assert not preparation or preparation["status"] not in ("UNAVAILABLE", "BLOCKED"), value
        return value
    return wait_for(read, lambda value: (value.get("operation") or {}).get("status") == "POSTED", 40)


def loan(runtime, fixture, definition):
    content = {"legalEntityId": fixture["entityId"], "title": "合成报表借款", "purpose": "固定运行包账龄验收", "amount": prior.money("300"),
        "dueOn": instant()[:10]}
    value = runtime.call("POST", "/advance-requests", {"businessNo": "F15-LOAN-"+uuid4().hex, "processKey": definition["key"],
        "definitionVersion": definition["version"], "content": content}, expected=201)
    path = "/advance-requests/"+value["id"]
    options = runtime.call("GET", path+"/prechecks/options")
    check = runtime.call("POST", path+"/prechecks", {**{k: value[k] for k in ("applicationVersion", "requestVersion")},
        "initiatorAppointmentId": fixture["appointments"]["alice"], "targetDigest": options["targetDigest"]}, expected=202)
    checked = wait_for(lambda: runtime.call("GET", path+"/prechecks/"+check["id"]), lambda item: item["job"]["status"] not in ("QUEUED", "RUNNING"), 30)
    assert checked["usable"] and checked["job"]["status"] == "READY", checked
    runtime.call("POST", path+"/submit", {**{k: value[k] for k in ("applicationVersion", "requestVersion")}, "precheckId": check["id"]})
    split.action(runtime, value, "manager"); split.action(runtime, value, "finance")
    pay(runtime, authorize(runtime, value), "SUCCEEDED")
    payment_voucher(runtime, value)
    return value


def run(args):
    directory = Path(args.output)
    assert str(directory).startswith("/fyoung/tmp/") and not directory.exists(), "Use a fresh owned temporary directory"
    directory.mkdir(parents=True)
    before_jar, after_jar = Path(args.before).resolve(), Path(args.after).resolve()
    assert digest(before_jar) == BASELINE_SHA256, "Preserved V120 baseline differs"
    sources, peer = sources_for(directory)
    risk.ROLES["admin"].add("FINANCE_CONFIG_ADMIN"); risk.ROLES["bob"].add("CASHIER")
    idp, runtime, restored = None, None, None
    evidence = {"status": "RUNNING", "startedAt": instant(), "beforeSha256": digest(before_jar), "afterSha256": digest(after_jar),
        "database": "H2", "browserVerified": False, "postgresqlVerified": False, "checks": []}
    save(directory / "evidence.json", evidence)
    files = ("scripts/check-expense-financial-reporting.py", "scripts/check-expense-prior-controls.py", "scripts/check-expense-split-routing.py",
        "scripts/check-expense-risk.py", "scripts/check-precheck-explanation.py", "scripts/payment-due-dates-support/CompareH2Schema.java",
        "scripts/fixtures/ExpenseRiskOidcFixture.java", "agentflow-server/src/test/java/io/agentflow/auth/OidcTestProvider.java")
    for name in files:
        target = directory / "harness-source" / name; target.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(ROOT/name, target)
    try:
        idp = risk.IdentityProvider(args.java, directory, after_jar, financial_reporting=True)
        runtime = runtime_for(args.java, directory/"original", sources, idp)
        runtime.start(before_jar)
        fixture = risk.setup(runtime, sources); fixture["entityId"] = sources.base.entity
        for report in fixture["reports"]:
            split.budget(runtime, report)
        legacy = fixture["reports"][0]; old_detail = split.detail(runtime, legacy); old_task = split.task(runtime, legacy, "manager")
        original_submit = next(value for value in runtime.client.records if value["path"].endswith("/"+legacy["id"]+"/submit"))
        runtime.stop(); before = split.columns_snapshot(runtime, idp.h2, "before-upgrade")
        runtime.start(after_jar); runtime.stop()
        after = split.columns_snapshot(runtime, idp.h2, "after-upgrade", runtime.directory/"before-upgrade-columns.tsv")
        assert set(after["tables"])-set(before["tables"]) == NEW_TABLES
        changed = []
        for name, table in before["tables"].items():
            if (table["rows"], table["sha256"]) != (after["tables"][name]["rows"], after["tables"][name]["originalColumnsSha256"]):
                changed.append(name)
            assert before["columns"][name] == after["columns"][name] and before["metadata"][name] == after["metadata"][name], name
        assert changed == ["flyway_schema_history"], changed
        assert after["tables"]["EXPENSE_SUBMISSION_REJECTION"]["rows"] == 0 and after["tables"]["EXPENSE_REPORTING_COVERAGE"]["rows"] == 1
        evidence["checks"].append({"name": "nonempty-v120-upgrade", "oldTables": len(before["tables"]), "oldRows": sum(x["rows"] for x in before["tables"].values()), "changedOldTables": changed})
        risk.stage("REPORT_UPGRADE_VERIFIED", oldTables=len(before["tables"]))
        runtime.start(after_jar)
        assert split.detail(runtime, legacy) == old_detail and split.task(runtime, legacy, "manager") == old_task
        assert runtime.call("POST", original_submit["path"].removeprefix("/api/v1"), original_submit["request"], key=original_submit["key"]) == original_submit["response"]
        assert runtime.client.records[-1]["replayed"] == "true"
        assert read_report(runtime, sources)["totals"]["submitted"] == 0
        for actor in ("alice", "manager", "admin", "bob"):
            read_report(runtime, sources, user=actor, expected=403)
        split.action(runtime, legacy)
        assert read_report(runtime, sources)["totals"]["submitted"] == 1

        # READY 后同原件被其他单据占用，丢失的是拒绝回执；恢复只能保持原失败且只计一次。
        invoice_id = invoice(runtime, sources, peer)
        winner = invoice_draft(runtime, fixture, invoice_id); rejected = invoice_draft(runtime, fixture, invoice_id)
        winner_input = split.prepare(runtime, fixture, winner); rejected_input = split.prepare(runtime, fixture, rejected)
        runtime.call("POST", "/expense-reports/"+winner["id"]+"/submit", winner_input); split.budget(runtime, winner)
        failure_path = "/expense-reports/"+rejected["id"]+"/submit"; failure_key = str(uuid4())
        failed = runtime.client.lose_response(failure_path, rejected_input, failure_key, 422, user="alice")
        assert failed["code"] == "RESOURCES_CHANGED"
        runtime.stop(force=True); runtime.start(after_jar)
        assert runtime.call("POST", failure_path, rejected_input, expected=422, key=failure_key)["code"] == "RESOURCES_CHANGED"
        stale = prior.check(runtime, fixture, rejected)
        assert stale["job"]["status"] == "BLOCKED" and any(x["code"] == "INVOICE_VERIFICATION_REQUIRED" for x in stale["findings"]), stale
        # 同原件版本变化先要求重新查验；另一份已查验同票号原件才能独立验证票号占用拦截。
        invoice_id = invoice(runtime, sources, peer, duplicate_of=invoice_id)
        current = split.detail(runtime, rejected); content = copy.deepcopy(current["content"])
        content["lines"][0]["invoiceIds"] = [invoice_id]
        runtime.call("POST", "/expense-reports/"+rejected["id"]+"/revise", {
            **{k: current[k] for k in ("applicationVersion", "financialVersion")}, "content": content})
        blocked = prior.check(runtime, fixture, rejected)
        assert blocked["job"]["status"] == "BLOCKED" and any(x["code"] == "INVOICE_OCCUPIED" for x in blocked["findings"]), blocked
        lifecycle(runtime, winner, "withdraw"); lifecycle(runtime, winner, "cancel")
        wait_for(lambda: runtime.call("GET", "/expense-reports/"+winner["id"]+"/workflow"), lambda x: x["budget"]["ledgerStatus"] == "RELEASED", 30)
        split.submit(runtime, fixture, rejected); split.action(runtime, rejected)
        attempts = read_report(runtime, sources)["activity"]
        assert attempts["duplicateSubmissions"]["recorded"] == 1 and attempts["duplicatePrechecks"] == 1, attempts
        assert attempts["verification"] == {"succeeded": 3, "rejected": 1, "unavailable": 1, "pending": 0, "failureRate": 0.25}, attempts
        verify(runtime, sources, invoice_id)
        assert read_report(runtime, sources)["activity"] == attempts
        evidence["checks"].append({"name": "failed-submission-crash-original-key-and-attempts", "recorded": 1, "prechecks": 1, "privateLaterVerificationExcluded": True})
        risk.stage("REPORT_REJECTION_RECOVERY_VERIFIED")

        # 退回原因及重提各自保留；未经财务处理的新轮次不会通过历史参与身份自动进入报告。
        split.action(runtime, rejected, "finance", "RETURN")
        returned = read_report(runtime, sources)
        assert returned["totals"]["returned"] == 1 and returned["totals"]["returnRate"] == 1
        split.submit(runtime, fixture, rejected)
        assert read_report(runtime, sources)["totals"]["submitted"] == 2
        split.action(runtime, rejected)
        assert read_report(runtime, sources)["totals"]["submitted"] == 3

        configure_accounts(runtime, fixture)
        plan_definition = prior.template(runtime, fixture, "expense-plan")
        credit = prior.plan(runtime, fixture, plan_definition, "100")
        expense_definition = {"key": "risk-runtime", "version": 1}
        funded = prior.draft(runtime, fixture, expense_definition, credit, ["40"])
        split.submit(runtime, fixture, funded)
        reserved = read_report(runtime, sources)["resources"]["priorRequests"][0]
        assert reserved["approved"] == "100.00" and reserved["consumed"] == "0.00" and reserved["reserved"] == "40.00", reserved
        for actor in ("manager", "finance", "finance"):
            split.action(runtime, funded, actor)
        identifier = authorize(runtime, funded)
        peer["payment"] = "UNKNOWN"; pay(runtime, identifier, "UNKNOWN")
        unknown = read_report(runtime, sources)
        assert unknown["backlog"]["payments"]["UNKNOWN"] == 1 and unknown["totals"]["payment"]["unknown"] == 1
        peer["payment"] = "SUCCEEDED"; query_payment(runtime, identifier, "SUCCEEDED")
        wait_for(lambda: prior.credit_view(runtime, credit), lambda x: x["lines"][0]["consumed"]["value"] == "40.00", 30)
        wait_for(lambda: runtime.call("GET", "/expense-reports/"+funded["id"]+"/settlement", user="finance"),
            lambda value: (value.get("settlement") or {}).get("status") == "SETTLED", 30)
        payment_voucher(runtime, funded)
        arrived = read_report(runtime, sources)
        assert arrived["totals"]["payment"]["samples"] == 1
        assert arrived["resources"]["priorRequests"][0]["executionRate"] == 0.4
        peer["payment"] = "REVERSED"; query_payment(runtime, identifier, "REVERSED")
        reversed_report = read_report(runtime, sources)
        assert reversed_report["backlog"]["payments"]["REVERSED"] == 1
        assert reversed_report["totals"]["payment"] == arrived["totals"]["payment"]
        assert peer["receipts"]["payment:"+identifier]["receivedCommands"] == 1
        peer["payment"] = "SUCCEEDED"
        loan_value = loan(runtime, fixture, prior.template(runtime, fixture, "advance-request"))
        loan_report = read_report(runtime, sources)
        assert loan_report["resources"]["advances"][0]["outstanding"] == "300.00", loan_report["resources"]
        assert loan_report["resources"]["advances"][0]["ages"][0]["outstanding"] == "300.00"
        category = read_report(runtime, sources, {"categoryCode": "OFFICE"})
        assert category["resources"]["advances"] == [] and category["resources"]["advanceCategoryApplicable"] is False
        yesterday = str(date.fromisoformat(instant()[:10])-timedelta(days=1))
        stock = read_report(runtime, sources, {"from": yesterday, "to": yesterday})
        assert stock["totals"]["submitted"] == 0 and stock["resources"] == loan_report["resources"] | {"asOf": stock["generatedAt"]}
        evidence["checks"].append({"name": "original-rounds-payment-and-current-resources", "paymentCommands": 1, "planConsumed": "40.00", "planExecutionRate": 0.4, "loanOutstanding": "300.00", "unknownSeparatedFromFailure": True})
        risk.stage("REPORT_FINANCIAL_FACTS_VERIFIED")

        # 配套还原必须包含原件及合成外部系统的原命令，保留未办完的原任务。
        report_before_restore = read_report(runtime, sources); task_before_restore = split.task(runtime, rejected, "finance")
        runtime.stop(); preserved = split.columns_snapshot(runtime, idp.h2, "before-restore")
        assert preserved["tables"]["EXPENSE_SUBMISSION_REJECTION"]["rows"] == 1
        backup = directory/"backup"; backup.mkdir()
        for name in ("data", "attachments"):
            shutil.copytree(runtime.directory/name, backup/name)
        save(backup/"synthetic-peer-state.json", {"commands": sources.commands, "invoices": sources.invoices, "entity": sources.base.entity, "peer": peer})
        save(backup/"manifest.json", {"jarSha256": digest(after_jar), "files": {str(p.relative_to(backup)): digest(p) for p in backup.rglob('*') if p.is_file()}})
        database_sha = digest(runtime.directory/"data/agentflow.mv.db")
        restored = runtime_for(args.java, directory/"restored", sources, idp)
        for name in ("data", "attachments"):
            shutil.copytree(backup/name, restored.directory/name)
        restored.process = runtime.process
        assert split.columns_snapshot(restored, idp.h2, "restored-before-start") == preserved
        schema = prior.restore_schema(runtime, restored, idp.h2)
        original_peer = json.loads((backup/"synthetic-peer-state.json").read_text())
        sources.commands, sources.invoices, sources.base.entity = original_peer["commands"], original_peer["invoices"], original_peer["entity"]
        peer.clear(); peer.update(original_peer["peer"])
        restored.start(after_jar); restored.client.sessions = runtime.client.sessions; restored.client.identities = copy.deepcopy(runtime.client.identities)
        assert stable(read_report(restored, sources)) == stable(report_before_restore)
        assert split.task(restored, rejected, "finance") == task_before_restore
        for index, invoice_id in enumerate(fixture["invoices"]):
            assert restored.call("GET", "/invoices/"+invoice_id+"/content") == ("%PDF-1.7\nsynthetic invoice "+str(index)+"\n%%EOF").encode()
        split.action(restored, rejected, "finance")
        assert split.task(restored, rejected, "finance")["taskId"] != task_before_restore["taskId"]
        restored.stop()
        assert digest(runtime.directory/"data/agentflow.mv.db") == database_sha
        assert not sources.errors and not sources.base.errors and not sources.model_calls and not sources.base.model_calls
        evidence["checks"].append({"name": "independent-paired-restore", "tables": len(preserved["tables"]), "rows": sum(x["rows"] for x in preserved["tables"].values()),
            "schemaStatements": schema["statements"], "originalDatabaseUnchanged": True, "originalTaskContinued": True, "originalRejectionPreserved": True})
        save(directory/"fixture.json", {**fixture, "rejected": rejected, "funded": funded, "loan": loan_value, "credit": credit})
        save(directory/"after-http-records.json", [value for value in runtime.client.records if value["boot"] > 1]+restored.client.records)
        evidence.update(status="PASSED", finishedAt=instant(), httpRecords=len(runtime.client.records)+len(restored.client.records),
            providerCalls=len(sources.finance_calls), boots=runtime.starts+restored.starts)
        risk.stage("REPORT_PAIRED_RESTORE_VERIFIED", httpRecords=evidence["httpRecords"])
    except BaseException as failure:
        evidence.update(status="FAILED", error=repr(failure), finishedAt=instant()); raise
    finally:
        for owned in (restored, runtime):
            if owned is not None: owned.stop()
        if idp is not None: idp.close()
        sources.close()
        evidence["ownedProcessesStopped"] = all(owned is None or owned.process is None or owned.process.poll() is not None for owned in (runtime, restored, idp))
        save(directory/"evidence.json", evidence)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--before', required=True); parser.add_argument('--after', required=True)
    parser.add_argument('--java', required=True); parser.add_argument('--output', required=True)
    run(parser.parse_args())
