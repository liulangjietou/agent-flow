#!/usr/bin/env python3
"""独立安装包的费用解释验收：旧库升级、真实 HTTP、原键恢复与进程强退。"""

import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import tempfile
import threading
import time
from http.client import RemoteDisconnected
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.request import ProxyHandler, Request, build_opener
from uuid import uuid4
import zipfile


def save(path, value):
    """证据只保存合成业务内容，不记录认证令牌。"""
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def instant(seconds=0):
    return (datetime.now(timezone.utc) + timedelta(seconds=seconds)).isoformat()


def wait_for(read, accept, seconds=20):
    """仅轮询当前已存在的任务；超时保留原任务而不重新创建。"""
    until = time.monotonic() + seconds
    while True:
        value = read()
        if accept(value):
            return value
        if time.monotonic() >= until:
            raise AssertionError("Existing operation did not reach expected state: " + str(value))
        time.sleep(0.15)


class Sources:
    """合成财务和模型仅监听回环，模型可暂扣回执以形成明确的中断窗口。"""

    def __init__(self, directory):
        self.directory, self.entity = directory, None
        self.budget, self.model = "BLOCKED", "NORMAL"
        self.valid_seconds = 600
        self.calls, self.model_calls, self.errors = [], [], []
        self.entered, self.release, self.returned = threading.Event(), threading.Event(), threading.Event()
        self.release.set()
        self.lock = threading.Lock()
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                try:
                    request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                    if self.path == "/model":
                        content = json.loads(request["messages"][1]["content"])
                        assert set(content) == {"sources", "issueSourceIds"}
                        mode = fixture.model
                        with fixture.lock:
                            fixture.model_calls.append({"mode": mode, "at": instant(), "body": request})
                            save(directory / "model-requests.json", fixture.model_calls)
                        fixture.entered.set()
                        if mode in ("HOLD", "LATE"):
                            fixture.release.wait(40)
                        refs = {s["reference"]["sourceId"]: s["reference"] for s in content["sources"]}
                        items = [{"issueSourceId": identity, "explanation": "请按原检查问题核对费用依据。",
                                  "corrections": ["核对原检查来源并按费用页面补正后重新预检。"], "evidence": [refs[identity]]}
                                 for identity in content["issueSourceIds"]]
                        if mode == "FORGED":
                            items[0]["evidence"] = [{"sourceId": items[0]["issueSourceId"], "contentDigest": "f" * 64}]
                        result = {"model": "synthetic-explanation-v1", "choices": [{"finish_reason": "stop", "message": {
                            "role": "assistant", "content": json.dumps({"items": items}, ensure_ascii=False)}}]}
                        status = 200
                    else:
                        assert self.path.startswith("/finance/") and request["tenantId"] == "demo" and request["contractVersion"] == 1
                        operation = self.path.rsplit("/", 1)[1]
                        with fixture.lock:
                            fixture.calls.append({"operation": operation, "request": request})
                            save(directory / "finance-requests.json", fixture.calls)
                        result, status = fixture.finance(operation, request)
                    raw = json.dumps(result, ensure_ascii=False).encode()
                    self.send_response(status)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(raw)))
                    self.end_headers()
                    self.wfile.write(raw)
                except (BrokenPipeError, ConnectionResetError):
                    pass
                except Exception as error:
                    fixture.errors.append(str(error))
                    save(directory / "fixture-errors.json", fixture.errors)
                    self.send_error(500)
                finally:
                    if self.path == "/model":
                        fixture.returned.set()

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def finance(self, operation, request):
        """明确返回合成拒绝或暂不可用，不构造任何付款、预算冻结或批准事实。"""
        data = request["data"]
        envelope = {"contractVersion": 1, "tenantId": "demo", "requestId": request["requestId"], "outcome": "SUCCESS"}
        if operation == "catalog":
            value = {"employeeId": data["employeeId"], "sourceVersion": "synthetic-v1", "validUntil": instant(self.valid_seconds),
                     "legalEntities": [{"id": self.entity, "name": "解释验收法人", "baseCurrency": "CNY", "paperReceiptRequired": False, "sourceVersion": "v1", "timeZone": "UTC"}],
                     "categories": [{"code": "OFFICE", "name": "办公费", "units": ["ITEM"]}], "costCenters": [{"legalEntityId": self.entity, "code": "IT", "name": "合成成本中心"}],
                     "projects": [], "cities": [{"code": "SH", "name": "上海"}]}
        elif operation == "employee-account":
            value = {"snapshot": {"legalEntityId": self.entity, "employeeId": data["employeeId"], "accountReference": "synthetic-private-account", "maskedAccount": "****1234", "accountDigest": "a" * 64, "sourceVersion": "v1"}, "validUntil": instant(self.valid_seconds)}
        elif operation == "exchange-rate":
            assert data["fromCurrency"] == data["toCurrency"] == "CNY"
            value = {"fromCurrency": "CNY", "toCurrency": "CNY", "rate": 1, "source": "synthetic-rate", "rateDate": data["rateDate"]}
        elif operation == "expense-policy":
            assert not data.get("managedPolicy")
            value = {"policy": {"policyId": "00000000-0000-4000-8000-000000000001", "version": 1,
                      "assessedGross": data["line"]["claimedGross"], "allowedGross": data["line"]["claimedGross"], "decision": "WITHIN_LIMIT",
                      "taxRuleReference": "synthetic-tax", "evidenceReference": "synthetic-policy"},
                     "deductibleTax": data["line"]["claimedTax"], "priorRequestRequired": False, "validUntil": instant(self.valid_seconds)}
        elif operation == "budget-precheck":
            if self.budget == "BLOCKED":
                return {**envelope, "outcome": "REJECTED", "reason": "BUDGET_INSUFFICIENT"}, 200
            if self.budget == "UNAVAILABLE":
                return {"synthetic": "budget unavailable"}, 503
            value = {"request": data, "reference": "synthetic-budget", "checkedAt": instant(-1), "validUntil": instant(self.valid_seconds)}
        else:
            raise AssertionError("Unexpected synthetic finance operation: " + operation)
        return {**envelope, "data": value}, 200

    def hold(self, mode):
        self.model = mode
        self.entered.clear()
        self.release.clear()
        self.returned.clear()

    def close(self):
        self.release.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)


