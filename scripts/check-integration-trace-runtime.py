#!/usr/bin/env python3
"""固定包检查六类集成队列的非空升级、原来源追踪、重启和独立恢复。"""

import argparse
import base64
import hashlib
import hmac
import importlib.util
import json
from pathlib import Path
import shutil
import time
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request, build_opener
from uuid import UUID, uuid4

ROOT = Path(__file__).resolve().parents[1]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value)
    return value


finance = module("integration_trace_finance", "scripts/check-finance-trace-runtime.py")
fixtures = module("integration_trace_peers", "scripts/fixtures/IntegrationTracePeers.py")
risk, reporting, split = finance.risk, finance.reporting, finance.split
save, digest, wait_for = finance.save, finance.digest, finance.wait_for
TABLES = {"SERVICE": ("service_task_operation", "service-task"), "SIGNATURE": ("signature_operation", "signature"),
          "ORGANIZATION": ("organization_sync_batch", "organization-sync"), "NOTIFICATION": ("notification_dispatch", "notification-delivery"),
          "EVENT": ("event_inbox", "event-inbox"), "CALLBACK": ("payment_callback", "payment-callback")}
CORE_TABLES = {key: value for key, value in TABLES.items() if key != "NOTIFICATION"}
FLAGS = ("agentflow.service-tasks", "agentflow.signatures", "agentflow.organization-sync", "agentflow.events", "agentflow.payment-callbacks")
SIGNING_KEY = b"synthetic-integration-trace-key-01"


class Client(risk.Client):
    """演示鉴权仅用于显式允许明文的回环 SMTP；登录令牌只留在内存。"""
    def __init__(self, runtime, idp):
        super().__init__(runtime, idp); self.tokens, self.extra_headers = {}, {}

    def login(self, actor):
        self.tokens.pop(actor, None)
        self.sessions[actor] = (build_opener(ProxyHandler({}), finance.tracing.TraceResponse(self.runtime)), None)
        value = self.call("POST", "/auth/login", {"tenantId": "demo", "username": actor, "password": "demo"}, actor)
        self.tokens[actor] = value["token"]

    def request(self, method, path, body, user, key, raw):
        headers = {"Content-Type": "application/octet-stream" if raw is not None else "application/json", **self.extra_headers}
        if user in self.tokens: headers["Authorization"] = "Bearer " + self.tokens[user]
        if method not in ("GET", "HEAD", "OPTIONS") and not path.startswith("/auth/"): headers["Idempotency-Key"] = key or str(uuid4())
        payload = raw if raw is not None else None if body is None else json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode()
        return Request(self.runtime.base + path, method=method, headers=headers, data=payload)


class OidcClient(finance.Client):
    """文件上传补充原申请版本，认证及持久 Cookie 沿用已验证的客户端。"""
    def __init__(self, runtime, idp):
        super().__init__(runtime, idp); self.extra_headers = {}

    def request(self, *args):
        request = super().request(*args)
        for name, value in self.extra_headers.items(): request.add_header(name, value)
        return request


