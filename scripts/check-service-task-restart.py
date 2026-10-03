#!/usr/bin/env python3
"""用独立安装包和合成接收方，验证默认 H2 文件库的执行中强制退出恢复。"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.request import ProxyHandler, Request, build_opener
from uuid import uuid4


def save(path, value):
    """验收记录只包含合成数据，不记录登录令牌。"""
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


class Receiver:
    """按原号去重；业务效果持久保存后，暂扣执行回执以形成确定的退出窗口。"""

    def __init__(self, directory):
        self.directory = directory
        self.calls, self.effects = [], {}
        self.contract_digest = None
        self.recorded, self.release = threading.Event(), threading.Event()
        self.lock = threading.Lock()
        receiver = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                if self.path not in ("/execute", "/query") or self.headers.get("Authorization") != "Bearer synthetic-service-token":
                    self.send_error(404)
                    return
                request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                execute = self.path == "/execute"
                identity = request["command"]["id"] if execute else request["operationId"]
                digest = request["commandDigest"]
                with receiver.lock:
                    receiver.calls.append({"path": self.path, "request": request})
                    if execute:
                        if self.headers.get("Idempotency-Key") != identity or (identity in receiver.effects and receiver.effects[identity]["commandDigest"] != digest):
                            self.send_error(409)
                            return
                        receiver.effects.setdefault(identity, {
                            "operationId": identity, "commandDigest": digest, "status": "APPLIED",
                            "reference": "synthetic-" + identity,
                            "completedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
                        })
                    observation = receiver.effects.get(identity, {"operationId": identity, "commandDigest": digest, "status": "NOT_FOUND"})
                    # 接收方必须先保住效果，再向控制进程报告已执行。
                    with (directory / "receiver.json").open("w") as output:
                        json.dump({"calls": receiver.calls, "effects": receiver.effects}, output)
                        output.flush()
                        os.fsync(output.fileno())
                if execute:
                    receiver.recorded.set()
                    receiver.release.wait(60)
                body = json.dumps({"protocolVersion": 1, "tenantId": "demo", "operationKey": "receipt.register",
                                   "operationVersion": 1, "contractDigest": receiver.contract_digest, "observation": observation}).encode()
                try:
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError):
                    pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def close(self):
        self.release.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)


class Runtime:
    """只管理本脚本创建的进程和临时目录，数据源 URL 取安装包自身默认值。"""

    def __init__(self, java, jar, directory, receiver):
        self.java, self.jar, self.directory = java, jar, directory
        self.process, self.sessions, self.starts = None, {}, 0
        self.http = build_opener(ProxyHandler({}))
        with socket.socket() as available:
            available.bind(("127.0.0.1", 0))
            self.port = available.getsockname()[1]
        self.config = directory / "runtime.json"
        save(self.config, {"server": {"address": "127.0.0.1", "port": self.port}, "agentflow": {
            "attachments": {"directory": str(directory / "attachments")},
            "service-tasks": {"lease-seconds": 15, "gateway": {"enabled": True, "tenants": {"demo": [{
                "key": "receipt.register", "version": 1, "name": "重启验收合成操作", "enabled": True,
                "parameters": [{"name": "memo", "type": "TEXT", "required": True, "sensitive": True}],
                "endpoint": "http://127.0.0.1:" + str(receiver.server.server_port) + "/", "token": "synthetic-service-token",
            }]}}}}, "logging": {"level": {"com.zaxxer.hikari.HikariConfig": "DEBUG"}}})
        # Spring 根据后缀选择配置解析器；内容为合法 YAML 的 JSON。
        self.config = self.config.rename(directory / "runtime.yaml")

    def request(self, method, path, body=None, user=None, expected=200):
        headers = {"Content-Type": "application/json", "Idempotency-Key": str(uuid4())}
        if user:
            headers["Authorization"] = "Bearer " + self.sessions[user]
        request = Request("http://127.0.0.1:" + str(self.port) + "/api/v1" + path, method=method, headers=headers,
                          data=json.dumps(body).encode() if body is not None else None)
        try:
            response = self.http.open(request, timeout=15)
        except HTTPError as failure:
            response = failure
        with response:
            raw = response.read()
            result = json.loads(raw) if raw else None
            if response.status != expected:
                raise AssertionError(f"Unexpected HTTP status: {method} {path}: {response.status}; {result}")
            return result

    def start(self):
        self.starts += 1
        environment = {key: value for key, value in os.environ.items() if not key.startswith(("AGENTFLOW_", "SPRING_", "SERVER_"))}
        with (self.directory / f"runtime-{self.starts}.log").open("w") as output:
            self.process = subprocess.Popen([self.java, "-Djava.io.tmpdir=/fyoung/tmp", "-jar", str(self.jar),
                "--spring.config.location=classpath:/application.yml,file:" + str(self.config)], cwd=self.directory,
                env=environment, stdout=output, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            try:
                self.request("GET", "/auth/options")
                break
            except (URLError, ConnectionError):
                if self.process.poll() is not None:
                    raise AssertionError("Owned runtime exited before readiness")
                time.sleep(0.2)
        else:
            raise AssertionError("Owned runtime did not become ready")
        for user in ("admin", "alice", "finance"):
            self.sessions[user] = self.request("POST", "/auth/login", {"tenantId": "demo", "username": user, "password": "demo"})["token"]

    def stop(self, force=False):
        if self.process is None or self.process.poll() is not None:
            return self.process is None or self.process.returncode == 0
        if force:
            self.process.kill()
        else:
            self.process.terminate()
        try:
            self.process.wait(timeout=15)
            return True
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=10)
            return False


def exercise(runtime, receiver, cycle):
    """结束执行中的应用进程，随后只允许原号查询恢复及真实人工批准。"""
    if cycle == 0:
        contract = runtime.request("GET", "/process-definitions/service-task-options/receipt.register/versions/1", user="admin")
        receiver.contract_digest = contract["contractDigest"]
        nodes = [{"id": "start", "name": "开始", "type": "START", "properties": {}},
                 {"id": "service", "name": "合成操作", "type": "SERVICE_TASK", "properties": {
                     "serviceOperationKey": "receipt.register", "serviceOperationVersion": "1",
                     "serviceContractDigest": receiver.contract_digest, "serviceInput.memo": "reason"}},
                 {"id": "review", "name": "人工审批", "type": "USER_TASK", "properties": {"assigneeRule": "user:finance"}},
                 {"id": "end", "name": "结束", "type": "END", "properties": {}}]
        edges = [{"id": "edge-" + str(index), "source": left, "target": right, "condition": "", "defaultBranch": False}
                 for index, (left, right) in enumerate(zip(("start", "service", "review"), ("service", "review", "end")))]
        definition = runtime.request("POST", "/process-definitions", {"key": "restart-" + str(uuid4()), "name": "异常退出恢复验收",
            "graph": {"nodes": nodes, "edges": edges}, "formSchema": {"schemaVersion": 2, "fields": [
                {"key": "reason", "label": "说明", "type": "TEXT", "required": True},
                {"key": "secret", "label": "不可外发", "type": "TEXT", "required": False, "sensitive": True}]}}, user="admin")
        runtime.definition = runtime.request("POST", "/process-definitions/" + definition["id"] + "/publish?expectedRevision=" + str(definition["revision"]),
                                             {"changeNote": "固定安装包异常退出恢复验收"}, user="admin")
    definition = runtime.definition
    # 先正常关闭并重开已有库，覆盖关闭整理后的文件状态，而不只检查新库。
    if not runtime.stop():
        raise AssertionError("Owned runtime did not stop gracefully before reopening")
    runtime.start()
    receiver.recorded.clear()
    receiver.release.clear()
    application = runtime.request("POST", "/applications", {"businessNo": "restart-" + str(uuid4()), "processKey": definition["key"],
        "definitionVersion": definition["version"], "title": "执行中退出", "payload": {"reason": "synthetic-original", "secret": "synthetic-not-sent"}}, user="alice", expected=201)
    identity = application["id"]
    runtime.request("POST", "/applications/" + identity + "/submit", {"expectedVersion": application["version"]}, user="alice")
    if not receiver.recorded.wait(15):
        raise AssertionError("Receiver did not persist the synthetic effect")
    runtime.stop(force=True)
    receiver.release.set()
    runtime.start()
    # 先检查申请是否仍存在，避免把已丢失记录误当成调度延迟。
    runtime.request("GET", "/applications/" + identity, user="alice")
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        tasks = [task for task in runtime.request("GET", "/tasks", user="finance") if task["applicationId"] == identity]
        if tasks:
            break
        time.sleep(0.2)
    else:
        raise AssertionError("Original wait was not recovered")
    with receiver.lock:
        execute = next(call for call in receiver.calls if call["path"] == "/execute" and call["request"]["command"]["binding"]["applicationId"] == identity)
        operation = execute["request"]["command"]["id"]
        related = [call for call in receiver.calls if call["request"].get("operationId") == operation or call["request"].get("command", {}).get("id") == operation]
    if [call["path"] for call in related] != ["/execute", "/query"]:
        raise AssertionError("Recovery must query the original operation before any resend")
    if execute["request"]["command"]["inputs"] != {"memo": "synthetic-original"}:
        raise AssertionError("Execution sent fields outside the declared mapping")
    if related[1]["request"]["commandDigest"] != execute["request"]["commandDigest"]:
        raise AssertionError("Recovery changed the original command digest")
    if "command" in related[1]["request"] or "inputs" in related[1]["request"]:
        raise AssertionError("Recovery query sent execution inputs")
    current = runtime.request("GET", "/applications/" + identity, user="alice")
    runtime.request("POST", "/tasks/" + tasks[0]["taskId"] + "/actions", {"action": "APPROVE", "expectedVersion": current["version"], "comment": "核对原操作恢复"}, user="finance")
    if runtime.request("GET", "/applications/" + identity, user="alice")["status"] != "APPROVED":
        raise AssertionError("Recovered workflow did not finish after human approval")
    return {"cycle": cycle, "applicationId": identity, "operationId": operation, "executeCalls": 1, "queryCalls": 1, "result": "PASS"}


def main():
    """每次创建独立文件库，不接触已有应用或数据库。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--cycles", type=int, choices=range(1, 6), default=3)
    args = parser.parse_args()
    jar = args.jar.resolve(strict=True)
    directory = Path(tempfile.mkdtemp(prefix="agentflow-service-restart-", dir="/fyoung/tmp"))
    result = {"directory": str(directory), "jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(), "synthetic": True, "cycles": [], "result": "RUNNING"}
    receiver = Receiver(directory)
    runtime = Runtime(args.java, jar, directory, receiver)
    try:
        runtime.start()
        for cycle in range(args.cycles):
            result["cycles"].append(exercise(runtime, receiver, cycle))
            save(directory / "result.json", result)
        result["result"] = "PASS"
    except Exception as error:
        result.update(result="FAIL", failure=str(error))
        raise
    finally:
        receiver.release.set()
        runtime.stop()
        receiver.close()
        save(directory / "result.json", result)
        print(json.dumps(result, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
