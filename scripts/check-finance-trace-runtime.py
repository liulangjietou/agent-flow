#!/usr/bin/env python3
"""固定包检查八类财务队列追踪、非空升级、进程恢复和原命令幂等性。"""

import argparse
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import threading
from uuid import UUID, uuid4

ROOT = Path(__file__).resolve().parents[1]


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / filename)
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value)
    return value


reporting = module("finance_trace_reporting", "check-expense-financial-reporting.py")
tracing = module("finance_trace_headers", "check-agent-trace-runtime.py")
risk, split = reporting.risk, reporting.split
save, digest, wait_for = risk.save, risk.digest, risk.wait_for
TABLES = {"PRECHECK": ("expense_precheck_job", "expense-precheck"),
          "INVOICE": ("invoice_verification_job", "invoice-verification"),
          "BUDGET": ("budget_operation", "budget-operation"),
          "VOUCHER": ("voucher_operation", "voucher-operation"),
          "PAYMENT": ("payment_operation", "payment-operation"),
          "PREPARATION": ("voucher_preparation", "voucher-preparation"),
          "REQUEST": ("payment_execution_request", "payment-execution-request"),
          "PAYEE": ("payment_payee_review", "payment-payee-review")}
FLAGS = ("agentflow.expenses.precheck-worker-enabled", "agentflow.invoices.verification-worker-enabled",
         "agentflow.budgets.worker-enabled", "agentflow.vouchers.worker-enabled", "agentflow.payments.worker-enabled",
         "agentflow.payments.payee-review-worker-enabled")
PARENTS = ("agentflow.vouchers.preparation-worker-enabled", "agentflow.payments.request-worker-enabled")


class Client(risk.Client):
    """只保存响应追踪头，Cookie 和 CSRF 仍由原客户端保存在内存。"""
    def login(self, actor):
        super().login(actor)
        self.sessions[actor][0].add_handler(tracing.TraceResponse(self.runtime))


def runtime_for(java, directory, sources, idp):
    runtime = reporting.runtime_for(java, directory, sources, idp)
    runtime.last_trace, runtime.trace_records = None, []
    runtime.client = Client(runtime, idp); runtime.call = runtime.client.call
    runtime.settings["agentflow.payments.payee-review-poll-delay-ms"] = 200
    return runtime


def workers(runtime, enabled, parents=None):
    for flag in FLAGS: runtime.settings[flag] = enabled
    for flag in PARENTS: runtime.settings[flag] = enabled if parents is None else parents
    runtime.settings["agentflow.expenses.settlement-worker-enabled"] = enabled


def observe_gateway(sources, directory):
    """实际 HTTP 接收方记录诊断头；财务协议及原命令由既有回环网关处理。"""
    records, lock = [], threading.Lock()
    parent = sources.server.RequestHandlerClass

    class Handler(parent):
        def do_POST(self):
            raw = self.rfile.read(int(self.headers["Content-Length"]))
            if self.path.startswith("/finance/"):
                with lock:
                    records.append({"operation": self.path.rsplit("/", 1)[1], "traceId": self.headers.get("X-Trace-Id"),
                                    "idempotencyKey": self.headers.get("Idempotency-Key"), "body": json.loads(raw)})
                    save(directory / "finance-traces.json", records)
            stream = self.rfile; self.rfile = io.BytesIO(raw)
            try: super().do_POST()
            finally: self.rfile = stream

    sources.server.RequestHandlerClass = Handler
    return records


def prepare_wave(runtime, sources, fixture):
    """只通过 API 准备三个独立批准来源和待提交单据，不直接改数据库状态。"""
    definition = {"key": "risk-runtime", "version": 1}
    result = {}
    for name in ("payment", "voucher", "review"):
        value = split.draft(runtime, fixture, definition, "100")
        split.submit(runtime, fixture, value)
        for actor in ("manager", "finance"): split.action(runtime, value, actor)
        if name != "voucher":
            split.action(runtime, value, "finance")
            result[name + "Authorization"] = reporting.authorize(runtime, value)
        result[name] = value
    result["budget"] = split.draft(runtime, fixture, definition, "100")
    result["budgetInput"] = split.prepare(runtime, fixture, result["budget"])
    result["precheck"] = split.draft(runtime, fixture, definition, "100")
    original = ("%PDF-1.7\nsynthetic trace invoice " + uuid4().hex + "\n%%EOF").encode()
    value = runtime.call("POST", "/invoices", {"filename": "合成追踪.pdf", "size": len(original),
                         "sha256": hashlib.sha256(original).hexdigest(), "format": "PDF"}, expected=201)
    runtime.call("PUT", "/invoices/" + value["id"] + "/content", raw=original)
    result["invoice"] = value
    return result


