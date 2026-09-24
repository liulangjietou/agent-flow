"""在自行创建的隔离 PostgreSQL/服务容器中生成真实审批负载，保留数据和测量报告。"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import platform
import re
import socket
import subprocess
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener
import uuid

LABEL = "io.agentflow.capacity.run"
FORMAT = "agentflow-capacity-v1"
MAX_RESPONSE_BYTES = 2 * 1024 * 1024
SERVER_CPUS, SERVER_MEMORY_MIB, JAVA_HEAP_MIB = 2, 1536, 1024
DATABASE_CPUS, DATABASE_MEMORY_MIB, CONNECTION_POOL_SIZE = 1, 768, 10


class BaselineError(Exception):
    """只携带稳定错误，不输出请求令牌、表单或外部响应正文。@author owlzhangfq@gmail.com"""


class NoRedirect(HTTPRedirectHandler):
    """负载只能到新建的本机服务，禁止跟随服务重定向。@author owlzhangfq@gmail.com"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise BaselineError("HTTP_REDIRECT_REJECTED")


def docker(*args, timeout=60):
    """Docker 参数逐项传入；命令失败不把环境变量或诊断正文带入报告。"""
    try:
        result = subprocess.run(["docker", *args], capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise BaselineError("DOCKER_UNAVAILABLE_OR_TIMEOUT") from error
    if result.returncode:
        raise BaselineError("DOCKER_COMMAND_FAILED")
    return result.stdout.strip()


def write_json(path, value):
    """产物使用独占创建，拒绝覆盖历史证据。"""
    with path.open("x", encoding="utf-8") as stream:
        os.chmod(path, 0o600)
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def percentile(values, percent):
    """使用最近秩，报告样本数量，避免小样本插值制造不存在的请求延迟。"""
    return sorted(values)[max(0, math.ceil(percent / 100 * len(values)) - 1)] if values else None


def summarize(samples):
    """失败请求仍进入计数和延迟分布，不把失败或超时删除后计算成功率。"""
    groups = {}
    for sample in samples:
        groups.setdefault((sample["phase"], sample["operation"]), []).append(sample)
    result = []
    for (phase, operation), rows in sorted(groups.items()):
        durations = [row["milliseconds"] for row in rows]
        errors = {}
        for row in rows:
            if row["error"]:
                errors[row["error"]] = errors.get(row["error"], 0) + 1
        result.append({"phase": phase, "operation": operation, "requests": len(rows),
                       "failed": sum(errors.values()), "errors": errors,
                       "p50Ms": percentile(durations, 50), "p95Ms": percentile(durations, 95),
                       "p99Ms": percentile(durations, 99), "maxMs": max(durations)})
    return result


class Client:
    """只访问已验证的隔离服务；请求不重试，写操作始终携带独立幂等键。@author owlzhangfq@gmail.com"""

    def __init__(self, port, samples):
        self.base = f"http://127.0.0.1:{port}/api/v1"
        self.tokens, self.samples, self.lock = {}, samples, threading.Lock()

    def call(self, method, path, user="admin", body=None, expected=200, phase="verify", operation="verify"):
        """发送一次请求并记录完整客户端耗时；失败只携带分类码，不重放业务写入。"""
        headers = {"Content-Type": "application/json"}
        if user in self.tokens:
            headers["Authorization"] = "Bearer " + self.tokens[user]
        if method != "GET" and not path.startswith("/auth/"):
            headers["Idempotency-Key"] = str(uuid.uuid4())
        req = Request(self.base + path, method=method, headers=headers,
                      data=None if body is None else json.dumps(body).encode())
        started, status, error_code = time.perf_counter(), None, None
        try:
            # 不继承系统代理；每次请求新连接，报告中明确包含连接与 JSON 解码时间。
            opener = build_opener(ProxyHandler({}), NoRedirect())
            try:
                response = opener.open(req, timeout=15)
            except HTTPError as error:
                response = error
            with response:
                status = response.status
                content = response.read(MAX_RESPONSE_BYTES + 1)
                if len(content) > MAX_RESPONSE_BYTES:
                    raise BaselineError("HTTP_RESPONSE_TOO_LARGE")
                value = json.loads(content) if content else None
                if status != expected:
                    raise BaselineError("UNEXPECTED_HTTP_" + str(status))
                return value
        except BaselineError as error:
            error_code = str(error)
            raise
        except (URLError, OSError, TimeoutError, ValueError) as error:
            error_code = "HTTP_TRANSPORT_OR_JSON_FAILED"
            raise BaselineError(error_code) from error
        finally:
            sample = {"phase": phase, "operation": operation, "status": status, "error": error_code,
                      "milliseconds": round((time.perf_counter() - started) * 1000, 3)}
            with self.lock:
                self.samples.append(sample)

    def page(self, path, user="admin", **filters):
        """遍历固定数据阶段的全部分页，拒绝重复游标或无界响应。"""
        rows, seen = [], set()
        while True:
            page = self.call("GET", path + "?" + urlencode({"limit": 100, **filters}), user)
            check(isinstance(page.get("items"), list) and len(page["items"]) <= 100, "INVALID_PAGE_SIZE")
            rows.extend(page["items"])
            cursor = page.get("nextCursor")
            if not cursor:
                return rows
            check(cursor not in seen and len(seen) < 1000, "INVALID_PAGE_CURSOR")
            seen.add(cursor)
            filters["cursor"] = cursor


def check(condition, code):
    if not condition:
        raise BaselineError(code)


def parallel(action, items, workers):
    """首个失败后阻止排队中的业务操作继续发出，已经发出的请求等待原结果。"""
    stopped = threading.Event()
    def guarded(item):
        check(not stopped.is_set(), "WORKLOAD_ABORTED")
        try:
            return action(item)
        except Exception:
            stopped.set()
            raise
    with ThreadPoolExecutor(max_workers=workers) as pool:
        return list(pool.map(guarded, items))


class Sandbox:
    """只管理本次创建且标识匹配的容器；结束只停止，保留网络、卷和所有数据。@author owlzhangfq@gmail.com"""

    def __init__(self, args, output):
        self.args, self.output = args, output
        self.run_id = uuid.uuid4().hex
        self.name = "agentflow-capacity-" + self.run_id[:12]
        self.containers = {}
        self.manifest = {"runId": self.run_id, "network": self.name, "volume": self.name + "-data", "containers": self.containers}

    def start(self):
        """验证本机镜像及运行环境后，创建空库与受资源上限约束的演示服务。"""
        endpoint = os.environ.get("DOCKER_HOST", "")
        check(not endpoint or endpoint.startswith("unix://"), "LOCAL_DOCKER_REQUIRED")
        context = json.loads(docker("context", "inspect"))[0]
        check(context["Endpoints"]["docker"]["Host"].startswith("unix://"), "LOCAL_DOCKER_REQUIRED")
        server = json.loads(docker("image", "inspect", self.args.server_image))[0]
        database = json.loads(docker("image", "inspect", self.args.postgres_image))[0]
        self.manifest["images"] = {"server": server["Id"], "database": database["Id"]}
        self.manifest["architecture"] = server["Architecture"]
        info = json.loads(docker("info", "--format", "{{json .}}"))
        check(int(info["ServerVersion"].split(".")[0]) >= 28, "DOCKER_28_OR_NEWER_REQUIRED")
        self.manifest["dockerHost"] = {key: info.get(key) for key in ("ServerVersion", "NCPU", "MemTotal", "Architecture", "KernelVersion")}
        self.manifest["resources"] = {"serverCpuLimit": SERVER_CPUS, "serverMemoryMiB": SERVER_MEMORY_MIB, "javaHeapMiB": JAVA_HEAP_MIB,
                                      "databaseCpuLimit": DATABASE_CPUS, "databaseMemoryMiB": DATABASE_MEMORY_MIB, "hikariMaximumPoolSize": CONNECTION_POOL_SIZE}
        # 宿主机压测需要 NAT 发布端口；internal 网络在 Docker Desktop 不提供此端口映射。
        docker("network", "create", "--driver", "bridge", "--opt", "com.docker.network.bridge.gateway_mode_ipv4=nat",
               "--opt", "com.docker.network.bridge.host_binding_ipv4=127.0.0.1", "--label", LABEL + "=" + self.run_id, self.name)
        # 名称含随机运行标识；仍先检查，避免 Docker volume create 重用已有卷。
        existing = docker("volume", "ls", "--format", "{{.Name}}").splitlines()
        check(self.name + "-data" not in existing, "VOLUME_ALREADY_EXISTS")
        docker("volume", "create", "--label", LABEL + "=" + self.run_id, self.name + "-data")
        db_id = docker("run", "-d", "--name", self.name + "-db", "--network", self.name, "--network-alias", "database",
                       "--label", LABEL + "=" + self.run_id, "--cpus", str(DATABASE_CPUS), "--memory", str(DATABASE_MEMORY_MIB) + "m",
                       "-e", "POSTGRES_DB=agentflow", "-e", "POSTGRES_USER=agentflow", "-e", "POSTGRES_PASSWORD=capacity-demo-only",
                       "--mount", "type=volume,src=" + self.name + "-data,dst=/var/lib/postgresql/data", database["Id"])
        self.containers["database"] = db_id
        self.wait_database(db_id)
        version = docker("exec", db_id, "psql", "-XAt", "-U", "agentflow", "-d", "agentflow", "-c", "SHOW server_version_num")
        check(version.isdigit() and 170000 <= int(version) < 180000, "POSTGRES_17_REQUIRED")
        self.manifest["postgresVersion"] = int(version)
        server_id = docker("run", "-d", "--name", self.name + "-server", "--network", self.name,
                           "--label", LABEL + "=" + self.run_id, "--cpus", str(SERVER_CPUS), "--memory", str(SERVER_MEMORY_MIB) + "m",
                           "-p", f"127.0.0.1:{self.args.port}:8080", "-e", "SERVER_ADDRESS=0.0.0.0",
                           "-e", "AGENTFLOW_DATASOURCE_URL=jdbc:postgresql://database:5432/agentflow",
                           "-e", "AGENTFLOW_DATASOURCE_USERNAME=agentflow", "-e", "AGENTFLOW_DATASOURCE_PASSWORD=capacity-demo-only",
                           "-e", "AGENTFLOW_DATASOURCE_DRIVER=org.postgresql.Driver", "-e", "AGENTFLOW_DEMO_AUTH=true",
                           "-e", f"AGENTFLOW_WEB_ORIGIN=http://127.0.0.1:{self.args.port}",
                           "-e", "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=" + str(CONNECTION_POOL_SIZE),
                           "-e", "JAVA_TOOL_OPTIONS=-Xms256m -Xmx" + str(JAVA_HEAP_MIB) + "m", server["Id"])
        self.containers["server"] = server_id
        self.wait_server(server_id)
        # 以新容器的实际端口映射核对目的地，CLI 不接受任意目标 URL。
        current = json.loads(docker("inspect", server_id))[0]
        check(current["NetworkSettings"]["Ports"]["8080/tcp"] == [{"HostIp": "127.0.0.1", "HostPort": str(self.args.port)}], "PORT_BINDING_MISMATCH")
        self.manifest["javaVersion"] = docker("exec", server_id, "java", "--version").splitlines()[0]

    def wait_database(self, container):
        """仅等待本次数据库，退出或超时立即终止启动。"""
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            try:
                docker("exec", container, "pg_isready", "-U", "agentflow", "-d", "agentflow", timeout=5)
                return
            except BaselineError:
                check(json.loads(docker("inspect", container))[0]["State"]["Running"], "DATABASE_EXITED")
                time.sleep(1)
        raise BaselineError("DATABASE_START_TIMEOUT")

    def wait_server(self, container):
        """实际就绪探针通过后才允许开始业务负载。"""
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            try:
                health = docker("exec", container, "curl", "-fsS", "--max-time", "3", "http://127.0.0.1:8080/actuator/health/readiness", timeout=5)
                if json.loads(health).get("status") == "UP":
                    return
            except (BaselineError, ValueError):
                pass
            check(json.loads(docker("inspect", container))[0]["State"]["Running"], "SERVER_EXITED")
            time.sleep(1)
        raise BaselineError("SERVER_START_TIMEOUT")

    def stop(self):
        """先核对每个已创建容器的完整 ID 和标签；失败时不停止身份不明的资源。"""
        errors = []
        for service in ("server", "database"):
            identifier = self.containers.get(service)
            if not identifier:
                continue
            try:
                current = json.loads(docker("inspect", identifier))[0]
                check(current["Id"] == identifier and current["Config"].get("Labels", {}).get(LABEL) == self.run_id, "RESOURCE_IDENTITY_MISMATCH")
                docker("stop", "--timeout", "15", identifier, timeout=25)
                check(not json.loads(docker("inspect", identifier))[0]["State"]["Running"], "RESOURCE_STILL_RUNNING")
            except BaselineError as error:
                errors.append({"service": service, "error": str(error)})
        return errors


def workload(client, args, report, progress):
    """业务数据只经原应用 API 产生；完成后逐页核对状态、任务、轮次和通知总量。"""
    for user in ("admin", "alice", "bob", "manager", "employee"):
        client.tokens[user] = client.call("POST", "/auth/login", user="anonymous", body={
            "tenantId": "demo", "username": user, "password": "demo"}, operation="login")["token"]
    check(not client.page("/operations/applications"), "SANDBOX_APPLICATIONS_NOT_EMPTY")
    prefix = "capacity-" + uuid.uuid4().hex[:8]
    graph = {"nodes": [{"id": "start", "name": "开始", "type": "START", "properties": {}},
                       {"id": "review", "name": "容量样本审批", "type": "USER_TASK", "properties": {"assigneeRule": "user:manager"}},
                       {"id": "end", "name": "结束", "type": "END", "properties": {}}],
             "edges": [{"id": "begin", "source": "start", "target": "review", "condition": ""},
                       {"id": "finish", "source": "review", "target": "end", "condition": ""}]}
    schema = {"schemaVersion": 1, "fields": [{"key": "amount", "label": "金额", "type": "NUMBER", "required": True},
                                             {"key": "reason", "label": "说明", "type": "TEXTAREA", "required": True, "maxLength": 2000}]}
    process_keys = []
    for index in range(args.definitions):
        key = prefix + "-" + str(index)
        draft = client.call("POST", "/process-definitions", body={"key": key, "name": "容量样本 " + str(index), "graph": graph, "formSchema": schema}, phase="seed", operation="create_definition")
        client.call("POST", "/process-definitions/" + draft["id"] + "/publish?expectedRevision=0", body={"changeNote": "隔离容量基线"}, phase="seed", operation="publish_definition")
        process_keys.append(key)
    def submit(index):
        user = "alice" if index % 2 == 0 else "bob"
        draft = client.call("POST", "/applications", user, {"businessNo": prefix + "-" + str(index), "title": "容量样本 " + str(index),
                            "processKey": process_keys[index % len(process_keys)], "definitionVersion": 1,
                            "payload": {"amount": str(index + 1) + ".01", "reason": "合成负载，不含真实业务数据。" * 25}},
                            201, "seed", "create_application")
        app = client.call("POST", "/applications/" + draft["id"] + "/submit", user, {"expectedVersion": draft["version"]}, phase="seed", operation="submit_application")
        check(app["status"] == "IN_APPROVAL", "SUBMISSION_STATE_MISMATCH")
        return {"id": app["id"], "index": index, "user": user}
    started = time.perf_counter()
    applications = parallel(submit, range(args.applications), args.seed_concurrency)
    report["seedWallSeconds"] = round(time.perf_counter() - started, 3)
    progress("SUBMITTED", {"applications": len(applications)})
    tasks = client.page("/workspace/tasks", "manager")
    check({row["applicationId"] for row in tasks} == {app["id"] for app in applications} and len(tasks) == len(applications), "INITIAL_TASK_SET_MISMATCH")
    tasks_by_app = {row["applicationId"]: row for row in tasks}
    approved = [app for app in applications if app["index"] % 4 == 0]
    def approve(app):
        task = client.call("GET", "/tasks/" + tasks_by_app[app["id"]]["taskId"], "manager", phase="actions", operation="read_task_for_action")
        client.call("POST", "/tasks/" + task["taskId"] + "/actions", "manager",
                    {"action": "APPROVE", "expectedVersion": task["version"], "comment": "容量样本批准"}, phase="actions", operation="approve_task")
    started = time.perf_counter()
    parallel(approve, approved, args.seed_concurrency)
    report["actionsWallSeconds"] = round(time.perf_counter() - started, 3)
    expected = {"applications": len(applications), "approved": len(approved), "pending": len(applications) - len(approved),
                "definitions": len(process_keys), "aliceApplications": sum(app["user"] == "alice" for app in applications)}
    report["expected"] = expected
    report["processKeys"] = process_keys
    endpoints = [("pending_first", "/workspace/tasks?limit=30", "manager"),
                 ("application_admin", "/operations/applications?limit=30", "admin"),
                 ("application_participant", "/applications/search?limit=30", "alice"),
                 ("definition_catalog", "/process-definitions/search?limit=30", "admin"),
                 ("approval_operations", "/operations/approvals", "admin"),
                 ("notification_inbox", "/notifications?limit=30", "manager")]
    first = client.call("GET", endpoints[0][1], "manager")
    if first.get("nextCursor"):
        endpoints.append(("pending_second", "/workspace/tasks?" + urlencode({"limit": 30, "cursor": first["nextCursor"]}), "manager"))
    def read(spec, phase):
        operation, path, user = spec
        value = client.call("GET", path, user, phase=phase, operation=operation)
        if operation == "approval_operations":
            check(value["metrics"]["submittedRounds"] == expected["applications"] and value["pendingTasks"] == expected["pending"], "MEASURED_OPERATIONS_MISMATCH")
        else:
            check(len(value["items"]) <= 30, "MEASURED_PAGE_TOO_LARGE")
            if operation.startswith("pending"):
                check(value["total"] == expected["pending"], "MEASURED_PENDING_COUNT_MISMATCH")
            check(all("payload" not in item and "graph" not in item for item in value["items"]), "SUMMARY_CONTAINS_FULL_CONTENT")
    for _ in range(args.warmup):
        for endpoint in endpoints:
            read(endpoint, "warmup")
    report["readPhases"] = []
    for concurrency in args.concurrency:
        phase = "read_c" + str(concurrency)
        jobs = endpoints * args.requests
        started = time.perf_counter()
        parallel(lambda endpoint: read(endpoint, phase), jobs, concurrency)
        elapsed = time.perf_counter() - started
        metrics = {"phase": phase, "concurrency": concurrency, "requests": len(jobs), "wallSeconds": round(elapsed, 3), "completedRequestsPerSecond": round(len(jobs) / elapsed, 3)}
        report["readPhases"].append(metrics)
        progress("MEASURED", metrics)
    rows = client.page("/operations/applications")
    check(len(rows) == expected["applications"] and {row["id"] for row in rows} == {app["id"] for app in applications}, "FINAL_APPLICATION_SET_MISMATCH")
    approved_ids = {app["id"] for app in approved}
    check(all(row["status"] == ("APPROVED" if row["id"] in approved_ids else "IN_APPROVAL") for row in rows), "FINAL_APPLICATION_STATES_MISMATCH")
    check(len(client.page("/applications/search", "alice")) == expected["aliceApplications"], "PARTICIPANT_PAGE_MISMATCH")
    check(not client.page("/applications/search", "employee") and not client.page("/workspace/tasks", "employee"), "UNRELATED_ACCOUNT_VISIBILITY")
    remaining = client.page("/workspace/tasks", "manager")
    check(len(remaining) == expected["pending"] and {row["applicationId"] for row in remaining} == {app["id"] for app in applications} - approved_ids, "FINAL_TASK_SET_MISMATCH")
    operations = client.call("GET", "/operations/approvals")
    check(operations["metrics"]["submittedRounds"] == expected["applications"] and operations["metrics"]["approved"] == expected["approved"] and operations["unrecordedHistoricalRounds"] == 0, "FINAL_ROUND_METRICS_MISMATCH")
    check(len(client.page("/process-definitions/search")) == expected["definitions"], "FINAL_DEFINITION_COUNT_MISMATCH")
    check(len(client.page("/notifications", "manager")) == expected["applications"], "FINAL_NOTIFICATION_COUNT_MISMATCH")
    report["verification"] = {"applicationStates": "EXACT_MATCH", "activeTaskSet": "EXACT_MATCH", "participantPaging": "PASS", "unrelatedAccount": "EMPTY", "roundMetrics": "EXACT_MATCH", "managerNotifications": "EXACT_MATCH", "definitionCount": "EXACT_MATCH"}


def arguments(argv=None):
    """在创建资源前一次性限制负载、镜像引用、端口和输出目录。"""
    parser = argparse.ArgumentParser(description="Create a private local demo workload; never target an existing service or database.")
    parser.add_argument("--server-image", required=True, help="An already-built local AgentFlow demo server image")
    parser.add_argument("--postgres-image", default="postgres:17")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--port", type=int, default=8192)
    parser.add_argument("--applications", type=int, default=1000)
    parser.add_argument("--definitions", type=int, default=20)
    parser.add_argument("--seed-concurrency", type=int, default=4)
    parser.add_argument("--concurrency", default="1,4,8")
    parser.add_argument("--requests", type=int, default=100, help="Measured requests per endpoint and concurrency level")
    parser.add_argument("--warmup", type=int, default=5)
    args = parser.parse_args(argv)
    try:
        args.concurrency = [int(value) for value in args.concurrency.split(",")]
        check(len(set(args.concurrency)) == len(args.concurrency) and 1 <= len(args.concurrency) <= 5 and all(1 <= value <= 16 for value in args.concurrency), "INVALID_CONCURRENCY")
        check(100 <= args.applications <= 10000 and 1 <= args.definitions <= min(args.applications, 100), "INVALID_DATA_SIZE")
        check(1 <= args.seed_concurrency <= 8 and 1 <= args.requests <= 500 and 1 <= args.warmup <= 20, "INVALID_REQUEST_COUNT")
        check(8190 <= args.port <= 8290, "INVALID_PREVIEW_PORT")
        for reference in (args.server_image, args.postgres_image):
            check(bool(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_./:@-]{0,255}", reference)), "INVALID_IMAGE_REFERENCE")
        args.output = args.output.absolute()
        check(args.output.parent.resolve().is_relative_to(Path("/fyoung/tmp").resolve()), "OUTPUT_MUST_BE_UNDER_FYOUNG_TMP")
        check(not args.output.exists() and not args.output.is_symlink(), "OUTPUT_ALREADY_EXISTS")
    except (ValueError, BaselineError) as error:
        parser.error(str(error))
    return args


def main(argv=None):
    """编排运行与停止，成功或失败均留下独立证据。"""
    args = arguments(argv)
    # 端口占用时在创建任何 Docker 资源前失败；Docker 绑定仍会复核竞争情况。
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", args.port))
    args.output.mkdir(mode=0o700)
    report = {"format": FORMAT, "startedAt": datetime.now(timezone.utc).isoformat(), "result": "INCOMPLETE",
              "configuration": {key: str(value) if isinstance(value, Path) else value for key, value in vars(args).items()},
              "client": {"python": platform.python_version(), "platform": platform.system(), "architecture": platform.machine()},
              "measurement": "Closed-loop bounded workers; one HTTP connection per request; client wall latency includes connect, response body and JSON decode; warmup excluded from measured phases; no production capacity claim."}
    samples, sandbox = [], Sandbox(args, args.output)
    def progress(event, details):
        line = {"at": datetime.now(timezone.utc).isoformat(), "event": event, **details}
        with (args.output / "progress.jsonl").open("a", encoding="utf-8") as stream:
            os.chmod(stream.name, 0o600)
            stream.write(json.dumps(line) + "\n")
        print(json.dumps(line), flush=True)
    try:
        sandbox.start()
        progress("READY", {"runId": sandbox.run_id})
        workload(Client(args.port, samples), args, report, progress)
        report["result"] = "PASS"
    except (BaselineError, KeyboardInterrupt) as error:
        report["failure"] = str(error) if isinstance(error, BaselineError) else "INTERRUPTED"
    except Exception:
        report["failure"] = "UNEXPECTED_RUN_FAILURE"
    finally:
        cleanup_errors = sandbox.stop()
        if cleanup_errors:
            report["result"] = "INCOMPLETE"
        report["cleanupErrors"] = cleanup_errors
        report["resources"] = sandbox.manifest
        report["summary"] = summarize(samples)
        report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        write_json(args.output / "samples.json", samples)
        write_json(args.output / "report.json", report)
        progress("FINISHED", {"result": report["result"], "failure": report.get("failure"), "cleanupErrors": cleanup_errors})
    return 0 if report["result"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