def runtime_for(java, directory, sources, idp, peers, notifications=False):
    runtime = finance.runtime_for(java, directory, sources, idp)
    runtime.client = (Client if notifications else OidcClient)(runtime, idp); runtime.call = runtime.client.call
    settings = runtime.settings
    settings.update({"agentflow.auth.demo-enabled": notifications, "agentflow.auth.oidc.enabled": not notifications, "agentflow.auth.session.jdbc-enabled": not notifications,
        "agentflow.service-tasks.gateway.enabled": True, "agentflow.service-tasks.gateway.tenants.demo": [{
            "key": "trace.register", "version": 1, "name": "合成服务追踪", "enabled": True, "endpoint": peers.base + "/service",
            "token": "synthetic-service-token", "parameters": [{"name": "memo", "type": "TEXT", "required": True, "sensitive": True}]}],
        "agentflow.signatures.gateway.enabled": True, "agentflow.signatures.gateway.tenants.demo": [{
            "key": "trace-seal", "version": 1, "name": "合成签署追踪", "actors": ["alice"],
            "signers": [{"key": "company", "providerSubject": "synthetic-company"}], "receiptPublicKey": peers.public_key,
            "endpoint": peers.base + "/signature", "token": "synthetic-signature-token", "timeoutSeconds": 10}],
        "agentflow.organization-sync.enabled": True, "agentflow.organization-sync.tenants.demo.source-key": "hr",
        "agentflow.organization-sync.tenants.demo.endpoint": peers.base + "/hr", "agentflow.organization-sync.tenants.demo.allow-unauthenticated-loopback": True,
        "agentflow.events.enabled": True, "agentflow.events.sources.erp.tenant-id": "demo", "agentflow.events.sources.erp.source-key": "erp",
        "agentflow.events.sources.erp.enabled": True, "agentflow.events.sources.erp.trust-revision": 1,
        "agentflow.events.sources.erp.signing-secrets[0]": "whsec_" + base64.b64encode(SIGNING_KEY).decode(),
        "agentflow.payment-callbacks.enabled": True, "agentflow.payment-callbacks.tenants.demo.signing-secrets[0]": "whsec_" + base64.b64encode(SIGNING_KEY).decode(),
        "agentflow.notifications.allow-insecure-in-demo": True, "agentflow.notifications.public-url": "http://127.0.0.1/",
        "agentflow.notifications.smtp-servers.mail": {"tenantId": "demo", "host": "127.0.0.1", "port": peers.smtp.server_address[1],
            "security": "DEMO_PLAIN", "from": "synthetic@example.invalid", "enabled": True},
        "agentflow.notifications.wecom-apps.im": {"tenantId": "demo", "corpId": "synthetic-corp", "agentId": 100001,
            "secret": "synthetic-secret", "baseUrl": peers.base, "enabled": True},
        "agentflow.notifications.bindings.mail-manager": {"tenantId": "demo", "recipient": "manager", "channel": "EMAIL", "serverId": "mail", "address": "manager@example.invalid", "enabled": True},
        "agentflow.notifications.bindings.im-manager": {"tenantId": "demo", "recipient": "manager", "channel": "ENTERPRISE_IM", "serverId": "im", "address": "TraceManager", "enabled": True},
        "agentflow.notifications.poll-delay-ms": 200})
    if not notifications:
        for key in list(settings):
            if key.startswith(("agentflow.notifications.smtp-servers", "agentflow.notifications.wecom-apps", "agentflow.notifications.bindings")):
                settings.pop(key)
    for flag in FLAGS: settings[flag + ".poll-delay-ms"] = 200
    workers(runtime, False)
    return runtime


def workers(runtime, enabled):
    for flag in FLAGS: runtime.settings[flag + ".worker-enabled"] = enabled
    runtime.settings["agentflow.notifications.delivery-worker-enabled"] = enabled and isinstance(runtime.client, Client)


def signed(runtime, path, body, event=None):
    raw = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode(); event = event or str(uuid4()); stamp = str(int(time.time()))
    signature = hmac.new(SIGNING_KEY, (event + "." + stamp + ".").encode() + raw, hashlib.sha256).digest()
    headers = {"Content-Type": "application/json", "webhook-tenant": "demo", "webhook-id": event, "webhook-timestamp": stamp,
               "webhook-signature": "v1," + base64.b64encode(signature).decode(), "X-Trace-Id": "untrusted-origin"}
    if path.endswith("/events"): headers["webhook-source"] = "erp"
    opener = build_opener(ProxyHandler({}), finance.tracing.TraceResponse(runtime))
    try: response = opener.open(Request(runtime.base + path, data=raw, headers=headers), timeout=25)
    except HTTPError as error: response = error
    with response:
        value = json.load(response); assert response.status == 202, (path, response.status, value)
    return value, {"path": path, "body": body, "event": event}