def queue_wave(runtime, sources, fixture, prepared):
    wave, requests = {}, []

    def call(path, body, user="alice", expected=202):
        key = str(uuid4()); receipt = runtime.call("POST", path, body, user, expected, key=key)
        origin = runtime.last_trace
        requests.append({"path": path, "body": body, "user": user, "expected": expected, "key": key, "receipt": receipt})
        return receipt, origin

    def add(kind, identity, origin, path, user="alice"):
        wave[kind] = {"id": identity, "sourceTrace": origin, "path": path, "user": user}

    invoice = prepared["invoice"]["id"]; path = "/invoices/" + invoice
    options = runtime.call("GET", path + "/verification-options")
    receipt, origin = call(path + "/verifications", {"expectedInvoiceVersion": options["invoiceVersion"],
                           "legalEntityId": sources.base.entity, "targetDigest": options["targetDigest"]})
    add("INVOICE", receipt["id"], origin, path + "/verifications/" + receipt["id"])
    value = prepared["precheck"]; path = "/expense-reports/" + value["id"]
    options = runtime.call("GET", path + "/precheck-options")
    receipt, origin = call(path + "/precheck", {**{k: value[k] for k in ("applicationVersion", "financialVersion")},
        "initiatorAppointmentId": fixture["appointments"]["alice"], "accountingDate": risk.instant()[:10], "targetDigest": options["targetDigest"]})
    add("PRECHECK", receipt["id"], origin, path + "/prechecks/" + receipt["id"])
    value = prepared["budget"]; path = "/expense-reports/" + value["id"]
    _, origin = call(path + "/submit", prepared["budgetInput"], expected=200)
    budget = runtime.call("GET", path + "/workflow")["budget"]
    assert budget["operationStatus"] == "QUEUED", budget
    add("BUDGET", budget["operationId"], origin, path + "/workflow")
    value = prepared["voucher"]; task = split.task(runtime, value, "finance")
    _, origin = call("/tasks/" + task["taskId"] + "/actions",
                     {"action": "APPROVE", "expectedVersion": task["version"], "comment": "合成来源追踪批准"}, "finance", 200)
    path = "/applications/" + value["applicationId"] + "/vouchers"
    voucher = wait_for(lambda: runtime.call("GET", path, user="finance"),
                       lambda value: (value.get("operation") or {}).get("status") == "QUEUED", 40)
    add("VOUCHER", voucher["operation"]["id"], origin, path, "finance")
    add("PREPARATION", voucher["preparation"]["id"], origin, path, "finance")
    identity = prepared["paymentAuthorization"]; path = "/cashier/payments/" + identity
    accounts = runtime.call("GET", path + "/accounts", user="bob"); account = accounts["items"][0]
    _, origin = call(path + "/actions", {"action": "EXECUTE", "authorizationVersion": accounts["authorizationVersion"],
        "debitAccountReference": account["reference"], "debitAccountVersion": account["sourceVersion"], "comment": "合成出纳追踪"}, "bob")
    payment = reporting.payment_state(runtime, identity, "QUEUED")["payment"]
    add("PAYMENT", identity, origin, path, "bob"); add("REQUEST", payment["request"]["id"], origin, path, "bob")
    identity = prepared["reviewAuthorization"]
    path = "/applications/" + prepared["review"]["applicationId"] + "/payments"
    value = runtime.call("GET", path, user="finance")
    ended, _ = call("/payments/" + identity + "/finance-actions",
                    {"action": "VOID", "authorizationVersion": value["payment"]["version"], "comment": "合成安全结束后复核"}, "finance")
    receipt, origin = call("/payments/" + identity + "/payee-reviews",
        {"authorizationVersion": ended["authorizationVersion"], "voucherVersion": value["voucherVersion"], "comment": "合成账户追踪复核"}, "finance")
    add("PAYEE", receipt["reviewId"], origin, path, "finance")
    return {"queues": wave, "requests": requests, "prepared": prepared}