class Runtime:
    """只启动和停止本脚本拥有的进程，固定配置、端口和数据库保持跨次启动一致。"""

    def __init__(self, java, directory, sources):
        self.java, self.directory, self.sources = java, directory, sources
        self.process, self.starts, self.tokens, self.records, self.losses = None, 0, {}, [], []
        self.http = build_opener(ProxyHandler({}))
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            self.port = probe.getsockname()[1]
        self.settings = {"server.address": "127.0.0.1", "server.port": self.port,
                         "agentflow.attachments.directory": str(directory / "attachments"),
                         "agentflow.auth.demo-enabled": True, "agentflow.sla.reminders-enabled": False,
                         "agentflow.notifications.proxy-reminders-enabled": False, "agentflow.advances.overdue.reminders-enabled": False,
                         "agentflow.events.enabled": False, "agentflow.timers.enabled": False,
                         "agentflow.finance-gateway.enabled": True,
                         "agentflow.finance-gateway.tenants.demo.endpoint": f"http://127.0.0.1:{sources.server.server_port}/finance",
                         "agentflow.finance-gateway.tenants.demo.allow-unauthenticated-loopback": True,
                         "agentflow.finance-gateway.tenants.demo.timeout-seconds": 3,
                         "agentflow.assist.enabled": True, "agentflow.assist.provider-id": "synthetic-local",
                         "agentflow.assist.model": "synthetic-explanation", "agentflow.assist.timeout-seconds": 2,
                         "agentflow.assist.endpoint": f"http://127.0.0.1:{sources.server.server_port}/model",
                         "agentflow.assist.poll-delay-ms": 200, "agentflow.expenses.precheck-poll-delay-ms": 200}
        root = Path(__file__).resolve().parents[1]
        for source in (root / "agentflow-server/src/main/java").rglob("*.java"):
            for name in re.findall(r'@ConditionalOnProperty\(name\s*=\s*"([^"]*worker-enabled)"', source.read_text()):
                self.settings[name] = False
        self.settings["agentflow.expenses.precheck-worker-enabled"] = True

    def start(self, jar, worker=False, login=True):
        assert self.process is None or self.process.poll() is not None
        self.starts += 1
        self.settings["agentflow.assist.worker-enabled"] = worker
        config = self.directory / f"runtime-{self.starts}.yaml"
        save(config, self.settings)
        environment = {k: v for k, v in os.environ.items() if not k.startswith(("AGENTFLOW_", "SPRING_", "SERVER_"))}
        with (self.directory / f"runtime-{self.starts}.log").open("x") as log:
            self.process = subprocess.Popen([self.java, "-Xmx512m", "-Djava.io.tmpdir=/fyoung/tmp", "-jar", str(jar),
                "--spring.config.location=classpath:/application.yml,file:" + str(config)], cwd=self.directory, env=environment, stdout=log, stderr=subprocess.STDOUT)
        save(self.directory / "owned-process.json", {"pid": self.process.pid, "backendPort": self.port, "boot": self.starts, "jarSha256": digest(jar)})
        until = time.monotonic() + 90
        while True:
            assert self.process.poll() is None, "Owned runtime exited before readiness"
            try:
                self.call("GET", "/auth/options", user=None)
                break
            except (URLError, ConnectionError):
                assert time.monotonic() < until, "Owned runtime readiness timeout"
                time.sleep(0.2)
        if login:
            self.login()
        print(json.dumps({"stage": "RUNTIME_READY", "boot": self.starts, "worker": worker}), flush=True)

    def login(self):
        self.tokens = {user: self.call("POST", "/auth/login", {"tenantId": "demo", "username": user, "password": "demo"}, user=None)["token"]
                       for user in ("admin", "alice", "bob", "finance")}

    def stop(self, force=False):
        if self.process is not None and self.process.poll() is None:
            self.process.kill() if force else self.process.terminate()
            try:
                self.process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=10)

    def call(self, method, path, body=None, user="alice", expected=200, key=None, raw=None):
        headers = {"Content-Type": "application/octet-stream" if raw is not None else "application/json"}
        if user is not None:
            headers["Authorization"] = "Bearer " + self.tokens[user]
        if method != "GET" and not path.startswith("/auth/"):
            headers["Idempotency-Key"] = key or str(uuid4())
        payload = raw if raw is not None else None if body is None else json.dumps(body, ensure_ascii=False).encode()
        request = Request(f"http://127.0.0.1:{self.port}/api/v1" + path, method=method, headers=headers, data=payload)
        try:
            response = self.http.open(request, timeout=15)
        except HTTPError as error:
            response = error
        with response:
            content = response.read()
            value = json.loads(content) if "application/json" in response.headers.get("Content-Type", "") else content
            if not path.startswith("/auth/"):
                record = {"method": method, "path": path, "actor": user, "status": response.status, "request": body,
                          "response": value if not isinstance(value, bytes) else {"sha256": hashlib.sha256(value).hexdigest(), "size": len(value)},
                          "key": headers.get("Idempotency-Key"), "cacheControl": response.headers.get("Cache-Control"), "replayed": response.headers.get("Idempotency-Replayed")}
                self.records.append(record)
                save(self.directory / "http-records.json", self.records)
            assert response.status == expected, (method, path, response.status, value)
            return value

    def lose_response(self, path, body, key, expected):
        """回环代理收齐已提交回执后断开客户端，不给客户端返回状态或正文。"""
        runtime = self
        observed = {"path": path, "key": key, "request": body}
        done = threading.Event()

        class LossHandler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                try:
                    raw = self.rfile.read(int(self.headers["Content-Length"]))
                    request = Request(f"http://127.0.0.1:{runtime.port}/api/v1" + path, method="POST", data=raw,
                                      headers={name: self.headers[name] for name in ("Content-Type", "Authorization", "Idempotency-Key")})
                    try:
                        response = runtime.http.open(request, timeout=15)
                    except HTTPError as error:
                        response = error
                    with response:
                        receipt = response.read()
                        observed.update(upstreamStatus=response.status, receipt=json.loads(receipt),
                                        requestSha256=hashlib.sha256(raw).hexdigest(), responseSha256=hashlib.sha256(receipt).hexdigest())
                except Exception as error:
                    observed["proxyError"] = str(error)
                finally:
                    self.close_connection = True
                    try:
                        self.connection.shutdown(socket.SHUT_RDWR)
                    except OSError:
                        pass
                    finally:
                        self.connection.close()
                        done.set()

        server = ThreadingHTTPServer(("127.0.0.1", 0), LossHandler)
        server.daemon_threads = True
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            request = Request(f"http://127.0.0.1:{server.server_port}/api/v1" + path, method="POST",
                              data=json.dumps(body, ensure_ascii=False).encode(), headers={"Content-Type": "application/json",
                              "Authorization": "Bearer " + self.tokens["alice"], "Idempotency-Key": key})
            try:
                with self.http.open(request, timeout=20) as response:
                    response.read()
                raise AssertionError("Lost response unexpectedly reached client")
            except (RemoteDisconnected, ConnectionResetError, URLError) as error:
                observed["clientFailure"] = type(error).__name__
            assert done.wait(5), "Response-loss proxy did not finish"
            assert "proxyError" not in observed and observed.get("upstreamStatus") == expected, observed
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)
            self.losses.append(observed)
            save(self.directory / "lost-responses.json", self.losses)


