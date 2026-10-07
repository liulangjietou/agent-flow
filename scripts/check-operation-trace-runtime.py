#!/usr/bin/env python3
"""固定包验证请求关联、旧 outbox 升级、实际投递中强退和独立数据库恢复。"""

import argparse
import base64
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
from pathlib import Path
import re
import shutil
import threading
from urllib.error import HTTPError
from urllib.request import Request
from uuid import uuid4
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("split_runtime", ROOT / "scripts/check-expense-split-routing.py")
split = importlib.util.module_from_spec(spec)
spec.loader.exec_module(split)
common = split.risk.common
save, digest, wait_for = common.save, common.digest, common.wait_for
TRACE = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
SENTRY = "o01-private-payload-sentinel"
KEY = bytes(32)  # 只用于本脚本的合成回环接收方。


class Receiver:
    """核对原 UTF-8 签名和实际请求头，可在收到指定事件后阻塞一次。"""

    def __init__(self, directory):
        self.directory, self.records, self.errors = directory, [], []
        self.block_event = None
        self.entered, self.release = threading.Event(), threading.Event()
        self.lock = threading.Lock()
        receiver = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                try:
                    length = int(self.headers["Content-Length"])
                    assert 0 < length <= 65536
                    raw = self.rfile.read(length)
                    body = json.loads(raw)
                    event, stamp = self.headers["webhook-id"], self.headers["webhook-timestamp"]
                    expected = "v1," + base64.b64encode(hmac.new(KEY, event.encode() + b"." + stamp.encode() + b"." + raw, hashlib.sha256).digest()).decode()
                    assert self.headers["webhook-signature"] == expected
                    assert self.headers["X-Trace-Id"] == body["traceId"] and TRACE.fullmatch(body["traceId"])
                    assert self.headers.get("X-Tenant-Id") is None and SENTRY not in raw.decode()
                    assert body["eventId"] == event and body["tenantId"] == "demo"
                    with receiver.lock:
                        count = sum(record["eventId"] == event for record in receiver.records)
                        receiver.records.append({"eventId": event, "traceId": body["traceId"], "body": body,
                                                 "bodySha256": hashlib.sha256(raw).hexdigest()})
                        save(directory / "received.json", receiver.records)
                    if receiver.block_event == event and count == 0:
                        receiver.entered.set()
                        assert receiver.release.wait(90), "Controlled receiver was not released"
                    self.send_response(204); self.end_headers()
                except (BrokenPipeError, ConnectionResetError):
                    pass
                except Exception as error:
                    receiver.errors.append(repr(error)); save(directory / "receiver-errors.json", receiver.errors)
                    self.send_error(500)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def close(self):
        self.release.set(); self.server.shutdown(); self.server.server_close(); self.thread.join(timeout=5)


class Runtime(common.Runtime):
    """沿用已核验的隔离启动配置，额外检查每次实际响应的追踪头。"""

    def __init__(self, java, directory, receiver):
        super().__init__(java, directory, receiver)
        self.tracing, self.last_trace = False, None
        self.settings.update({"agentflow.finance-gateway.enabled": False, "agentflow.assist.enabled": False,
                              "agentflow.expenses.precheck-worker-enabled": False,
                              "agentflow.webhooks.worker-enabled": False, "agentflow.webhooks.poll-delay-ms": 200,
                              "agentflow.webhooks.allow-insecure-http-in-demo": True,
                              "agentflow.webhooks.targets.local.tenant-id": "demo",
                              "agentflow.webhooks.targets.local.label": "合成追踪接收方",
                              "agentflow.webhooks.targets.local.url": f"http://127.0.0.1:{receiver.server.server_port}/receive",
                              "agentflow.webhooks.targets.local.signing-secret": "whsec_" + base64.b64encode(KEY).decode(),
                              "agentflow.webhooks.targets.local.enabled": True})

    def call(self, method, path, body=None, user="alice", expected=200, key=None, raw=None, extra=None):
        headers = {"Content-Type": "application/json", **(extra or {})}
        if user is not None:
            headers["Authorization"] = "Bearer " + self.tokens[user]
        if method not in ("GET", "HEAD", "OPTIONS") and not path.startswith("/auth/"):
            headers["Idempotency-Key"] = key or str(uuid4())
        payload = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        try:
            response = self.http.open(Request(f"http://127.0.0.1:{self.port}/api/v1" + path, method=method, headers=headers, data=payload), timeout=15)
        except HTTPError as error:
            response = error
        with response:
            content = response.read()
            value = json.loads(content) if content and "application/json" in response.headers.get("Content-Type", "") else content.decode()
            trace = response.headers.get("X-Trace-Id")
            if self.tracing:
                assert trace and TRACE.fullmatch(trace), (method, path, "Missing server trace")
                assert trace != headers.get("X-Trace-Id"), "Client trace header was trusted"
                if isinstance(value, dict) and "traceId" in value:
                    assert value["traceId"] == trace
            self.last_trace = trace
            self.records.append({"boot": self.starts, "method": method, "path": path, "status": response.status,
                                 "traceId": trace, "replayed": response.headers.get("Idempotency-Replayed"),
                                 "response": None if path.startswith("/auth/") else value})
            save(self.directory / "http-records.json", self.records)
            assert response.status == expected, (method, path, response.status, value)
            return value


def submit(runtime):
    app = runtime.call("POST", "/applications", {"businessNo": "TRACE-" + str(uuid4()), "processKey": "expense-reimbursement",
        "definitionVersion": 1, "title": SENTRY, "payload": {"amount": 6000, "privateValue": SENTRY}}, expected=201)
    key = str(uuid4())
    receipt = runtime.call("POST", f"/applications/{app['id']}/submit", {"expectedVersion": 1}, key=key)
    trace = runtime.last_trace
    replay = runtime.call("POST", f"/applications/{app['id']}/submit", {"expectedVersion": 1}, key=key)
    assert replay == receipt
    deliveries = runtime.call("GET", f"/integrations/webhooks/deliveries?applicationId={app['id']}", user="admin")["items"]
    assert len(deliveries) == 1
    return {"id": app["id"], "receipt": receipt, "traceId": trace, "delivery": deliveries[0]}