def probe(runtime, idp, label):
    """停服后的只读数据库检查，保留所有原输入及命令列。"""
    assert runtime.process.poll() is not None
    source = runtime.directory / "FinanceQueueProbe.java"
    tables = ",".join(json.dumps(value[0]) for value in TABLES.values())
    source.write_text("""import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.JsonUtil;
import java.sql.*;
import java.nio.file.*;
import java.util.*;
/** 离线只读队列观察。@author owlzhangfq@gmail.com */
class FinanceQueueProbe {
 public static void main(String[] args) throws Exception {
  var result = new LinkedHashMap<String,Object>();
  try (var connection = DriverManager.getConnection("jdbc:h2:file:" + args[0] + ";IFEXISTS=TRUE;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0;REUSE_SPACE=FALSE", "sa", "")) {
   for (String table : List.of(TABLES)) {
    var values = new LinkedHashMap<String,Object>();
    try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM " + table)) {
     while (rows.next()) {
      var value = new LinkedHashMap<String,String>();
      for (int i=1;i<=rows.getMetaData().getColumnCount();i++) value.put(rows.getMetaData().getColumnName(i).toLowerCase(Locale.ROOT),rows.getString(i));
      values.put(rows.getString("id"),value);
     }
    } result.put(table,values);
   }
  } Files.writeString(Path.of(args[1]),new JsonUtil(new ObjectMapper()).write(result));
 }
}""".replace("TABLES", tables))
    output = runtime.directory / (label + "-queues.json")
    classpath = str(idp.h2.parent.parent / "classes") + os.pathsep + str(idp.h2.parent / "*")
    with (runtime.directory / (label + "-probe.log")).open("x") as log:
        subprocess.run([runtime.java, "-Djava.io.tmpdir=/fyoung/tmp", "--class-path", classpath, str(source),
                        str(runtime.directory / "data/agentflow"), str(output)], stdout=log, stderr=subprocess.STDOUT, check=True, timeout=40)
    return json.loads(output.read_text())


def completed(kind, value):
    if kind == "PRECHECK": return value["job"]["status"] == "READY"
    if kind == "INVOICE": return value["status"] == "SUCCEEDED"
    if kind == "BUDGET": return value["budget"]["confirmedCurrent"]
    if kind in ("VOUCHER", "PREPARATION"): return (value.get("operation") or {}).get("status") == "POSTED"
    if kind in ("PAYMENT", "REQUEST"): return (value["payment"].get("operation") or {}).get("status") == "SUCCEEDED"
    return (value.get("payeeReview") or {}).get("status") == "READY"


def finish(runtime, wave):
    results = {}
    for request in wave["requests"]:
        replay = runtime.call("POST", request["path"], request["body"], request["user"], request["expected"], key=request["key"])
        assert replay == request["receipt"] and runtime.client.records[-1]["replayed"] == "true"
    for kind, item in wave["queues"].items():
        results[kind] = wait_for(lambda: runtime.call("GET", item["path"], user=item["user"]), lambda value: completed(kind, value), 45)
    reporting.payment_voucher(runtime, wave["prepared"]["payment"])
    return results


def check_traces(wave, records, legacy):
    counts = {}
    for kind, item in wave["queues"].items():
        if legacy and kind in ("PREPARATION", "REQUEST"): continue
        expected = item["sourceTrace"]
        if legacy:
            raw = TABLES[kind][1] + "\0demo\0" + item["id"]
            expected = str(UUID(bytes=hashlib.md5(raw.encode()).digest(), version=3))
        matches = [value for value in records if value["traceId"] == expected]
        assert matches, (kind, expected)
        counts[kind] = len(matches)
        for value in matches:
            assert "traceId" not in value["body"]
            if value["operation"].endswith("-command"):
                assert value["idempotencyKey"] == value["body"]["requestId"]
    return counts