def snapshot(runtime, h2, name):
    """应用停服后逐表逐列计算摘要，包含二进制与空值；不跳过空表或历史表。"""
    assert runtime.process.poll() is not None
    source = runtime.directory / "SnapshotH2.java"
    if not source.exists():
        source.write_text('''import java.sql.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
/** 离线全表摘要供安装包升级对照。@author owlzhangfq@gmail.com */
class SnapshotH2 {
  public static void main(String[] args) throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:h2:file:" + args[0] + ";IFEXISTS=TRUE;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0;REUSE_SPACE=FALSE", "sa", "")) {
      var tables = new TreeMap<String,String>();
      try (var names = connection.getMetaData().getTables(null, "PUBLIC", "%", new String[]{"BASE TABLE"})) {
        while (names.next()) {
          String table = names.getString("TABLE_NAME"); var rows = new ArrayList<String>();
          try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT * FROM \\\"" + table.replace("\\\"", "\\\"\\\"") + "\\\"")) {
            while (result.next()) {
              var row = new StringBuilder();
              for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) {
                int type = result.getMetaData().getColumnType(i);
                byte[] bytes = type == Types.BINARY || type == Types.VARBINARY || type == Types.LONGVARBINARY || type == Types.BLOB
                    ? result.getBytes(i) : result.getString(i) == null ? null : result.getString(i).getBytes(StandardCharsets.UTF_8);
                row.append(bytes == null ? "-" : Base64.getEncoder().encodeToString(bytes)).append(';');
              }
              rows.add(row.toString());
            }
          }
          Collections.sort(rows); var hash = MessageDigest.getInstance("SHA-256");
          for (String row : rows) hash.update((row + "\\n").getBytes(StandardCharsets.UTF_8));
          tables.put(table, rows.size() + "\\t" + HexFormat.of().formatHex(hash.digest()));
        }
      }
      var lines = new ArrayList<String>(); tables.forEach((key,value) -> lines.add(key + "\\t" + value));
      Files.write(Path.of(args[1]), lines);
    }
  }
}
''')
    output = runtime.directory / (name + ".tsv")
    subprocess.run([runtime.java, "-Djava.io.tmpdir=/fyoung/tmp", "--class-path", str(h2), str(source), str(runtime.directory / "data/agentflow"), str(output)], check=True, timeout=60)
    result = {}
    for line in output.read_text().splitlines():
        table, count, value = line.split("\t")
        result[table] = {"rows": int(count), "sha256": value}
    save(runtime.directory / (name + ".json"), result)
    return result