def definition(runtime, label, middle, fields=None):
    nodes = [{"id": "start", "name": "开始", "type": "START", "properties": {}}, *middle,
             {"id": "end", "name": "结束", "type": "END", "properties": {}}]
    graph = {"nodes": nodes, "edges": [{"id": "e" + str(index), "source": one["id"], "target": two["id"], "condition": ""}
              for index, (one, two) in enumerate(zip(nodes, nodes[1:]))]}
    value = runtime.call("POST", "/process-definitions", {"key": label + "-" + uuid4().hex, "name": "合成集成追踪", "graph": graph,
                         "formSchema": {"schemaVersion": 2, "fields": fields or []}}, "admin")
    return runtime.call("POST", "/process-definitions/" + value["id"] + "/publish?expectedRevision=" + str(value["revision"]),
                        {"changeNote": "合成追踪验收"}, "admin")


def draft(runtime, definition, payload=None):
    return runtime.call("POST", "/applications", {"businessNo": "TRACE-" + uuid4().hex, "processKey": definition["key"], "definitionVersion": definition["version"],
                        "title": "PRIVATE 合成集成追踪", "payload": payload or {}}, expected=201)


def submit(runtime, value):
    return runtime.call("POST", "/applications/" + value["id"] + "/submit", {"expectedVersion": value["version"], **({"initiatorAppointmentId": runtime.initiator} if hasattr(runtime, "initiator") else {})})


def review(fixture, actor="finance"):
    return {"id": "review", "name": "人工审批", "type": "USER_TASK", "properties": {"assigneeRule": "role:ORG_PERSON_" + fixture["people"][actor]}}


def preferences(runtime, enabled):
    value = runtime.call("GET", "/notifications/preferences", user="manager")
    runtime.call("PUT", "/notifications/preferences", {"expectedVersion": value["version"], "emailEnabled": enabled, "enterpriseImEnabled": enabled}, "manager")