def run(args):
    directory = Path(args.work_dir)
    assert directory.is_absolute() and str(directory).startswith("/fyoung/tmp/") and not directory.exists()
    directory.mkdir()
    old_jar, new_jar = Path(args.previous_jar), Path(args.current_jar)
    assert old_jar.is_file() and new_jar.is_file() and old_jar != new_jar
    with zipfile.ZipFile(new_jar) as archive:
        entry = next(name for name in archive.namelist() if re.fullmatch(r"BOOT-INF/lib/h2-[^/]+\.jar", name))
        h2 = directory / "h2.jar"; h2.write_bytes(archive.read(entry))
    receiver = Receiver(directory)
    runtime = Runtime(args.java, directory, receiver)
    restored = None
    try:
        runtime.start(old_jar)
        old = submit(runtime)
        runtime.stop()
        before = split.columns_snapshot(runtime, h2, "before-upgrade")
        runtime.tracing = True
        runtime.start(new_jar)
        assert runtime.call("GET", "/applications/" + old["id"])["status"] == "IN_APPROVAL"
        runtime.stop()
        after = split.columns_snapshot(runtime, h2, "after-upgrade")
        assert before == after, "Read-only upgrade changed stored data or schema"
        runtime.start(new_jar)
        new = submit(runtime)
        assert new["traceId"]
        runtime.call("GET", "/applications", user=None, expected=401)
        runtime.call("GET", "/applications/not-a-uuid", expected=400, extra={"X-Trace-Id": str(uuid4())})
        runtime.call("GET", "/applications/" + str(uuid4()), expected=404)
        runtime.call("GET", "/integrations/webhooks", user="alice", expected=403)
        runtime.call("OPTIONS", "/applications", user=None, expected=403,
                     extra={"Origin": "https://untrusted.invalid", "Access-Control-Request-Method": "GET"})
        state = runtime.call("GET", "/applications/" + new["id"])
        receiver.block_event = new["delivery"]["eventId"]
        runtime.stop()
        runtime.settings["agentflow.webhooks.worker-enabled"] = True
        runtime.start(new_jar)
        assert receiver.entered.wait(15), "New delivery did not reach receiver"
        runtime.stop(force=True)
        receiver.release.set()
        print(json.dumps({"stage": "DELIVERY_PROCESS_KILLED_AFTER_RECEIVER_ACCEPTED", "eventId": receiver.block_event}), flush=True)
        runtime.start(new_jar)
        detail_path = "/integrations/webhooks/deliveries/" + new["delivery"]["id"]
        detail = wait_for(lambda: runtime.call("GET", detail_path, user="admin"),
                          lambda value: value["delivery"]["status"] == "DELIVERED", seconds=55)
        assert [item["result"] for item in detail["attempts"]] == ["DELIVERED", "OUTCOME_UNKNOWN"]
        assert runtime.call("GET", "/applications/" + new["id"]) == state
        assert not receiver.errors
        records = [record for record in receiver.records if record["eventId"] == receiver.block_event]
        assert len(records) == 2 and records[0] == records[1] and records[0]["traceId"] == new["traceId"]
        old_records = [record for record in receiver.records if record["eventId"] == old["delivery"]["eventId"]]
        assert len(old_records) == 1 and old_records[0]["traceId"] == old["delivery"]["eventId"]
        runtime.stop()
        final = split.columns_snapshot(runtime, h2, "before-restore")
        restore_dir = directory / "restored"; restore_dir.mkdir()
        shutil.copytree(directory / "data", restore_dir / "data")
        restored = Runtime(args.java, restore_dir, receiver)
        restored.tracing = True
        restored.start(new_jar)
        assert restored.call("GET", detail_path, user="admin") == detail
        assert restored.call("GET", "/applications/" + new["id"]) == state
        restored.stop()
        restored_snapshot = split.columns_snapshot(restored, h2, "after-restore")
        assert restored_snapshot == final
        for log in directory.glob("runtime-*.log"):
            assert SENTRY not in log.read_text(), "Request body leaked into server logs"
        correlated = [line for log in directory.glob("runtime-*.log") for line in log.read_text().splitlines()
                      if "traceId=" + new["traceId"] in line]
        assert any("HTTP request completed" in line for line in correlated)
        assert any("Webhook attempt completed" in line for line in correlated)
        save(directory / "correlated-log-lines.json", correlated)
        evidence = {"status": "PASSED", "previousJarSha256": digest(old_jar), "currentJarSha256": digest(new_jar),
                    "boots": runtime.starts + restored.starts, "httpRecords": len(runtime.records) + len(restored.records),
                    "unchangedTablesOnUpgrade": len(before["tables"]), "unchangedTablesOnRestore": len(final["tables"]),
                    "receivedRequests": len(receiver.records), "old": old, "new": new, "delivery": detail,
                    "limits": ["H2 only", "Loopback synthetic receiver only", "Other background tracing remains under O01"]}
        save(directory / "evidence.json", evidence)
        print(json.dumps({key: value for key, value in evidence.items() if key not in ("old", "new", "delivery")}), flush=True)
    finally:
        runtime.stop()
        if restored is not None:
            restored.stop()
        receiver.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    for flag in ("previous-jar", "current-jar", "java", "work-dir"):
        parser.add_argument("--" + flag, required=True)
    run(parser.parse_args())
