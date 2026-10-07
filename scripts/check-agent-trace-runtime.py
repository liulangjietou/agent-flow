#!/usr/bin/env python3
"""固定包验证六类 Agent 队列的来源追踪、非空升级、重启和独立恢复。"""

import argparse
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import threading
from urllib.request import HTTPHandler
from uuid import UUID, uuid4

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("split_trace_runtime", ROOT / "scripts/check-expense-split-routing.py")
split = importlib.util.module_from_spec(spec)
spec.loader.exec_module(split)
risk, common = split.risk, split.risk.common
save, digest, wait_for = common.save, common.digest, common.wait_for
TRACE = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
TABLES = {"SUMMARY": ("agent_assist_job", "assist-run"), "DRAFT": ("agent_draft_assist_run", "draft-assist"),
          "INVOICE": ("agent_invoice_extraction_run", "invoice-extraction"), "EXPENSE": ("agent_expense_draft_run", "expense-draft"),
          "PRECHECK": ("agent_precheck_explanation_run", "precheck-explanation"), "RISK": ("agent_expense_risk_run", "expense-risk")}
SENTRY = "o01-agent-private-sentinel"


class TraceResponse(HTTPHandler):
    """只保留已完成响应的诊断头；不保存会话和 CSRF 凭据。"""
    def __init__(self, runtime):
        super().__init__(); self.runtime = runtime

    def http_response(self, request, response):
        trace = response.headers.get("X-Trace-Id")
        assert trace and TRACE.fullmatch(trace), (request.selector, "Missing response trace")
        self.runtime.last_trace = trace
        self.runtime.trace_records.append({"method": request.get_method(), "path": request.selector.split("?")[0],
                                           "status": response.status, "traceId": trace, "boot": self.runtime.starts})
        save(self.runtime.directory / "response-traces.json", self.runtime.trace_records)
        return response


class Client(risk.Client):
    def login(self, actor):
        super().login(actor)
        self.sessions[actor][0].add_handler(TraceResponse(self.runtime))