def setup(runtime, sources):
    """所有费用、组织、票据和在审申请均通过旧安装包公开接口创建。"""
    runtime.call("POST", "/organization/initialize", {}, "admin", 201)
    unit = runtime.call("POST", "/organization/units", {"kind": "LEGAL_ENTITY", "name": "解释验收法人", "active": True}, "admin", 201)
    sources.entity = unit["id"]
    department = runtime.call("POST", "/organization/units", {"kind": "DEPARTMENT", "name": "解释验收部门", "legalEntityId": unit["id"], "active": True}, "admin", 201)
    position = runtime.call("POST", "/organization/units", {"kind": "POSITION", "name": "解释验收岗位", "legalEntityId": unit["id"], "active": True}, "admin", 201)
    person = runtime.call("POST", "/organization/people", {"subject": "alice", "displayName": "解释申请人", "active": True, "approvalEligible": False}, "admin", 201)
    appointment = runtime.call("POST", "/organization/appointments", {"personId": person["id"], "departmentId": department["id"], "positionId": position["id"], "active": True}, "admin", 201)
    finance = runtime.call("POST", "/organization/people", {"subject": "finance", "displayName": "解释验收审批人", "active": True, "approvalEligible": True}, "admin", 201)
    runtime.call("POST", "/organization/appointments", {"personId": finance["id"], "departmentId": department["id"], "positionId": position["id"], "active": True}, "admin", 201)
    draft = runtime.call("POST", "/process-templates/expense-report/copy", {"key": "explanation-expense", "name": "解释验收报销", "templateVersion": 1}, "admin")
    for node in draft["graph"]["nodes"]:
        if node["type"] == "USER_TASK":
            node["properties"]["assigneeRule"] = "role:ORG_PERSON_" + finance["id"]
    draft = runtime.call("PUT", "/process-definitions/" + draft["id"], {"name": draft["name"], "graph": draft["graph"], "formSchema": draft["formSchema"], "notificationTexts": draft.get("notificationTexts", {}), "expectedRevision": draft["revision"]}, "admin")
    definition = runtime.call("POST", "/process-definitions/" + draft["id"] + "/publish?expectedRevision=" + str(draft["revision"]), {"changeNote": "本地合成费用预检解释验收"}, "admin")
    content = {"legalEntityId": unit["id"], "title": "不得发送的合成费用标题", "type": "DAILY", "advanceOffsets": [], "lines": [
        {"lineNo": 1, "categoryCode": "OFFICE", "incurredOn": datetime.now(timezone.utc).date().isoformat(), "cityCode": "SH", "quantity": 1, "unit": "ITEM",
         "claimedGross": {"value": "100.00", "currency": "CNY"}, "claimedTax": {"value": "0.00", "currency": "CNY"}, "invoiceIds": [],
         "allocations": [{"costCenter": "IT", "amount": {"value": "100.00", "currency": "CNY"}}], "description": "不得发送的费用说明"}]}
    report = runtime.call("POST", "/expense-reports", {"businessNo": "EXPLANATION-LEGACY", "processKey": definition["key"], "definitionVersion": definition["version"], "content": content}, expected=201)
    original = b"%PDF-1.7\nsynthetic original retained across upgrade\n%%EOF"
    invoice = runtime.call("POST", "/invoices", {"filename": "合成原件.pdf", "size": len(original), "sha256": hashlib.sha256(original).hexdigest(), "format": "PDF"}, expected=201)
    runtime.call("PUT", "/invoices/" + invoice["id"] + "/content", raw=original)
    graph = {"nodes": [{"id": "start", "name": "开始", "type": "START", "properties": {}}, {"id": "review", "name": "人工审批", "type": "USER_TASK", "properties": {"assigneeRule": "role:ORG_PERSON_" + finance["id"]}}, {"id": "end", "name": "结束", "type": "END", "properties": {}}],
             "edges": [{"id": "first", "source": "start", "target": "review", "condition": "", "defaultBranch": False}, {"id": "last", "source": "review", "target": "end", "condition": "", "defaultBranch": False}]}
    human = runtime.call("POST", "/process-definitions", {"key": "explanation-original-human", "name": "原在审保留", "graph": graph, "formSchema": {"schemaVersion": 2, "fields": []}}, "admin")
    human = runtime.call("POST", "/process-definitions/" + human["id"] + "/publish?expectedRevision=" + str(human["revision"]), {"changeNote": "升级前保留人工在审"}, "admin")
    application = runtime.call("POST", "/applications", {"businessNo": "EXPLANATION-PENDING", "processKey": human["key"], "definitionVersion": human["version"], "title": "升级前在审", "payload": {}}, expected=201)
    runtime.call("POST", "/applications/" + application["id"] + "/submit", {"expectedVersion": application["version"]})
    return {"report": report, "appointmentId": appointment["id"], "invoiceId": invoice["id"], "originalSha256": hashlib.sha256(original).hexdigest(), "applicationId": application["id"]}