def prepare(runtime, sources, peer, fixture, peers):
    payment_report = split.draft(runtime, fixture, {"key": "risk-runtime", "version": 1}, "100")
    split.submit(runtime, fixture, payment_report)
    for actor in ("manager", "finance", "finance"): split.action(runtime, payment_report, actor)
    authorization = reporting.authorize(runtime, payment_report); path = "/cashier/payments/" + authorization
    accounts = runtime.call("GET", path + "/accounts", user="bob"); account = accounts["items"][0]
    runtime.call("POST", path + "/actions", {"action": "EXECUTE", "authorizationVersion": accounts["authorizationVersion"],
        "debitAccountReference": account["reference"], "debitAccountVersion": account["sourceVersion"], "comment": "合成回调来源付款"}, "bob", 202)
    wait_for(lambda: runtime.call("GET", path, user="bob"), lambda v: (v["payment"].get("operation") or {}).get("status") == "SUCCEEDED", 40)
    reporting.payment_voucher(runtime, payment_report)
    operation = peer["receipts"]["payment:" + authorization]
    fields = [{"key": "contract", "label": "合同原件", "type": "ATTACHMENT", "required": True, "sensitive": True, "nodeAccess": {"review": "READ_ONLY"}}]
    signature = draft(runtime, definition(runtime, "trace-signature", [review(fixture, "manager")], fields)); files = []
    for index in range(2):
        raw = ("%PDF-1.7\nsynthetic original " + uuid4().hex + "\n%%EOF").encode(); path = "/applications/" + signature["id"]
        file = runtime.call("POST", path + "/attachments", {"expectedVersion": signature["version"], "fieldPath": "contract",
                           "filename": "合成合同-" + str(index) + ".pdf", "size": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}, expected=201)
        runtime.client.extra_headers = {"X-Application-Version": str(signature["version"])}
        try: runtime.call("PUT", path + "/attachments/" + file["id"] + "/content", raw=raw)
        finally: runtime.client.extra_headers = {}
        files.append(file["id"])
    signature = runtime.call("PUT", path, {"expectedVersion": signature["version"], "title": signature["title"], "payload": {"contract": files}})
    signature = submit(runtime, signature); task = next(t for t in runtime.call("GET", "/tasks", user="manager") if t["applicationId"] == signature["id"])
    runtime.call("POST", "/tasks/" + task["taskId"] + "/actions", {"expectedVersion": signature["version"], "action": "APPROVE", "comment": "核对合成原件"}, "manager")
    signature = runtime.call("GET", path)
    options = runtime.call("GET", "/process-definitions/service-task-options/trace.register/versions/1", user="admin"); peers.contract_digest = options["contractDigest"]
    middle = [{"id": "service" + str(i), "name": "合成服务", "type": "SERVICE_TASK", "properties": {"serviceOperationKey": "trace.register",
               "serviceOperationVersion": "1", "serviceContractDigest": peers.contract_digest, "serviceInput.memo": "reason"}} for i in (1, 2)]
    service = draft(runtime, definition(runtime, "trace-service", [*middle, review(fixture)], [{"key": "reason", "label": "用途", "type": "TEXT", "required": True}]), {"reason": "PRIVATE synthetic memo"})
    key = "trace-event-" + uuid4().hex
    runtime.call("POST", "/event-contracts/" + key + "/versions", {"expectedVersion": 0, "name": "合成事件", "sourceKey": "erp", "eventType": "TraceReady", "reason": "合成追踪"}, "admin", 201)
    waiting = submit(runtime, draft(runtime, definition(runtime, "trace-event", [{"id": "wait", "name": "等待事件", "type": "EVENT_WAIT",
               "properties": {"eventContractKey": key, "eventContractVersion": "1"}}, review(fixture)])))
    wait = runtime.call("GET", "/applications/" + waiting["id"] + "/rounds/1/event-waits")["items"][0]
    return {"signature": signature, "files": files, "service": service,
            "event": {"envelopeVersion": 1, "tenantId": "demo", "sourceKey": "erp", "eventType": "TraceReady", "applicationId": waiting["id"],
                      "roundNo": 1, "waitId": wait["waitId"], "contractKey": key, "contractVersion": 1},
            "callback": {"contractVersion": 1, "type": "payment.changed", "tenantId": "demo", "kind": "EMPLOYEE", "authorizationId": authorization,
                         "commandDigest": operation["digest"], "sourceRevision": 1}}


def queue(runtime, prepared):
    wave, requests = {}, []
    def add(kind, identity, trace, path, user="alice"):
        wave[kind] = {"id": identity, "sourceTrace": trace, "path": path, "user": user}
    def post(path, body, user="alice", expected=202):
        key = str(uuid4()); value = runtime.call("POST", path, body, user, expected, key=key)
        requests.append({"path": path, "body": body, "user": user, "expected": expected, "key": key, "receipt": value})
        return value, runtime.last_trace
    service = prepared["service"]; _, origin = post("/applications/" + service["id"] + "/submit", {"expectedVersion": service["version"], "initiatorAppointmentId": runtime.initiator}, expected=200)
    path = "/applications/" + service["id"] + "/rounds/1/service-tasks"; value = runtime.call("GET", path)["items"][0]
    add("SERVICE", value["id"], origin, path)
    signature = prepared["signature"]; path = "/applications/" + signature["id"] + "/signatures"
    value, origin = post(path, {"roundNo": signature["roundNo"], "expectedVersion": str(signature["version"]), "profileKey": "trace-seal", "profileVersion": "1",
        "documentIds": prepared["files"], "purpose": "明确授权合成追踪验收", "validUntil": risk.instant(3600)}, expected=201)
    add("SIGNATURE", value["id"], origin, path + "/" + value["id"])
    path = "/organization/synchronization"; status = runtime.call("GET", path, user="admin")
    value, origin = post(path + "/batches", {"expectedSourceVersion": status["sourceVersion"], "targetDigest": status["targetDigest"]}, "admin")
    add("ORGANIZATION", value["id"], origin, path + "/batches/" + value["id"], "admin")
    for kind, path in (("EVENT", "/integrations/events"), ("CALLBACK", "/integrations/payment/callbacks")):
        value, replay = signed(runtime, path, prepared[kind.lower()]); add(kind, value["id"], runtime.last_trace, path + "/" + value["id"], "admin")
        requests.append({"signed": replay, "receipt": value})
    return {"queues": wave, "requests": requests}