class Model:
    """六种现有模型协议共用回环接收方，实际检查诊断头和选择性输入。"""
    def __init__(self, directory):
        self.requests, self.errors = [], []
        self.lock = threading.Lock()
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                try:
                    raw = self.rfile.read(int(self.headers["Content-Length"]))
                    body = json.loads(raw); trace = self.headers.get("X-Trace-Id")
                    assert trace and TRACE.fullmatch(trace)
                    assert self.headers.get("X-Tenant-Id") is None
                    assert SENTRY not in raw.decode() and "tenantId" not in raw.decode()
                    assert body["store"] is False and body["stream"] is False and "tools" not in body
                    content = body["messages"][1]["content"]
                    if isinstance(content, list):
                        kind = "INVOICE"; source = json.loads(content[0]["text"])["source"]
                        output = {"proposals": [{"field": "INVOICE_NUMBER", "value": "000077", "confidence": "MEDIUM",
                                  "evidence": [{"originalId": source["originalId"], "originalDigest": source["originalDigest"], "page": 1, "quote": "000077"}]}]}
                    else:
                        content = json.loads(content); sources = content["sources"]
                        refs = [source["reference"] for source in sources]
                        if "concerns" in content:
                            kind = "RISK"
                            output = {"items": [{"concernSourceId": concern["sourceId"], "kind": concern["kind"],
                                      "explanation": "合成解释：所选事实需要人工核对", "limitations": "不证明违规",
                                      "checks": ["核对业务依据"], "evidence": refs} for concern in content["concerns"]]}
                        elif "issueSourceIds" in content:
                            kind = "PRECHECK"; indexed = {ref["sourceId"]: ref for ref in refs}
                            output = {"items": [{"issueSourceId": identity, "explanation": "请核对原检查中的预算结果。",
                                      "corrections": ["核对费用归属后重新预检"], "evidence": [indexed[identity]]} for identity in content["issueSourceIds"]]}
                        elif "targetSchema" in content:
                            kind = "DRAFT"
                            output = {"proposals": [{"targetId": "form:reason", "value": "合成用途说明", "evidence": refs}]}
                        elif any(ref["sourceId"].startswith("expense:") for ref in refs):
                            kind = "EXPENSE"; output = {"lines": []}
                        else:
                            kind = "SUMMARY"; output = {"claims": [{"text": "合成材料摘要，需人工核对。", "evidence": refs}], "confidence": 0.5}
                    with fixture.lock:
                        fixture.requests.append({"kind": kind, "traceId": trace, "sha256": hashlib.sha256(raw).hexdigest(), "body": body})
                        save(directory / "model-requests.json", fixture.requests)
                    result = {"model": "synthetic-trace-v1", "choices": [{"finish_reason": "stop", "message": {
                              "role": "assistant", "content": json.dumps(output, ensure_ascii=False)}}]}
                    payload = json.dumps(result, ensure_ascii=False).encode()
                    self.send_response(200); self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(payload))); self.end_headers(); self.wfile.write(payload)
                except Exception as error:
                    fixture.errors.append(repr(error)); save(directory / "model-errors.json", fixture.errors)
                    self.send_error(500)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True); self.thread.start()

    def close(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join(timeout=5)


def runtime_for(java, directory, sources, idp, model):
    runtime = risk.Runtime(java, directory, sources, idp)
    runtime.client = Client(runtime, idp); runtime.call = runtime.client.call
    runtime.last_trace, runtime.trace_records = None, []
    runtime.settings.update({"agentflow.assist.endpoint": f"http://127.0.0.1:{model.server.server_port}/model",
                             "agentflow.assist.model": "synthetic-trace", "agentflow.assist.timeout-seconds": 4,
                             "agentflow.invoices.extraction-worker-enabled": False, "agentflow.invoices.extraction-poll-delay-ms": 200})
    return runtime


def basic_definition(runtime, fixture):
    graph = {"nodes": [{"id": "start", "name": "开始", "type": "START", "properties": {}},
             {"id": "review", "name": "审批", "type": "USER_TASK", "properties": {"assigneeRule": "role:ORG_PERSON_" + fixture["people"]["manager"]}},
             {"id": "end", "name": "结束", "type": "END", "properties": {}}],
             "edges": [{"id": "first", "source": "start", "target": "review", "condition": ""}, {"id": "last", "source": "review", "target": "end", "condition": ""}]}
    definition = runtime.call("POST", "/process-definitions", {"key": "agent-trace", "name": "合成 Agent 追踪", "graph": graph,
                 "formSchema": {"schemaVersion": 2, "fields": [{"key": "reason", "label": "说明", "type": "TEXT", "required": False},
                 {"key": "secret", "label": "敏感字段", "type": "TEXT", "required": False, "sensitive": True}]}}, "admin")
    return runtime.call("POST", "/process-definitions/" + definition["id"] + "/publish?expectedRevision=" + str(definition["revision"]), {"changeNote": "合成追踪验收"}, "admin")


def queue_wave(runtime, sources, fixture, definition, label):
    """全由真实 API 入队，原请求键、正文和来源头一起保留供重启核对。"""
    wave = []
    def queue(kind, path, body, user="alice"):
        key = str(uuid4()); receipt = runtime.call("POST", path, body, user, 202, key=key)
        wave.append({"kind": kind, "path": path, "body": body, "user": user, "key": key,
                     "receipt": receipt, "sourceTrace": runtime.last_trace})

    def application():
        return runtime.call("POST", "/applications", {"businessNo": label + "-" + str(uuid4()), "processKey": definition["key"],
                            "definitionVersion": definition["version"], "title": "合成申请", "payload": {"reason": "采购资料需核对", "secret": SENTRY}}, expected=201)

    draft = application(); path = "/applications/" + draft["id"] + "/draft-assist-runs"
    options = runtime.call("GET", path + "/input")
    queue("DRAFT", path, {"expectedVersion": 1, "targetDigest": options["targetDigest"], "brief": "根据来源整理用途说明", "sourceIds": ["form:reason"]})
    app = application(); runtime.call("POST", "/applications/" + app["id"] + "/submit", {"expectedVersion": 1, "initiatorAppointmentId": fixture["appointments"]["alice"]})
    task = risk.tasks(runtime, app["id"])[0]["taskId"]; path = "/applications/" + app["id"] + "/assist-runs"
    options = runtime.call("GET", path + "/input?taskId=" + task, user="manager")
    queue("SUMMARY", path, {"taskId": task, "expectedVersion": 2, "targetDigest": options["targetDigest"], "sourceIds": ["form:reason"]}, "manager")
    raw = b"<Invoice><Number>000077</Number></Invoice>"
    invoice = runtime.call("POST", "/invoices", {"filename": "synthetic.xml", "format": "XML", "size": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}, expected=201)
    runtime.call("PUT", "/invoices/" + invoice["id"] + "/content", raw=raw)
    path = "/invoices/" + invoice["id"] + "/extraction-runs"; options = runtime.call("GET", path + "/input")
    queue("INVOICE", path, {"expectedOriginalId": options["input"]["originalId"], "expectedOriginalDigest": options["input"]["originalDigest"],
                           "method": "MODEL", "targetDigest": options["targetDigest"], "externalSendConfirmed": True})
    content = {"legalEntityId": sources.base.entity, "type": "TRAVEL", "title": SENTRY, "advanceOffsets": [], "lines": []}
    report = runtime.call("POST", "/expense-reports", {"businessNo": label + "-draft", "processKey": "risk-runtime", "definitionVersion": 1, "content": content}, expected=201)
    path = "/expense-reports/" + report["id"] + "/draft-assists"
    body = {"applicationVersion": 1, "financialVersion": 1, "brief": "整理原行程的办公事项", "itinerary": [{"id": 1, "startsOn": common.instant()[:10],
            "endsOn": common.instant()[:10], "cityCode": "SH", "purpose": "现场核对"}],
            "catalog": {"categoryCodes": ["OFFICE"], "costCenterCodes": ["IT"], "projectCodes": []}}
    preview = runtime.call("POST", path + "/preview", body)
    queue("EXPENSE", path, {"input": body, "validUntil": preview["input"]["validUntil"], "targetDigest": preview["targetDigest"], "consentDigest": preview["consentDigest"]})
    money = {"value": "100", "currency": "CNY"}
    content = {**content, "type": "DAILY", "lines": [{"lineNo": 1, "categoryCode": "OFFICE", "incurredOn": common.instant()[:10], "cityCode": "SH", "quantity": 1, "unit": "ITEM",
               "claimedGross": money, "claimedTax": {"value": "0", "currency": "CNY"}, "invoiceIds": [], "allocations": [{"costCenter": "IT", "amount": money}], "description": SENTRY}]}
    report = runtime.call("POST", "/expense-reports", {"businessNo": label + "-precheck", "processKey": "risk-runtime", "definitionVersion": 1, "content": content}, expected=201)
    precheck_fixture = {"report": report, "appointmentId": fixture["appointments"]["alice"]}
    sources.base.budget = "BLOCKED"
    try: check = common.precheck(runtime, precheck_fixture)
    finally: sources.base.budget = "READY"
    assert check["job"]["status"] == "BLOCKED", check
    path, body = common.generation(runtime, precheck_fixture, check); queue("PRECHECK", path, body)
    path, body = risk.generation(runtime, fixture); queue("RISK", path, body, "manager")
    assert len(wave) == 6
    return wave


def probe(runtime, idp, label):
    """应用停服后读取实际队列列值，不改时钟、租约或状态。"""
    assert runtime.process.poll() is not None
    source = runtime.directory / "AgentQueueProbe.java"
    source.write_text('''import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.JsonUtil;
import java.sql.*;
import java.nio.file.*;
import java.util.*;
/** 离线只读队列观察。@author owlzhangfq@gmail.com */
class AgentQueueProbe {
 public static void main(String[] args) throws Exception {
  var result = new LinkedHashMap<String,Object>();
  try (var connection = DriverManager.getConnection("jdbc:h2:file:" + args[0] + ";IFEXISTS=TRUE;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0;REUSE_SPACE=FALSE", "sa", "")) {
   for (String table : List.of("agent_assist_job","agent_draft_assist_run","agent_invoice_extraction_run","agent_expense_draft_run","agent_precheck_explanation_run","agent_expense_risk_run")) {
    var values = new LinkedHashMap<String,Object>();
    try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM " + table)) {
     while (rows.next()) {
      var value = new LinkedHashMap<String,String>();
      for (int i=1;i<=rows.getMetaData().getColumnCount();i++) value.put(rows.getMetaData().getColumnName(i).toLowerCase(Locale.ROOT),rows.getString(i));
      values.put(rows.getString(table.equals("agent_assist_job") ? "run_id" : "id"),value);
     }
    } result.put(table,values);
   }
  } Files.writeString(Path.of(args[1]),new JsonUtil(new ObjectMapper()).write(result));
 }
}''')
    output = runtime.directory / (label + "-queue-probe.json")
    classpath = str(idp.h2.parent.parent / "classes") + os.pathsep + str(idp.h2.parent / "*")
    with (runtime.directory / (label + "-probe.log")).open("x") as log:
        subprocess.run([runtime.java, "-Djava.io.tmpdir=/fyoung/tmp", "--class-path", classpath, str(source),
                        str(runtime.directory / "data/agentflow"), str(output)], stdout=log, stderr=subprocess.STDOUT, check=True, timeout=40)
    return json.loads(output.read_text())


def finish(runtime, wave, model, legacy=False):
    completed = {}
    for item in wave:
        replay = runtime.call("POST", item["path"], item["body"], item["user"], 202, key=item["key"])
        assert replay == item["receipt"] and runtime.client.records[-1]["replayed"] == "true"
        value = wait_for(lambda: runtime.call("GET", item["path"] + "/" + item["receipt"]["id"], user=item["user"]),
                         lambda result: result["status"] not in ("QUEUED", "RUNNING"), 50)
        assert value["status"] == "COMPLETED", (item["kind"], value)
        trace = item["sourceTrace"]
        if legacy:
            raw = TABLES[item["kind"]][1] + "\0demo\0" + item["receipt"]["id"]
            trace = str(UUID(bytes=hashlib.md5(raw.encode()).digest(), version=3))
            assert trace != item["sourceTrace"]
        calls = [request for request in model.requests if request["traceId"] == trace]
        assert len(calls) == 1 and calls[0]["kind"] == item["kind"], (item["kind"], trace, calls)
        completed[item["kind"]] = value
    return completed


def run(args):
    directory = Path(args.output)
    assert str(directory).startswith("/fyoung/tmp/") and directory.resolve().is_relative_to(Path("/fyoung/tmp").resolve()) and not directory.exists()
    directory.mkdir(); old, new = Path(args.previous_jar).resolve(), Path(args.current_jar).resolve()
    evidence = {"status": "RUNNING", "database": "H2", "previousJarSha256": digest(old), "jarSha256": digest(new),
                "browserVerified": False, "postgresqlVerified": False, "realEnterpriseModelVerified": False, "checks": []}
    save(directory / "evidence.json", evidence)
    sources, model = risk.Sources(directory), Model(directory)
    idp = runtime = restored = None
    try:
        idp = risk.IdentityProvider(args.java, directory, new)
        runtime = runtime_for(args.java, directory / "runtime", sources, idp, model)
        runtime.start(old); fixture = risk.setup(runtime, sources); business_before = risk.business_state(runtime, fixture)
        definition = basic_definition(runtime, fixture); legacy_wave = queue_wave(runtime, sources, fixture, definition, "legacy")
        save(directory / "legacy-wave.json", legacy_wave); assert not model.requests
        runtime.stop(); baseline = split.columns_snapshot(runtime, idp.h2, "before-upgrade")
        legacy_rows = probe(runtime, idp, "before-upgrade")
        runtime.start(new); runtime.stop()
        upgraded = split.columns_snapshot(runtime, idp.h2, "after-upgrade", runtime.directory / "before-upgrade-columns.tsv")
        for table, facts in baseline["tables"].items():
            if table != "flyway_schema_history":
                assert facts["rows"] == upgraded["tables"][table]["rows"]
                assert facts["sha256"] == upgraded["tables"][table]["originalColumnsSha256"], table
        upgraded_rows = probe(runtime, idp, "after-upgrade")
        for kind, (table, _) in TABLES.items():
            assert upgraded["columns"][table.upper()] == baseline["columns"][table.upper()] + ["TRACE_ID"]
            assert len(upgraded_rows[table]) == 1
            for identity, row in upgraded_rows[table].items():
                assert row.pop("trace_id") is None and row == legacy_rows[table][identity]
        evidence["checks"].append({"name": "nonempty-upgrade", "tables": len(baseline["tables"]), "legacyQueues": 6, "inventedOrigins": 0})
        runtime.settings["agentflow.invoices.extraction-worker-enabled"] = True
        runtime.start(new, worker=True); legacy_results = finish(runtime, legacy_wave, model, legacy=True)
        assert risk.business_state(runtime, fixture) == business_before and len(model.requests) == 6
        runtime.stop(); runtime.settings["agentflow.invoices.extraction-worker-enabled"] = False
        runtime.start(new); wave = queue_wave(runtime, sources, fixture, definition, "current")
        save(directory / "current-wave.json", wave)
        assert len(model.requests) == 6
        runtime.stop(force=True); queued_rows = probe(runtime, idp, "after-queued-kill")
        for item in wave:
            row = queued_rows[TABLES[item["kind"]][0]][item["receipt"]["id"]]
            assert row["trace_id"] == item["sourceTrace"]
        runtime.settings["agentflow.invoices.extraction-worker-enabled"] = True
        runtime.start(new, worker=True); results = finish(runtime, wave, model)
        assert len(model.requests) == 12 and risk.business_state(runtime, fixture) == business_before
        runtime.stop(); final_rows = probe(runtime, idp, "completed")
        for item in legacy_wave + wave:
            table = TABLES[item["kind"]][0]; identity = item["receipt"]["id"]
            before, after = queued_rows[table][identity], final_rows[table][identity]
            assert before["trace_id"] == after["trace_id"]
            for field in ("context_json", "requester_json", "sources_json", "target_digest"):
                if field in before: assert before[field] == after[field]
        evidence["checks"].append({"name": "six-workers-after-process-restart", "legacyStableTraces": 6, "persistedRequestTraces": 6,
                                   "actualModelRequests": 12, "originalKeyReplays": 12, "originalApprovalsUnchanged": True})
        backup = split.columns_snapshot(runtime, idp.h2, "before-restore")
        restored = runtime_for(args.java, directory / "restored", sources, idp, model)
        for folder in ("data", "attachments"):
            shutil.copytree(runtime.directory / folder, restored.directory / folder)
        restored.settings.update({"agentflow.expenses.precheck-worker-enabled": False, "agentflow.budgets.worker-enabled": False,
                                  "agentflow.invoices.verification-worker-enabled": False})
        restored.start(new); restored.stop()
        recovered = split.columns_snapshot(restored, idp.h2, "after-restore")
        assert recovered == backup
        assert probe(restored, idp, "after-restore") == final_rows
        restored.start(new)
        for actor in risk.ROLES: restored.client.login(actor)
        for collection, expected in ((legacy_wave, legacy_results), (wave, results)):
            for item in collection:
                value = restored.call("GET", item["path"] + "/" + item["receipt"]["id"], user=item["user"])
                assert value == expected[item["kind"]]
        assert len(model.requests) == 12 and not model.errors and not sources.errors and not sources.base.errors
        restored.stop()
        for folder in (runtime.directory, restored.directory):
            for log in folder.glob("runtime-*.log"):
                assert SENTRY not in log.read_text()
        evidence["checks"].append({"name": "independent-restore", "tables": len(backup["tables"]), "sameQueueRows": 12, "sameAuthorizedDetails": 12, "resentModelRequests": 0})
        evidence.update(status="PASS", boots=runtime.starts + restored.starts, modelRequests=len(model.requests),
                        businessHttpRequests=len(runtime.client.records) + len(restored.client.records),
                        responseTraces=len(runtime.trace_records) + len(restored.trace_records))
        save(directory / "evidence.json", evidence); print(json.dumps(evidence, ensure_ascii=False), flush=True)
    finally:
        if restored: restored.stop()
        if runtime: runtime.stop()
        if idp: idp.close()
        model.close(); sources.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True); parser.add_argument("--previous-jar", required=True)
    parser.add_argument("--current-jar", required=True); parser.add_argument("--output", required=True)
    run(parser.parse_args())