def precheck(runtime, fixture):
    report = runtime.call("GET", "/expense-reports/" + fixture["report"]["id"])
    path = "/expense-reports/" + report["id"]
    available = runtime.call("GET", path + "/precheck-options")
    receipt = runtime.call("POST", path + "/precheck", {"applicationVersion": report["applicationVersion"], "financialVersion": report["financialVersion"],
        "initiatorAppointmentId": fixture["appointmentId"], "accountingDate": datetime.now(timezone.utc).date().isoformat(), "targetDigest": available["targetDigest"]}, expected=202)
    return wait_for(lambda: runtime.call("GET", path + "/prechecks/" + receipt["id"]), lambda value: value["job"]["status"] not in ("QUEUED", "RUNNING"))


def generation(runtime, fixture, check):
    path = "/expense-reports/" + fixture["report"]["id"] + "/precheck-explanations"
    options = runtime.call("GET", path + "/input?precheckId=" + check["job"]["id"])
    assert options["enabled"], options
    source_ids = [s["reference"]["sourceId"] for s in options["sources"] if s["reference"]["sourceId"].startswith("precheck:")]
    return path, {"precheckId": options["precheckId"], "applicationVersion": options["applicationVersion"], "financialVersion": options["financialVersion"],
                  "targetDigest": options["targetDigest"], "sourceIds": source_ids}