def family(kind):
    return "NOTIFICATION" if kind.startswith("NOTIFICATION_") else kind


def expected_trace(kind, item, legacy):
    raw = TABLES[family(kind)][1] + "\0demo\0" + item["id"]
    return str(UUID(bytes=hashlib.md5(raw.encode()).digest(), version=3)) if legacy else item["sourceTrace"]


def finish(runtime, wave, peers, legacy):
    for request in wave["requests"]:
        if "signed" in request:
            value, _ = signed(runtime, **request["signed"])
            assert value["id"] == request["receipt"]["id"]
        else:
            value = runtime.call("POST", request["path"], request["body"], request["user"], request["expected"], key=request["key"])
            assert value == request["receipt"] and runtime.client.records[-1]["replayed"] == "true"
    def completed(kind, value):
        if kind == "SERVICE": return len(value["items"]) == 2 and all(item["progress"] == "ADVANCED" for item in value["items"])
        if kind == "SIGNATURE": return value["operation"]["status"] == "SIGNED"
        if kind == "ORGANIZATION": return value["state"]["status"] in ("RECEIVED", "CANCELLED")
        if kind.startswith("NOTIFICATION_"): return value["delivery"]["status"] == "ACCEPTED"
        if kind == "EVENT": return value["status"] == "CONSUMED"
        return value["callback"]["status"] == "QUERY_QUEUED"
    results = {}
    for kind, item in wave["queues"].items():
        value = wait_for(lambda: runtime.call("GET", item["path"], user=item["user"]), lambda v: completed(kind, v), 50)
        trace = expected_trace(kind, item, legacy)
        if kind == "NOTIFICATION_EMAIL":
            assert any(message["traceId"] == trace and item["id"] in message["messageId"] for message in peers.mail)
        elif kind not in ("EVENT", "CALLBACK"):
            path = {"SERVICE": "/service/execute", "SIGNATURE": "/signature/submit", "ORGANIZATION": "/hr/changes", "NOTIFICATION_ENTERPRISE_IM": "/cgi-bin/message/send"}[kind]
            calls = [call for call in peers.calls if call["path"] == path and call["traceId"] == trace]
            assert len(calls) == (2 if kind == "SERVICE" else 1), (kind, trace, calls)
        if kind == "SIGNATURE":
            assert len([call for call in peers.calls if call.get("operationId") == item["id"] and call["traceId"] == trace and call["path"] == "/signature/artifact"]) == 2
        if kind == "ORGANIZATION":
            runtime.call("POST", item["path"] + "/cancel", {"expectedVersion": value["state"]["version"], "comment": "完成读取验收，保留来源记录"}, "admin")
            value = runtime.call("GET", item["path"], user="admin")
        results[kind] = value
    assert not peers.errors
    return results



