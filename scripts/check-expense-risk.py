#!/usr/bin/env python3
"""固定安装包验收：非空升级、真实 OIDC/HTTP、原键恢复、强退和配套备份恢复。"""

import argparse
import copy
import hashlib
from http.client import RemoteDisconnected
from http.cookiejar import CookieJar
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlparse
from urllib.request import build_opener, HTTPCookieProcessor, HTTPRedirectHandler, ProxyHandler, Request
from uuid import uuid4
import zipfile


ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("precheck_runtime", ROOT / "scripts/check-precheck-explanation.py")
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)
save, digest, instant, wait_for = common.save, common.digest, common.instant, common.wait_for
ROLES = {"admin": {"ADMIN", "PROCESS_ADMIN"}, "alice": {"EMPLOYEE"},
         "manager": {"EMPLOYEE", "APPROVER"}, "finance": {"EMPLOYEE", "APPROVER", "FINANCE"}, "bob": {"EMPLOYEE"}}
RISK_TABLES = {"AGENT_EXPENSE_RISK_RUN", "AGENT_EXPENSE_RISK_DOCUMENT", "AGENT_EXPENSE_RISK_TRANSITION"}


def free_port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def stage(name, **facts):
    print(json.dumps({"stage": name, **facts}, ensure_ascii=False), flush=True)


class IdentityProvider:
    """编译现有合成身份夹具；不接触外部 IdP、现有应用进程或真实凭据。"""

    def __init__(self, java, directory, jar):
        self.process = None
        libraries, classes = directory / "libraries", directory / "classes"
        libraries.mkdir(); classes.mkdir()
        with zipfile.ZipFile(jar) as archive:
            for name in archive.namelist():
                if name.startswith("BOOT-INF/lib/") and name.endswith(".jar"):
                    (libraries / Path(name).name).write_bytes(archive.read(name))
                elif name.startswith("BOOT-INF/classes/") and not name.endswith("/"):
                    destination = classes / name.removeprefix("BOOT-INF/classes/")
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    destination.write_bytes(archive.read(name))
        self.h2 = next(libraries.glob("h2-*.jar"))
        sources = [ROOT / "scripts/fixtures/ExpenseRiskOidcFixture.java",
                   ROOT / "agentflow-server/src/test/java/io/agentflow/auth/OidcTestProvider.java"]
        for source in sources:
            shutil.copy2(source, directory / source.name)
        classpath = str(classes) + os.pathsep + str(libraries / "*")
        with (directory / "idp-compile.log").open("x") as log:
            subprocess.run([str(Path(java).with_name("javac")), "-J-Djava.io.tmpdir=/fyoung/tmp", "-cp", classpath,
                            "-d", str(classes), *map(str, sources)], check=True, stdout=log, stderr=subprocess.STDOUT, timeout=45)
        metadata = directory / "idp.json"
        with (directory / "idp.log").open("x") as log:
            self.process = subprocess.Popen([java, "-Djava.io.tmpdir=/fyoung/tmp", "-cp", classpath,
                "io.agentflow.auth.ExpenseRiskOidcFixture", str(metadata)], stdout=log, stderr=subprocess.STDOUT)
        try:
            wait_for(lambda: (self.process.poll(), metadata.exists()), lambda item: item == (None, True), 35)
        except BaseException:
            self.close()
            raise
        self.info = json.loads(metadata.read_text())
        save(directory / "idp-provenance.json", {"pid": self.process.pid,
             "sources": {str(source.relative_to(ROOT)): digest(source) for source in sources}})

    def close(self):
        if self.process is not None and self.process.poll() is None:
            self.process.terminate(); self.process.wait(timeout=15)