def verify_expiry_and_late_result(runtime, sources, fixture, result):
    """模型请求已经超时或依据真实到期时，迟到正文不能变成可采纳结果。"""
    sources.budget = "BLOCKED"
    path, body = generation(runtime, fixture, precheck(runtime, fixture))
    count = len(sources.model_calls)
    sources.hold("LATE")
    queued = runtime.call("POST", path, body, expected=202)
    assert sources.entered.wait(10)
    failed = wait_for(lambda: runtime.call("GET", path + "/" + queued["id"]), lambda value: value["status"] == "FAILED")
    assert failed["failure"] == "MODEL_TIMEOUT" and failed["suggestion"] is None
    sources.release.set()
    assert sources.returned.wait(5)
    assert runtime.call("GET", path + "/" + queued["id"]) == failed
    assert len(sources.model_calls) == count + 1
    result["lateModelResult"] = {"runId": queued["id"], "failure": "MODEL_TIMEOUT", "calls": 1, "suggestion": None, "returnedAfterFailure": True}

    sources.model, sources.valid_seconds = "NORMAL", 10
    path, body = generation(runtime, fixture, precheck(runtime, fixture))
    queued = runtime.call("POST", path, body, expected=202)
    completed = wait_for(lambda: runtime.call("GET", path + "/" + queued["id"]), lambda value: value["status"] == "COMPLETED")
    assert completed["canAdopt"]
    expired = wait_for(lambda: runtime.call("GET", path + "/" + queued["id"]), lambda value: not value["canAdopt"], 15)
    assert expired["unavailableCode"] == "FACTS_EXPIRED" and expired["suggestion"] == completed["suggestion"]
    runtime.call("POST", path + "/" + queued["id"] + "/review", {"expectedRunVersion": 3, "action": "ADOPT", "selectedIssueIds": body["sourceIds"][1:]}, expected=409)
    dismissed = runtime.call("POST", path + "/" + queued["id"] + "/review", {"expectedRunVersion": 3, "action": "DISMISS"})
    assert dismissed["status"] == "DISMISSED"
    runtime.call("GET", path + "/" + queued["id"])
    result["realExpiry"] = {"runId": queued["id"], "validUntil": completed["validUntil"], "canAdoptBefore": True, "canAdoptAfter": False,
                            "unavailableCode": expired["unavailableCode"], "staleAdoptionHttpStatus": 409, "dismissed": True, "clockModified": False}
    sources.valid_seconds = 600


def verify_queued_revision(runtime, sources, fixture, fixed, result):
    """费用通过原入口修改后，重启领取旧队列应先失败，不能向模型发送旧授权内容。"""
    runtime.stop()
    runtime.start(fixed)
    path, body = generation(runtime, fixture, precheck(runtime, fixture))
    count = len(sources.model_calls)
    queued = runtime.call("POST", path, body, expected=202)
    report = runtime.call("GET", "/expense-reports/" + fixture["report"]["id"])
    content = {**report["content"], "title": "排队后由本人明确修改的费用"}
    revised = runtime.call("POST", "/expense-reports/" + report["id"] + "/revise", {
        "applicationVersion": report["applicationVersion"], "financialVersion": report["financialVersion"], "content": content})
    assert revised["financialVersion"] == report["financialVersion"] + 1
    runtime.stop(force=True)
    runtime.start(fixed, worker=True)
    failed = wait_for(lambda: runtime.call("GET", path + "/" + queued["id"]), lambda value: value["status"] == "FAILED")
    assert failed["failure"] == "INPUT_UNAVAILABLE" and failed["suggestion"] is None
    assert len(sources.model_calls) == count
    result["queuedRevision"] = {"runId": queued["id"], "failure": "INPUT_UNAVAILABLE", "modelCalls": 0,
                                "originalFinancialVersion": report["financialVersion"], "revisedFinancialVersion": revised["financialVersion"]}