def notification_run(args, directory, previous, current, sources, idp, peers):
    runtime = restored = None
    tables = {"NOTIFICATION": TABLES["NOTIFICATION"]}
    def login(value):
        for actor in ("alice", "manager", "admin"): value.client.login(actor)
    def create_wave(value):
        preferences(value, True)
        approval = {"id": "review", "name": "审批", "type": "USER_TASK", "properties": {"assigneeRule": "user:manager"}}
        application = draft(value, definition(value, "trace-notification", [approval])); body = {"expectedVersion": application["version"]}
        path = "/applications/" + application["id"] + "/submit"; key = str(uuid4())
        receipt = value.call("POST", path, body, key=key); trace = value.last_trace
        items = value.call("GET", "/notifications/deliveries?status=PENDING", user="manager")["items"]; assert len(items) == 2, items
        queues = {"NOTIFICATION_" + item["channel"]: {"id": item["id"], "sourceTrace": trace,
                  "path": "/notifications/deliveries/" + item["id"], "user": "manager"} for item in items}
        return {"queues": queues, "requests": [{"path": path, "body": body, "key": key, "user": "alice", "expected": 200, "receipt": receipt}]}
    try:
        runtime = runtime_for(args.java, directory / "notification", sources, idp, peers, notifications=True)
        finance.workers(runtime, False); runtime.start(previous); login(runtime); legacy = create_wave(runtime)
        save(directory / "notification-legacy-wave.json", legacy)
        runtime.stop(); before = split.columns_snapshot(runtime, idp.h2, "before-upgrade")
        old = finance.probe(runtime, idp, "before-upgrade", tables)
        runtime.start(current); runtime.stop()
        after = split.columns_snapshot(runtime, idp.h2, "after-upgrade", runtime.directory / "before-upgrade-columns.tsv")
        for table, facts in before["tables"].items():
            if table != "flyway_schema_history": assert facts["rows"] == after["tables"][table]["rows"] and facts["sha256"] == after["tables"][table]["originalColumnsSha256"], table
        upgraded = finance.probe(runtime, idp, "after-upgrade", tables)["notification_dispatch"]
        assert len(upgraded) == len(old["notification_dispatch"]) == 2
        for identity, row in upgraded.items():
            assert row["trace_id"] is None and {k: v for k, v in row.items() if k != "trace_id"} == old["notification_dispatch"][identity]
        runtime.settings["agentflow.notifications.delivery-worker-enabled"] = True
        runtime.start(current); login(runtime); legacy_results = finish(runtime, legacy, peers, True)
        runtime.stop(); runtime.settings["agentflow.notifications.delivery-worker-enabled"] = False
        runtime.start(current); login(runtime); wave = create_wave(runtime); save(directory / "notification-current-wave.json", wave)
        runtime.stop(force=True); queued = finance.probe(runtime, idp, "after-queued-kill", tables)
        for item in wave["queues"].values(): assert queued["notification_dispatch"][item["id"]]["trace_id"] == item["sourceTrace"]
        runtime.settings["agentflow.notifications.delivery-worker-enabled"] = True
        runtime.start(current); login(runtime); results = finish(runtime, wave, peers, False)
        runtime.stop(); final = finance.probe(runtime, idp, "completed", tables); backup = split.columns_snapshot(runtime, idp.h2, "before-restore")
        restored = runtime_for(args.java, directory / "notification-restored", sources, idp, peers, notifications=True); finance.workers(restored, False)
        for folder in ("data", "attachments"): shutil.copytree(runtime.directory / folder, restored.directory / folder)
        restored.start(current); restored.stop()
        assert split.columns_snapshot(restored, idp.h2, "after-restore") == backup
        assert finance.probe(restored, idp, "after-restore", tables) == final
        count = len(peers.calls), len(peers.mail)
        restored.start(current); login(restored)
        for collection, expected in ((legacy, legacy_results), (wave, results)):
            for kind, item in collection["queues"].items(): assert restored.call("GET", item["path"], user=item["user"]) == expected[kind]
        assert count == (len(peers.calls), len(peers.mail)) and not peers.errors
        return {"status": "PASS", "boots": runtime.starts + restored.starts, "queueTables": 1, "nonemptyUpgradeTables": len(before["tables"]),
                "legacyItems": 2, "currentItems": 2, "sameAuthorizedDetails": 4, "resentRequests": 0,
                "responseTraces": len(runtime.trace_records) + len(restored.trace_records), "businessHttpRequests": len(runtime.client.records) + len(restored.client.records)}
    finally:
        if restored: restored.stop()
        if runtime: runtime.stop()