class Sources:
    """组合既有预检财务夹具，补充幂等预算、验票和四类风险解释的回环协议。"""

    def __init__(self, directory):
        self.base = common.Sources(directory)
        self.base.budget = "READY"; self.base.valid_seconds = 3600
        self.directory, self.model_calls, self.finance_calls, self.errors = directory, [], [], []
        self.commands, self.invoices = {}, {}
        self.mode = "VALID"
        self.entered, self.release = threading.Event(), threading.Event()
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                try:
                    request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                    if self.path == "/model":
                        content = json.loads(request["messages"][1]["content"])
                        assert set(content) == {"sources", "concerns"}
                        mode = fixture.mode
                        fixture.model_calls.append({"mode": mode, "at": instant(), "body": request})
                        save(directory / "model-requests.json", fixture.model_calls)
                        fixture.entered.set()
                        if mode == "HOLD":
                            fixture.release.wait(60)
                        evidence = [source["reference"] for source in content["sources"]]
                        items = [{"concernSourceId": concern["sourceId"], "kind": concern["kind"],
                                  "explanation": "合成解释：所选费用事实需要人工核对", "limitations": "不证明违规或规避审批",
                                  "checks": ["核对原始业务用途及票据"], "evidence": evidence} for concern in content["concerns"]]
                        result = {"model": "synthetic-risk-v1", "choices": [{"finish_reason": "stop", "message": {
                            "role": "assistant", "content": json.dumps({"items": items}, ensure_ascii=False)}}]}
                        status = 200
                    else:
                        assert self.path.startswith("/finance/") and request["tenantId"] == "demo" and request["contractVersion"] == 1
                        operation = self.path.rsplit("/", 1)[1]
                        fixture.finance_calls.append({"operation": operation, "request": request})
                        save(directory / "finance-requests.json", fixture.finance_calls)
                        result, status = fixture.finance(operation, request)
                    raw = json.dumps(result, ensure_ascii=False).encode()
                    self.send_response(status); self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(raw))); self.end_headers(); self.wfile.write(raw)
                except (BrokenPipeError, ConnectionResetError):
                    pass
                except Exception as error:
                    fixture.errors.append(repr(error)); save(directory / "fixture-errors.json", fixture.errors)
                    self.send_error(500)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True); self.thread.start()

    def finance(self, operation, request):
        data = request["data"]
        envelope = {"contractVersion": 1, "tenantId": "demo", "requestId": request["requestId"], "outcome": "SUCCESS"}
        if operation == "budget-command":
            command, fingerprint = data["command"], data["commandDigest"]
            identifier = command["id"]
            if identifier not in self.commands:
                expected = command.get("expected")
                self.commands[identifier] = {"command": command, "observation": {"operationId": identifier,
                    "commandDigest": fingerprint, "status": "APPLIED", "ledgerRevision": 1 if expected is None else expected["revision"] + 1,
                    "reference": "synthetic-budget-" + identifier, "appliedAt": instant(-1)}}
            saved = self.commands[identifier]
            assert saved["command"] == command and saved["observation"]["commandDigest"] == fingerprint
            value = saved["observation"]
        elif operation == "budget-query":
            saved = self.commands.get(data["operationId"])
            value = saved["observation"] if saved else {**data, "status": "NOT_FOUND"}
            assert value["commandDigest"] == data["commandDigest"]
        elif operation == "invoice-verification":
            identifier = data["originalDigest"]
            self.invoices.setdefault(identifier, str(10000000000000000000 + len(self.invoices)))
            value = {"key": {"type": "DIGITAL", "number": self.invoices[identifier]}, "legalEntityId": self.base.entity,
                     "gross": {"value": "100", "currency": "CNY"}, "tax": {"value": "6", "currency": "CNY"},
                     "issueDate": instant()[:10], "originalDigest": identifier, "reference": "synthetic-invoice",
                     "verifiedAt": instant(-1), "validUntil": instant(3600)}
        else:
            return self.base.finance(operation, request)
        return {**envelope, "data": value}, 200

    def hold(self):
        self.mode = "HOLD"; self.entered.clear(); self.release.clear()

    def resume(self):
        self.mode = "VALID"; self.release.set()

    def close(self):
        self.resume(); self.server.shutdown(); self.server.server_close(); self.thread.join(timeout=5); self.base.close()


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class Client:
    """原 Cookie 留在内存；重启不能静默换登录或写请求键，证据不保存凭据。"""

    def __init__(self, runtime, idp):
        self.runtime, self.idp, self.sessions, self.identities, self.records, self.losses = runtime, idp, {}, {}, [], []

    def login(self, actor):
        http = build_opener(ProxyHandler({}))
        with http.open(Request(self.idp.info["control"] + "/actor?subject=" + actor, method="POST"), timeout=10) as response:
            assert json.load(response)["configured"]
        jar = CookieJar(); opener = build_opener(ProxyHandler({}), HTTPCookieProcessor(jar), NoRedirect())
        self.sessions[actor] = opener, jar
        url = self.runtime.base + "/auth/oidc/authorize/enterprise"
        for index in range(3):
            try:
                response = opener.open(Request(url), timeout=15)
            except HTTPError as error:
                response = error
            with response:
                assert response.status == 302, ("OIDC redirect", index, response.status)
                target = response.headers["Location"]
            expected = urlparse(self.idp.info["issuer"] if index == 0 else self.runtime.base)
            parsed = urlparse(target)
            assert (parsed.scheme, parsed.netloc) == (expected.scheme, expected.netloc)
            if index == 2:
                assert parsed.path == "/"
            url = target
        me = self.call("GET", "/auth/me", user=actor)["actor"]
        assert me["tenantId"] == "demo" and me["userId"] == actor and set(me["roles"]) == ROLES[actor]
        cookies = [cookie for cookie in jar if cookie.name == "AGENTFLOW_SESSION"]
        assert len(cookies) == 1 and "HttpOnly" in cookies[0]._rest and cookies[0]._rest.get("SameSite") == "Lax"
        self.identities[actor] = {"roles": me["roles"], "cookieSha256": hashlib.sha256(cookies[0].value.encode()).hexdigest()}
        save(self.runtime.directory / "identities.json", self.identities)

    def request(self, method, path, body, user, key, raw):
        headers = {"Content-Type": "application/octet-stream" if raw is not None else "application/json",
                   "X-AgentFlow-Actor": quote(json.dumps(["demo", user], separators=(",", ":")), safe="")}
        if method not in ("GET", "HEAD", "OPTIONS"):
            options = self.call("GET", "/auth/options", user=user)
            assert options["mode"] == "OIDC"
            headers[options["csrfHeader"]] = options["csrfToken"]
            if not path.startswith("/auth/") and not path.endswith("/risk-explanations/input"):
                headers["Idempotency-Key"] = key or str(uuid4())
        payload = raw if raw is not None else None if body is None else json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode()
        return Request(self.runtime.base + path, method=method, headers=headers, data=payload)

    def call(self, method, path, body=None, user="alice", expected=200, key=None, raw=None):
        request = self.request(method, path, body, user, key, raw)
        try:
            response = self.sessions[user][0].open(request, timeout=25)
        except HTTPError as error:
            response = error
        with response:
            content = response.read()
            value = json.loads(content) if "application/json" in response.headers.get("Content-Type", "") and content else content
            if not path.startswith("/auth/"):
                self.records.append({"method": method, "path": "/api/v1" + path, "actor": user, "boot": self.runtime.starts,
                    "status": response.status, "request": body, "response": value if not isinstance(value, bytes) else {"sha256": hashlib.sha256(value).hexdigest(), "size": len(value)},
                    "key": request.get_header("Idempotency-key"), "cacheControl": response.headers.get("Cache-Control"),
                    "replayed": response.headers.get("Idempotency-Replayed")})
                save(self.runtime.directory / "http-records.json", self.records)
            assert response.status in (expected if isinstance(expected, tuple) else (expected,)), (method, path, response.status, value)
            return value

    def lose_response(self, path, body, key, expected, user="manager"):
        """接收上游完整成功回执后切断 TCP；随后只能使用原始正文和原键恢复。"""
        request = self.request("POST", path, body, user, key, None)
        self.sessions[user][1].add_cookie_header(request)
        observed = {}
        client = self

        class DropResponse(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                try:
                    assert self.rfile.read(int(self.headers["Content-Length"])) == request.data
                    with build_opener(ProxyHandler({})).open(request, timeout=25) as upstream:
                        observed.update(status=upstream.status, response=json.loads(upstream.read()))
                    assert observed["status"] == expected
                except Exception as error:
                    observed["error"] = repr(error)
                finally:
                    self.close_connection = True
                    self.connection.shutdown(socket.SHUT_RDWR); self.connection.close()

        server = ThreadingHTTPServer(("127.0.0.1", 0), DropResponse)
        thread = threading.Thread(target=server.handle_request, daemon=True); thread.start()
        try:
            try:
                with build_opener(ProxyHandler({})).open(Request("http://127.0.0.1:" + str(server.server_port), data=request.data), timeout=30):
                    raise AssertionError("Client unexpectedly received a response")
            except (RemoteDisconnected, ConnectionResetError) as error:
                observed["clientFailure"] = type(error).__name__
        finally:
            thread.join(timeout=5); server.server_close()
        assert "error" not in observed and observed.get("status") == expected and observed.get("clientFailure"), observed
        client.losses.append({"path": path, "actor": user, "key": key, "request": body, **observed})
        save(self.runtime.directory / "lost-responses.json", self.losses)
        return observed["response"]


class Runtime:
    """只管理本次创建的应用；配置和数据目录固定，健康探测不创建认证会话。"""

    def __init__(self, java, directory, sources, idp):
        directory.mkdir()
        self.java, self.directory, self.process, self.starts = java, directory, None, 0
        self.port = free_port(); self.base = f"http://127.0.0.1:{self.port}/api/v1"
        # 组合既有配置构造，避免遗漏其他后台财务或通知任务的禁用项。
        settings = common.Runtime(java, directory, sources).settings
        settings.update({"server.port": self.port, "agentflow.auth.demo-enabled": False, "agentflow.auth.oidc.enabled": True,
            "agentflow.auth.session.jdbc-enabled": True, "agentflow.auth.oidc.issuer": idp.info["issuer"],
            "agentflow.auth.oidc.client-id": "platform", "agentflow.auth.oidc.client-secret": "fixture-secret",
            "agentflow.auth.oidc.tenant-claim": "tenant", "agentflow.auth.oidc.roles-claim": "roles",
            "agentflow.auth.oidc.tenant-mappings.external": "demo", "agentflow.auth.oidc.allow-insecure-loopback": True,
            "agentflow.auth.oidc.role-mappings.staff[0]": "EMPLOYEE", "agentflow.auth.oidc.role-mappings.admins[0]": "ADMIN",
            "agentflow.auth.oidc.role-mappings.designers[0]": "PROCESS_ADMIN", "agentflow.auth.oidc.role-mappings.reviewers[0]": "APPROVER",
            "agentflow.auth.oidc.role-mappings.accountants[0]": "FINANCE", "agentflow.web.allowed-origin": self.base.removesuffix("/api/v1"),
            "agentflow.budgets.worker-enabled": True, "agentflow.budgets.poll-delay-ms": 200,
            "agentflow.invoices.verification-worker-enabled": True, "agentflow.invoices.verification-poll-delay-ms": 200})
        self.settings = settings
        self.client = Client(self, idp)
        self.call = self.client.call

    def start(self, jar, worker=False):
        assert self.process is None or self.process.poll() is not None
        self.starts += 1; self.settings["agentflow.assist.worker-enabled"] = worker
        config = self.directory / f"runtime-{self.starts}.yaml"; save(config, self.settings)
        environment = {key: value for key, value in os.environ.items() if not key.startswith(("AGENTFLOW_", "SPRING_", "SERVER_"))}
        with (self.directory / f"runtime-{self.starts}.log").open("x") as log:
            self.process = subprocess.Popen([self.java, "-Xmx512m", "-Djava.io.tmpdir=/fyoung/tmp", "-jar", str(jar),
                "--spring.config.location=classpath:/application.yml,file:" + str(config)], cwd=self.directory, env=environment, stdout=log, stderr=subprocess.STDOUT)
        save(self.directory / "owned-process.json", {"pid": self.process.pid, "port": self.port, "boot": self.starts, "jarSha256": digest(jar)})
        until = time.monotonic() + 90
        http = build_opener(ProxyHandler({}))
        while True:
            assert self.process.poll() is None, "Owned runtime exited before readiness"
            try:
                with http.open(self.base.removesuffix("/api/v1") + "/actuator/health/readiness", timeout=3) as response:
                    assert json.load(response)["status"] == "UP"
                break
            except (URLError, ConnectionError):
                assert time.monotonic() < until, "Owned runtime readiness timeout"
                time.sleep(.2)
        stage("RUNTIME_READY", boot=self.starts, worker=worker, directory=self.directory.name)

    def stop(self, force=False):
        if self.process is not None and self.process.poll() is None:
            self.process.kill() if force else self.process.terminate()
            try:
                self.process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                self.process.kill(); self.process.wait(timeout=10)


def setup(runtime, sources):
    """全部旧库业务通过 V114 公开接口建立，包含真实在审、冻结和已验票原件。"""
    call = runtime.call
    for actor in ROLES:
        runtime.client.login(actor)
    call("POST", "/organization/initialize", {}, "admin", 201)
    entity = call("POST", "/organization/units", {"kind": "LEGAL_ENTITY", "name": "风险验收法人", "active": True}, "admin", 201)
    sources.base.entity = entity["id"]
    department = call("POST", "/organization/units", {"kind": "DEPARTMENT", "name": "风险验收部门", "legalEntityId": entity["id"], "active": True}, "admin", 201)
    position = call("POST", "/organization/units", {"kind": "POSITION", "name": "风险验收岗位", "legalEntityId": entity["id"], "active": True}, "admin", 201)
    people, appointments = {}, {}
    for actor in ("alice", "manager", "finance"):
        person = call("POST", "/organization/people", {"subject": actor, "displayName": "合成" + actor, "active": True,
                      "approvalEligible": actor != "alice"}, "admin", 201)
        people[actor] = person["id"]
        appointments[actor] = call("POST", "/organization/appointments", {"personId": person["id"], "departmentId": department["id"], "positionId": position["id"], "active": True}, "admin", 201)["id"]
    ids = ["start", "business", "receipt", "finance", "end"]
    graph = {"nodes": [{"id": name, "name": name, "type": "START" if name == "start" else "END" if name == "end" else "USER_TASK",
        "properties": {} if name in ("start", "end") else {"assigneeRule": "role:ORG_PERSON_" + people["manager" if name == "business" else "finance"],
            **({"expenseStage": "RECEIPT" if name == "receipt" else "FINANCE_REVIEW"} if name != "business" else {})}} for name in ids],
        "edges": [{"id": "edge" + str(index), "source": source, "target": target, "condition": "", "defaultBranch": False} for index, (source, target) in enumerate(zip(ids, ids[1:]))]}
    fields = [{"key": key, "label": key, "type": kind, "required": True} for key, kind in (("amount", "NUMBER"), ("currency", "TEXT"), ("overPolicy", "BOOLEAN"))]
    fields.insert(0, {"key": "expenseDetails", "label": "费用明细", "type": "TEXT", "required": True, "sensitive": True,
                      "nodeAccess": {name: "READ_ONLY" for name in ("business", "receipt", "finance")}})
    definition = call("POST", "/process-definitions", {"key": "risk-runtime", "name": "风险运行包验收", "graph": graph, "formSchema": {"schemaVersion": 2, "fields": fields}}, "admin")
    definition = call("POST", "/process-definitions/" + definition["id"] + "/publish?expectedRevision=" + str(definition["revision"]), {"changeNote": "本地合成运行包验收"}, "admin")
    invoices = []
    for index in range(3):
        original = ("%PDF-1.7\nsynthetic invoice " + str(index) + "\n%%EOF").encode()
        invoice = call("POST", "/invoices", {"filename": "合成原件.pdf", "size": len(original), "sha256": hashlib.sha256(original).hexdigest(), "format": "PDF"}, expected=201)
        path = "/invoices/" + invoice["id"]
        call("PUT", path + "/content", raw=original)
        options = call("GET", path + "/verification-options")
        queued = call("POST", path + "/verifications", {"expectedInvoiceVersion": options["invoiceVersion"], "legalEntityId": entity["id"], "targetDigest": options["targetDigest"]}, expected=202)
        result = wait_for(lambda: call("GET", path + "/verifications/" + queued["id"]), lambda value: value["status"] not in ("QUEUED", "RUNNING"))
        assert result["status"] == "SUCCEEDED", result
        invoices.append(invoice["id"])
    reports = []
    for index, selected in enumerate((invoices[:2], invoices[2:])):
        money = {"value": "100", "currency": "CNY"}
        content = {"legalEntityId": entity["id"], "type": "DAILY", "title": "不可发送的私人费用标题", "advanceOffsets": [], "lines": [
            {"lineNo": line + 1, "categoryCode": "OFFICE", "incurredOn": instant()[:10], "cityCode": "SH", "quantity": 1, "unit": "ITEM",
             "claimedGross": money, "claimedTax": {"value": "6", "currency": "CNY"}, "invoiceIds": [invoice],
             "allocations": [{"costCenter": "IT", "amount": money}], "description": "不可发送的私人说明"} for line, invoice in enumerate(selected)]}
        report = call("POST", "/expense-reports", {"businessNo": "RISK-LEGACY-" + str(index), "processKey": definition["key"], "definitionVersion": definition["version"], "content": content}, expected=201)
        fixture = {"report": report, "appointmentId": appointments["alice"]}
        check = common.precheck(runtime, fixture)
        assert check["job"]["status"] == "READY", check
        report = call("GET", "/expense-reports/" + report["id"])
        call("POST", "/expense-reports/" + report["id"] + "/submit", {"applicationVersion": report["applicationVersion"], "financialVersion": report["financialVersion"], "precheckId": check["job"]["id"]})
        wait_for(lambda: tasks(runtime, report["applicationId"]), bool)
        reports.append(call("GET", "/expense-reports/" + report["id"]))
    # 当天明确休息，另外一天保留合法工作时段，避免测试依赖执行日的星期。
    rules = {"zoneId": "UTC", "weeklyHours": {"MONDAY": [{"start": "09:00", "end": "18:00"}]}, "overrides": [{"date": instant()[:10], "periods": []}]}
    calendar = call("POST", "/business-calendars", {"key": "RISK_RUNTIME", "name": "合成日历", "rules": rules}, "admin", 201)
    return {"reports": reports, "invoices": invoices, "calendar": calendar, "people": people, "appointments": appointments}


def tasks(runtime, application):
    return [item for item in runtime.call("GET", "/tasks", user="manager") if item["applicationId"] == application]


def business_state(runtime, fixture):
    return [{"report": runtime.call("GET", "/expense-reports/" + report["id"]),
             "application": runtime.call("GET", "/applications/" + report["applicationId"]),
             "tasks": tasks(runtime, report["applicationId"])} for report in fixture["reports"]]


def generation(runtime, fixture):
    primary, comparison = fixture["reports"]
    path = "/expense-reports/" + primary["id"] + "/risk-explanations"
    scope = {"documents": [{"reportId": primary["id"], "roundNo": 1, "lineNos": [1, 2]},
                            {"reportId": comparison["id"], "roundNo": 1, "lineNos": [1]}], "calendarId": fixture["calendar"]["id"]}
    body = {"taskId": tasks(runtime, primary["applicationId"])[0]["taskId"], "scope": scope}
    calendars = runtime.call("GET", path + "/calendars?roundNo=1&taskId=" + body["taskId"], user="manager")
    assert calendars["nextAfterKey"] is None and any(item["id"] == scope["calendarId"] for item in calendars["items"])
    preview = runtime.call("POST", path + "/input", body, "manager")
    assert preview["enabled"], preview
    assert {item["kind"] for item in preview["concerns"]} == {"SAME_DAY", "CROSS_DOCUMENT", "NON_WORKING_DAY", "CONSECUTIVE_INVOICES"}
    return path, {**body, "inputDigest": preview["inputDigest"], "targetDigest": preview["targetDigest"],
                  "sourceIds": [source["reference"]["sourceId"] for source in preview["sources"]]}


def result(runtime, path, identifier, status):
    return wait_for(lambda: runtime.call("GET", path + "/" + identifier, user="manager"), lambda value: value["status"] == status, 60)


def run(args):
    directory = Path(args.output)
    assert str(directory).startswith("/fyoung/tmp/") and not directory.exists(), "Use a fresh owned temporary directory"
    directory.mkdir(parents=True)
    before_jar, after_jar = Path(args.before).resolve(), Path(args.after).resolve()
    source_manifest = {}
    for name in ("scripts/check-expense-risk.py", "scripts/check-precheck-explanation.py", "scripts/fixtures/ExpenseRiskOidcFixture.java",
                 "agentflow-server/src/test/java/io/agentflow/auth/OidcTestProvider.java"):
        source, saved = ROOT / name, directory / "harness-source" / name
        saved.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(source, saved)
        source_manifest[name] = digest(source)
    save(directory / "harness-manifest.json", source_manifest)
    sources = Sources(directory)
    idp, runtime, restored = None, None, None
    evidence = {"status": "RUNNING", "startedAt": instant(), "beforeSha256": digest(before_jar), "afterSha256": digest(after_jar),
                "database": "H2", "identity": "loopback OIDC with JDBC sessions", "browserVerified": False, "postgresqlVerified": False, "checks": []}
    save(directory / "evidence.json", evidence)
    try:
        idp = IdentityProvider(args.java, directory, after_jar)
        runtime = Runtime(args.java, directory / "original", sources, idp)
        runtime.start(before_jar)
        fixture = setup(runtime, sources); save(directory / "fixture.json", fixture)
        before_business = business_state(runtime, fixture)
        runtime.stop(); before = common.snapshot(runtime, idp.h2, "before-upgrade")
        assert not RISK_TABLES.intersection(before)
        runtime.start(after_jar); runtime.stop()
        after = common.snapshot(runtime, idp.h2, "after-upgrade")
        changed = {name for name in before if before[name] != after[name]}
        added = set(after) - set(before)
        assert changed == {"flyway_schema_history"} and added == RISK_TABLES, (changed, added)
        evidence["checks"].append({"name": "nonempty-upgrade", "oldTables": len(before), "changedTables": sorted(changed), "addedTables": sorted(added)})
        stage("UPGRADE_VERIFIED", oldTables=len(before), addedTables=len(added))

        runtime.start(after_jar)
        assert business_state(runtime, fixture) == before_business
        path, body = generation(runtime, fixture)
        initial_calls = len(sources.model_calls)
        for actor in ("alice", "admin", "finance", "bob"):
            runtime.call("POST", path + "/input", {"taskId": body["taskId"], "scope": body["scope"]}, actor, (403, 404))
        assert len(sources.model_calls) == initial_calls
        key = str(uuid4())
        receipt = runtime.client.lose_response(path, body, key, 202)
        assert runtime.call("POST", path, body, "manager", 202, key) == receipt
        assert runtime.client.records[-1]["replayed"] == "true"
        assert len(sources.model_calls) == initial_calls
        original_cookie = runtime.client.identities["manager"]["cookieSha256"]
        runtime.stop(force=True); runtime.start(after_jar, worker=True)
        assert runtime.call("GET", "/auth/me", user="manager")["actor"]["userId"] == "manager"
        assert runtime.client.identities["manager"]["cookieSha256"] == original_cookie
        completed = result(runtime, path, receipt["id"], "COMPLETED")
        assert len(sources.model_calls) == initial_calls + 1 and completed["adoptable"]
        review = {"expectedRunVersion": completed["version"], "action": "ADOPT", "selectedConcernIds": [item["sourceId"] for item in completed["concerns"]], "comment": "核对所选原依据"}
        review_key = str(uuid4())
        adopted = runtime.client.lose_response(path + "/" + receipt["id"] + "/review", review, review_key, 200)
        assert adopted["status"] == "ADOPTED"
        assert runtime.call("POST", path + "/" + receipt["id"] + "/review", review, "manager", 200, review_key) == adopted
        assert runtime.client.records[-1]["replayed"] == "true"
        assert business_state(runtime, fixture) == before_business
        evidence["checks"].append({"name": "queued-restart-original-session-and-lost-receipts", "runId": receipt["id"], "modelCalls": 1, "cookieSha256": original_cookie})
        stage("QUEUED_RESTART_AND_RECOVERY_VERIFIED")

        sources.hold()
        path, held_body = generation(runtime, fixture)
        held = runtime.call("POST", path, held_body, "manager", 202)
        assert sources.entered.wait(10), "No held model request"
        assert runtime.call("GET", path + "/" + held["id"], user="manager")["status"] == "RUNNING"
        held_calls = len(sources.model_calls)
        runtime.stop(force=True); sources.resume(); runtime.start(after_jar, worker=True)
        failed = result(runtime, path, held["id"], "FAILED")
        assert failed["failure"] == "MODEL_TIMEOUT" and len(sources.model_calls) == held_calls, failed
        assert business_state(runtime, fixture) == before_business
        evidence["checks"].append({"name": "running-crash-no-resend", "runId": held["id"], "failure": failed["failure"], "modelCalls": 1})
        stage("RUNNING_CRASH_VERIFIED")

        runtime.stop(); runtime.start(after_jar)
        path, revoked_body = generation(runtime, fixture)
        revoked = runtime.call("POST", path, revoked_body, "manager", 202)
        old_cookie = runtime.client.identities["manager"]["cookieSha256"]
        runtime.call("POST", "/auth/logout", {}, "manager", (200, 204))
        runtime.client.login("manager")
        assert runtime.client.identities["manager"]["cookieSha256"] != old_cookie
        revoked_calls = len(sources.model_calls)
        runtime.stop(); runtime.start(after_jar, worker=True)
        unavailable = result(runtime, path, revoked["id"], "FAILED")
        assert unavailable["failure"] == "INPUT_UNAVAILABLE" and len(sources.model_calls) == revoked_calls, unavailable
        evidence["checks"].append({"name": "original-logout-cannot-be-replaced", "runId": revoked["id"], "modelCalls": 0})
        stage("ORIGINAL_LOGOUT_VERIFIED")

        path, stale_body = generation(runtime, fixture)
        stale = runtime.call("POST", path, stale_body, "manager", 202)
        result(runtime, path, stale["id"], "COMPLETED")
        calendar = fixture["calendar"]
        runtime.call("PUT", "/business-calendars/" + calendar["id"], {"name": "合成日历修订", "rules": calendar["rules"], "expectedRevision": calendar["revision"]}, "admin")
        runtime.call("POST", path, stale_body, "manager", 409)
        stale_view = runtime.call("GET", path + "/" + stale["id"], user="manager")
        assert stale_view["reviewable"] and not stale_view["adoptable"]
        runtime.call("POST", path + "/" + stale["id"] + "/review", {"expectedRunVersion": stale_view["version"], "action": "ADOPT", "selectedConcernIds": [item["sourceId"] for item in stale_view["concerns"]]}, "manager", 409)
        dismissed = runtime.call("POST", path + "/" + stale["id"] + "/review", {"expectedRunVersion": stale_view["version"], "action": "DISMISS"}, "manager")
        assert dismissed["status"] == "DISMISSED" and business_state(runtime, fixture) == before_business
        evidence["checks"].append({"name": "stale-source-reject-adopt-allow-dismiss", "runId": stale["id"]})
        stage("STALE_SOURCE_VERIFIED")

        history = runtime.call("GET", path + "?roundNo=1", user="manager")
        runtime.stop()
        backup = directory / "backup"; backup.mkdir()
        for name in ("data", "attachments"):
            shutil.copytree(runtime.directory / name, backup / name)
        peer = {"commands": sources.commands, "invoices": sources.invoices, "entity": sources.base.entity}
        save(backup / "synthetic-peer-state.json", peer)
        preserved = common.snapshot(runtime, idp.h2, "before-restore")
        assert history["total"] == 4 and preserved["AGENT_EXPENSE_RISK_RUN"]["rows"] == 4
        assert preserved["AGENT_EXPENSE_RISK_DOCUMENT"]["rows"] == 8 and preserved["AGENT_EXPENSE_RISK_TRANSITION"]["rows"] == 14
        assert len(sources.commands) == 2
        save(backup / "manifest.json", {"jarSha256": digest(after_jar), "configurationSha256": digest(runtime.directory / f"runtime-{runtime.starts}.yaml"),
             "files": {str(source.relative_to(backup)): digest(source) for source in backup.rglob("*") if source.is_file()}})
        restored = Runtime(args.java, directory / "restored", sources, idp)
        for name in ("data", "attachments"):
            shutil.copytree(backup / name, restored.directory / name)
        # 离线摘要要求终止句柄；此处引用已退出原进程，不生成占位服务。
        restored.process = runtime.process
        assert common.snapshot(restored, idp.h2, "restored-before-start") == preserved
        for source in (backup / "attachments").rglob("*"):
            if source.is_file():
                assert digest(source) == digest(restored.directory / "attachments" / source.relative_to(backup / "attachments"))
        recovered_peer = json.loads((backup / "synthetic-peer-state.json").read_text())
        sources.commands, sources.invoices, sources.base.entity = recovered_peer["commands"], recovered_peer["invoices"], recovered_peer["entity"]
        restored.start(after_jar, worker=True)
        restored.client.sessions = runtime.client.sessions
        restored.client.identities = copy.deepcopy(runtime.client.identities)
        assert restored.call("GET", "/auth/me", user="manager")["actor"]["userId"] == "manager"
        assert restored.call("GET", path + "?roundNo=1", user="manager") == history
        assert business_state(restored, fixture) == before_business
        for index, invoice in enumerate(fixture["invoices"]):
            original = ("%PDF-1.7\nsynthetic invoice " + str(index) + "\n%%EOF").encode()
            assert restored.call("GET", "/invoices/" + invoice + "/content") == original
        primary = fixture["reports"][0]
        application = restored.call("GET", "/applications/" + primary["applicationId"])
        task = tasks(restored, primary["applicationId"])[0]
        restored.call("POST", "/tasks/" + task["taskId"] + "/actions", {"action": "APPROVE", "expectedVersion": application["version"], "comment": "恢复后继续原人工审批"}, "manager")
        assert not tasks(restored, primary["applicationId"])
        next_tasks = [item for item in restored.call("GET", "/tasks", user="finance") if item["applicationId"] == primary["applicationId"]]
        assert len(next_tasks) == 1 and next_tasks[0]["taskId"] != task["taskId"]
        assert restored.call("POST", path + "/" + receipt["id"] + "/review", review, "manager", 200, review_key) == adopted
        assert not restored.call("GET", path + "/" + receipt["id"], user="manager")["reviewable"]
        evidence["checks"].append({"name": "paired-restore-and-original-human-approval", "tables": len(preserved), "nextTask": next_tasks[0]["taskId"]})
        stage("RESTORE_AND_APPROVAL_VERIFIED")

        forbidden = [report[key] for report in fixture["reports"] for key in ("id", "applicationId")] + fixture["invoices"] + list(sources.invoices.values())
        forbidden += ["alice", "不可发送", "synthetic-private-account", "originalDigest", "loginReference", "targetDigest"]
        for sent in sources.model_calls:
            raw = json.dumps(sent["body"], ensure_ascii=False)
            assert not any(value in raw for value in forbidden), "Model request leaked unselected identity or raw financial data"
        assert not sources.errors, sources.errors
        evidence.update(status="PASSED", finishedAt=instant(), modelCalls=len(sources.model_calls), httpRecords=len(runtime.client.records) + len(restored.client.records),
                        financeCalls=len(sources.finance_calls), distinctBudgetCommands=len(sources.commands))
    except BaseException as error:
        evidence.update(status="FAILED", finishedAt=instant(), failure=repr(error))
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
        stage("FINISHED", status=evidence["status"], output=str(directory))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", required=True, help="Preserved V114 installation JAR")
    parser.add_argument("--after", required=True, help="Fixed V115 installation JAR")
    parser.add_argument("--java", required=True, help="Absolute Java 17 executable")
    parser.add_argument("--output", required=True, help="Fresh directory under /fyoung/tmp")
    run(parser.parse_args())