def run(java, legacy, fixed, directory, result):
    sources = Sources(directory)
    runtime = Runtime(java, directory, sources)
    with zipfile.ZipFile(fixed) as archive:
        entry = next(name for name in archive.namelist() if re.fullmatch(r"BOOT-INF/lib/h2-[^/]+\.jar", name))
        h2 = directory / "h2.jar"
        h2.write_bytes(archive.read(entry))
    try:
        runtime.start(legacy)
        fixture = setup(runtime, sources)
        old_check = precheck(runtime, fixture)
        assert old_check["job"]["status"] == "BLOCKED", old_check
        save(directory / "legacy-fixture.json", fixture)
        runtime.stop()
        before = snapshot(runtime, h2, "before-upgrade")
        runtime.start(fixed, login=False)
        runtime.stop()
        after = snapshot(runtime, h2, "after-upgrade")
        changed = [name for name in before if before[name] != after.get(name)]
        assert changed == ["flyway_schema_history"], changed
        added = sorted(set(after) - set(before))
        assert added == ["AGENT_PRECHECK_EXPLANATION_RUN", "AGENT_PRECHECK_EXPLANATION_TRANSITION"], added
        result["upgrade"] = {"preservedTables": len(before) - 1, "preservedRows": sum(v["rows"] for k, v in before.items() if k != "flyway_schema_history"), "changedTables": changed, "newTables": added}
        runtime.start(fixed)
        assert runtime.call("GET", "/expense-reports/" + fixture["report"]["id"]) == fixture["report"]
        raw = runtime.call("GET", "/invoices/" + fixture["invoiceId"] + "/content")
        assert hashlib.sha256(raw).hexdigest() == fixture["originalSha256"]
        old_options = runtime.call("GET", "/expense-reports/" + fixture["report"]["id"] + "/precheck-explanations/input?precheckId=" + old_check["job"]["id"])
        assert not old_options["enabled"] and old_options["unavailableCode"] == "PRECHECK_EXPLANATION_REFRESH_REQUIRED", old_options
        check = precheck(runtime, fixture)
        path, body = generation(runtime, fixture, check)
        for user in ("admin", "bob", "finance"):
            runtime.call("GET", path, user=user, expected=404)
            runtime.call("POST", path, body, user, 404)
        runtime.call("POST", path, {**body, "sourceIds": ["precheck:result"]}, expected=422)
        runtime.call("POST", path, {**body, "sourceIds": [*body["sourceIds"], "form:secret"]}, expected=403)
        key = str(uuid4())
        runtime.lose_response(path, body, key, 202)
        runtime.call("POST", path, body, expected=409)
        assert not sources.model_calls
        runtime.stop(force=True)
        runtime.start(fixed, worker=True)
        receipt = runtime.call("POST", path, body, expected=202, key=key)
        assert receipt == runtime.losses[0]["receipt"] and runtime.records[-1]["replayed"] == "true"
        detail = wait_for(lambda: runtime.call("GET", path + "/" + receipt["id"]), lambda value: value["status"] == "COMPLETED")
        assert detail["canAdopt"] and detail["result"] == "BLOCKED" and len(sources.model_calls) == 1
        sent = sources.model_calls[0]["body"]["messages"][1]["content"]
        assert all(value not in sent for value in ("不得发送", "synthetic-private-account", fixture["report"]["id"], "alice", "expense:line[1]"))
        for user in ("admin", "bob", "finance"):
            runtime.call("GET", path + "/" + receipt["id"], user=user, expected=404)
        review = {"expectedRunVersion": 3, "action": "ADOPT", "selectedIssueIds": [body["sourceIds"][1]], "comment": "核对原来源后采纳"}
        review_key = str(uuid4())
        runtime.lose_response(path + "/" + receipt["id"] + "/review", review, review_key, 200)
        assert runtime.call("GET", "/expense-reports/" + fixture["report"]["id"]) == fixture["report"]
        assert runtime.call("GET", "/expense-reports/" + fixture["report"]["id"] + "/prechecks/" + check["job"]["id"])["job"] == check["job"]
        runtime.stop(force=True)
        runtime.start(fixed, worker=True)
        adopted = runtime.call("POST", path + "/" + receipt["id"] + "/review", review, key=review_key)
        assert adopted["status"] == "ADOPTED" and adopted == runtime.losses[1]["receipt"] and runtime.records[-1]["replayed"] == "true"
        runtime.call("GET", path + "/" + receipt["id"])
        assert len(sources.model_calls) == 1
        result["queuedRestart"] = {"runId": receipt["id"], "modelCalls": 1, "originalGenerationReplay": True, "originalReviewReplay": True,
                                   "financialStateUnchanged": True, "realLostResponses": len(runtime.losses)}

        check = precheck(runtime, fixture)
        path, body = generation(runtime, fixture, check)
        sources.hold("HOLD")
        running = runtime.call("POST", path, body, expected=202)
        assert sources.entered.wait(10)
        assert runtime.call("GET", path + "/" + running["id"])["status"] == "RUNNING"
        runtime.stop(force=True)
        sources.release.set()
        runtime.start(fixed, worker=True)
        failed = wait_for(lambda: runtime.call("GET", path + "/" + running["id"]), lambda value: value["status"] == "FAILED", 45)
        assert failed["failure"] == "MODEL_TIMEOUT" and len(sources.model_calls) == 2 and failed["suggestion"] is None
        result["runningRestart"] = {"runId": running["id"], "failure": failed["failure"], "modelCalls": 1, "resends": 0}

        sources.model = "FORGED"
        check = precheck(runtime, fixture)
        path, body = generation(runtime, fixture, check)
        forged = runtime.call("POST", path, body, expected=202)
        rejected = wait_for(lambda: runtime.call("GET", path + "/" + forged["id"]), lambda value: value["status"] == "FAILED")
        assert rejected["failure"] == "INVALID_MODEL_OUTPUT" and rejected["suggestion"] is None
        sources.model = "NORMAL"
        for outcome in ("READY", "UNAVAILABLE"):
            sources.budget = outcome
            check = precheck(runtime, fixture)
            assert check["job"]["status"] == outcome, check
            path, body = generation(runtime, fixture, check)
            queued = runtime.call("POST", path, body, expected=202)
            completed = wait_for(lambda: runtime.call("GET", path + "/" + queued["id"]), lambda value: value["status"] == "COMPLETED")
            assert completed["result"] == outcome and completed["canAdopt"]
            precheck(runtime, fixture)
            stale = runtime.call("GET", path + "/" + queued["id"])
            assert not stale["canAdopt"] and stale["unavailableCode"] == "PRECHECK_SUPERSEDED"
            runtime.call("POST", path + "/" + queued["id"] + "/review", {"expectedRunVersion": 3, "action": "ADOPT", "selectedIssueIds": body["sourceIds"][1:] or ["precheck:result"]}, expected=409)
            dismissed = runtime.call("POST", path + "/" + queued["id"] + "/review", {"expectedRunVersion": 3, "action": "DISMISS", "comment": "原依据已被新预检替代"})
            assert dismissed["status"] == "DISMISSED"
        result["outcomes"] = ["BLOCKED", "READY", "UNAVAILABLE"]
        result["forgedEvidenceRejected"] = True
        result["supersededCannotAdoptCanDismiss"] = True
        verify_expiry_and_late_result(runtime, sources, fixture, result)
        verify_queued_revision(runtime, sources, fixture, fixed, result)
        page = runtime.call("GET", path)
        assert page["total"] == 8 and len(page["items"]) == 8 and page["page"] == 0 and page["pageSize"] == 20
        for item in page["items"]:
            runtime.call("GET", path + "/" + item["id"])
        application = runtime.call("GET", "/applications/" + fixture["applicationId"])
        assert application["status"] == "IN_APPROVAL"
        tasks = [t for t in runtime.call("GET", "/tasks", user="finance") if t["applicationId"] == application["id"]]
        assert len(tasks) == 1
        runtime.call("POST", "/tasks/" + tasks[0]["taskId"] + "/actions", {"action": "APPROVE", "expectedVersion": application["version"], "comment": "确认旧在审申请升级后接续"}, "finance")
        assert runtime.call("GET", "/applications/" + application["id"])["status"] == "APPROVED"
        assert not sources.errors, sources.errors
        result.update(originalHumanApprovalContinued=True, originalFilePreserved=True, httpResponses=len(runtime.records), boots=runtime.starts, modelRequests=len(sources.model_calls), result="PASS")
    finally:
        runtime.stop()
        sources.close()
        result["ownedRuntimeStopped"] = runtime.process is None or runtime.process.poll() is not None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True)
    parser.add_argument("--legacy-jar", type=Path, required=True)
    parser.add_argument("--jar", type=Path, required=True)
    args = parser.parse_args()
    directory = Path(tempfile.mkdtemp(prefix="agentflow-explanation-runtime-", dir="/fyoung/tmp"))
    result = {"directory": str(directory), "synthetic": True, "result": "IN_PROGRESS", "legacyJarSha256": digest(args.legacy_jar), "jarSha256": digest(args.jar)}
    print(json.dumps(result), flush=True)
    try:
        run(args.java, args.legacy_jar.resolve(), args.jar.resolve(), directory, result)
    except Exception as error:
        result.update(result="FAIL", error=str(error))
        raise
    finally:
        save(directory / "result.json", result)
        print(json.dumps(result), flush=True)


if __name__ == "__main__":
    main()