def run(args):
    directory = Path(args.output)
    assert str(directory).startswith("/fyoung/tmp/") and not directory.exists()
    directory.mkdir(); previous, current = Path(args.previous_jar).resolve(), Path(args.current_jar).resolve()
    sources, peer = reporting.sources_for(directory); records = observe_gateway(sources, directory)
    risk.ROLES["admin"].add("FINANCE_CONFIG_ADMIN"); risk.ROLES["bob"].add("CASHIER")
    idp = runtime = restored = None
    evidence = {"status": "RUNNING", "database": "H2", "previousJarSha256": digest(previous), "jarSha256": digest(current),
                "browserVerified": False, "postgresqlVerified": False, "realFinancialSystemsVerified": False, "checks": []}
    save(directory / "evidence.json", evidence)
    try:
        idp = risk.IdentityProvider(args.java, directory, current, financial_reporting=True)
        runtime = runtime_for(args.java, directory / "runtime", sources, idp); workers(runtime, True); runtime.start(previous)
        fixture = risk.setup(runtime, sources); fixture["entityId"] = sources.base.entity; reporting.configure_accounts(runtime, fixture)
        prepared = prepare_wave(runtime, sources, fixture)
        runtime.stop(); workers(runtime, False, parents=True); runtime.start(previous)
        legacy = queue_wave(runtime, sources, fixture, prepared); save(directory / "legacy-wave.json", legacy)
        runtime.stop(); before = split.columns_snapshot(runtime, idp.h2, "before-upgrade"); old_rows = probe(runtime, idp, "before-upgrade")
        workers(runtime, False); runtime.start(current); runtime.stop()
        after = split.columns_snapshot(runtime, idp.h2, "after-upgrade", runtime.directory / "before-upgrade-columns.tsv")
        for table, facts in before["tables"].items():
            if table != "flyway_schema_history":
                assert facts["rows"] == after["tables"][table]["rows"]
                assert facts["sha256"] == after["tables"][table]["originalColumnsSha256"], table
        upgraded = probe(runtime, idp, "after-upgrade")
        for table, _ in TABLES.values():
            assert old_rows[table] and len(upgraded[table]) == len(old_rows[table])
            assert after["columns"][table.upper()] == before["columns"][table.upper()] + ["TRACE_ID"]
            for identity, row in upgraded[table].items():
                assert row["trace_id"] is None
                assert {k: v for k, v in row.items() if k != "trace_id"} == old_rows[table][identity]
        evidence["checks"].append({"name": "nonempty-upgrade", "tables": len(before["tables"]), "queueTables": 8, "inventedOrigins": 0})
        marker = len(records); workers(runtime, True); runtime.start(current)
        legacy_results = finish(runtime, legacy); legacy_counts = check_traces(legacy, records[marker:], True)
        risk.stage("FINANCE_LEGACY_RECOVERY_VERIFIED", queues=8)
        prepared = prepare_wave(runtime, sources, fixture)
        runtime.stop(); workers(runtime, False, parents=True); runtime.start(current)
        wave = queue_wave(runtime, sources, fixture, prepared); save(directory / "current-wave.json", wave)
        runtime.stop(force=True); queued_rows = probe(runtime, idp, "after-queued-kill")
        for kind, item in wave["queues"].items():
            assert queued_rows[TABLES[kind][0]][item["id"]]["trace_id"] == item["sourceTrace"], kind
        workers(runtime, True); runtime.start(current); results = finish(runtime, wave)
        counts = check_traces(wave, records[marker:], False)
        assert all(tracing.TRACE.fullmatch(value["traceId"] or "") for value in records[marker:])
        assert all(value["receivedCommands"] == 1 for value in peer["receipts"].values())
        runtime.stop(); final_rows = probe(runtime, idp, "completed")
        for collection in (legacy, wave):
            for kind, item in collection["queues"].items():
                table = TABLES[kind][0]; before_row, after_row = queued_rows[table][item["id"]], final_rows[table][item["id"]]
                for key in ("trace_id", "input_json", "command_digest"):
                    if key in before_row: assert before_row[key] == after_row[key], (kind, key)
        evidence["checks"].append({"name": "persisted-worker-recovery", "queueTables": 8, "legacyRequestCounts": legacy_counts,
                                   "currentRequestCounts": counts, "originalKeyReplays": len(legacy["requests"]) + len(wave["requests"])})
        backup = split.columns_snapshot(runtime, idp.h2, "before-restore")
        restored = runtime_for(args.java, directory / "restored", sources, idp); workers(restored, False)
        for folder in ("data", "attachments"): shutil.copytree(runtime.directory / folder, restored.directory / folder)
        restored.start(current); restored.stop()
        assert split.columns_snapshot(restored, idp.h2, "after-restore") == backup
        assert probe(restored, idp, "after-restore") == final_rows
        count_before_restore = len(records); restored.start(current)
        for actor in risk.ROLES: restored.client.login(actor)
        for collection, expected in ((legacy, legacy_results), (wave, results)):
            for kind, item in collection["queues"].items():
                assert restored.call("GET", item["path"], user=item["user"]) == expected[kind], kind
        assert len(records) == count_before_restore
        assert not sources.errors and not sources.base.errors
        restored.stop()
        evidence["checks"].append({"name": "independent-restore", "tables": len(backup["tables"]), "sameAuthorizedDetails": 16, "resentRequests": 0})
        evidence.update(status="PASS", boots=runtime.starts + restored.starts, gatewayRequests=len(records),
                        responseTraces=len(runtime.trace_records) + len(restored.trace_records),
                        businessHttpRequests=len(runtime.client.records) + len(restored.client.records))
        save(directory / "evidence.json", evidence); print(json.dumps(evidence, ensure_ascii=False), flush=True)
    finally:
        if restored: restored.stop()
        if runtime: runtime.stop()
        if idp: idp.close()
        sources.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True); parser.add_argument("--previous-jar", required=True)
    parser.add_argument("--current-jar", required=True); parser.add_argument("--output", required=True)
    run(parser.parse_args())