def java_instant():
    """签署摘要采用 Java Instant 的 UTC 小数分组格式。"""
    value = risk.instant().replace("+00:00", "Z")
    if value.endswith(".000000Z"): return value[:-8] + "Z"
    if value.endswith("000Z"): return value[:-4] + "Z"
    return value


def run(args):
    directory = Path(args.output); assert directory.resolve().is_relative_to(Path('/fyoung/tmp').resolve()) and not directory.exists()
    directory.mkdir(); shutil.copy2(__file__, directory / Path(__file__).name)
    shutil.copy2(ROOT / "scripts/fixtures/IntegrationTracePeers.py", directory / "IntegrationTracePeers.py")
    previous, current = Path(args.previous_jar).resolve(), Path(args.current_jar).resolve()
    sources, financial_peer = reporting.sources_for(directory)
    financial_traces = finance.observe_gateway(sources, directory)
    risk.ROLES["admin"].add("FINANCE_CONFIG_ADMIN"); risk.ROLES["bob"].add("CASHIER")
    peers = fixtures.Peers(directory, args.node, java_instant)
    idp = runtime = restored = None
    evidence = {"status": "RUNNING", "database": "H2", "jarSha256": digest(current), "previousJarSha256": digest(previous),
                "browserVerified": False, "postgresqlVerified": False, "realExternalSystemsVerified": False, "checks": []}
    save(directory / "evidence.json", evidence)
    try:
        idp = risk.IdentityProvider(args.java, directory, current, financial_reporting=True)
        runtime = runtime_for(args.java, directory / "runtime", sources, idp, peers); runtime.start(previous)
        fixture = risk.setup(runtime, sources); fixture["entityId"] = sources.base.entity; reporting.configure_accounts(runtime, fixture)
        runtime.initiator = fixture["appointments"]["alice"]
        legacy = queue(runtime, prepare(runtime, sources, financial_peer, fixture, peers)); save(directory / "legacy-wave.json", legacy)
        runtime.stop(); before = split.columns_snapshot(runtime, idp.h2, "before-upgrade"); old_rows = finance.probe(runtime, idp, "before-upgrade", CORE_TABLES)
        runtime.start(current); runtime.stop(); upgraded = finance.probe(runtime, idp, "after-upgrade", CORE_TABLES)
        after = split.columns_snapshot(runtime, idp.h2, "after-upgrade", runtime.directory / "before-upgrade-columns.tsv")
        for table, facts in before["tables"].items():
            if table != "flyway_schema_history": assert facts["rows"] == after["tables"][table]["rows"] and facts["sha256"] == after["tables"][table]["originalColumnsSha256"], table
        for table, _ in CORE_TABLES.values():
            assert old_rows[table] and len(old_rows[table]) == len(upgraded[table])
            assert after["columns"][table.upper()] == before["columns"][table.upper()] + ["TRACE_ID"]
            for identity, row in upgraded[table].items():
                assert row["trace_id"] is None and {k: v for k, v in row.items() if k != "trace_id"} == old_rows[table][identity]
        evidence["checks"].append({"name": "nonempty-upgrade", "tables": len(before["tables"]), "queueTables": 5})
        workers(runtime, True); runtime.start(current); legacy_results = finish(runtime, legacy, peers, True)
        risk.stage("INTEGRATION_LEGACY_RECOVERY_VERIFIED", queues=5)
        prepared = prepare(runtime, sources, financial_peer, fixture, peers)
        runtime.stop(); workers(runtime, False); runtime.start(current)
        wave = queue(runtime, prepared); save(directory / "current-wave.json", wave)
        runtime.stop(force=True); queued = finance.probe(runtime, idp, "after-queued-kill", CORE_TABLES)
        for kind, item in wave["queues"].items(): assert queued[TABLES[family(kind)][0]][item["id"]]["trace_id"] == item["sourceTrace"], kind
        workers(runtime, True); runtime.start(current); results = finish(runtime, wave, peers, False)
        runtime.stop(); final = finance.probe(runtime, idp, "completed", CORE_TABLES)
        for collection in (legacy, wave):
            for kind, item in collection["queues"].items():
                table = TABLES[family(kind)][0]; old, new = queued[table][item["id"]], final[table][item["id"]]
                for column in ("trace_id", "input_json", "context_json", "command_digest", "request_digest", "target_digest"):
                    if column in old: assert old[column] == new[column], (kind, column)
        assert all(receipt["receivedCommands"] == 1 for receipt in financial_peer["receipts"].values())
        for collection in (legacy, wave):
            request = next(item["signed"] for item in collection["requests"] if "signed" in item and item["signed"]["path"].endswith("/callbacks"))
            identity = request["body"]["authorizationId"]
            commands = [call for call in financial_traces if call["operation"] == "payment-command" and call["body"]["data"]["command"]["id"] == identity]
            queries = [call for call in financial_traces if call["operation"] == "payment-query" and call["body"]["data"]["authorizationId"] == identity]
            assert len(commands) == 1 and len(queries) == 1
            assert queries[0]["traceId"] == commands[0]["traceId"] != collection["queues"]["CALLBACK"]["sourceTrace"]
        evidence["checks"].append({"name": "queued-kill-and-restart", "queueTables": 5, "legacyItems": 5, "currentItems": 5, "replays": 10})
        backup = split.columns_snapshot(runtime, idp.h2, "before-restore")
        restored = runtime_for(args.java, directory / "restored", sources, idp, peers)
        for folder in ("data", "attachments"): shutil.copytree(runtime.directory / folder, restored.directory / folder)
        finance.workers(restored, False); restored.start(current); restored.stop()
        assert split.columns_snapshot(restored, idp.h2, "after-restore") == backup
        assert finance.probe(restored, idp, "after-restore", CORE_TABLES) == final
        count = len(peers.calls), len(peers.mail), len(sources.finance_calls)
        restored.start(current)
        for actor in ("alice", "admin", "manager"): restored.client.login(actor)
        for collection, expected in ((legacy, legacy_results), (wave, results)):
            for kind, item in collection["queues"].items(): assert restored.call("GET", item["path"], user=item["user"]) == expected[kind], kind
        assert count == (len(peers.calls), len(peers.mail), len(sources.finance_calls))
        assert not sources.errors and not sources.base.errors and not peers.errors
        restored.stop()
        evidence["checks"].append({"name": "independent-restore", "tables": len(backup["tables"]), "sameAuthorizedDetails": 10, "resentRequests": 0})
        evidence["notificationRuntime"] = notification_run(args, directory, previous, current, sources, idp, peers)
        evidence.update(status="PASS", boots=runtime.starts + restored.starts, integrationHttpRequests=len(peers.calls), smtpMessages=len(peers.mail),
                        responseTraces=len(runtime.trace_records) + len(restored.trace_records), businessHttpRequests=len(runtime.client.records) + len(restored.client.records), signedHttpRequests=8)
        save(directory / "evidence.json", evidence); print(json.dumps(evidence, ensure_ascii=False), flush=True)
    except Exception as error:
        evidence.update(status="FAILED", failure=repr(error)); save(directory / "evidence.json", evidence); raise
    finally:
        if restored: restored.stop()
        if runtime: runtime.stop()
        if idp: idp.close()
        peers.close(); sources.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("java", "node", "previous-jar", "current-jar", "output"): parser.add_argument("--" + name, required=True)
    run(parser.parse_args())
