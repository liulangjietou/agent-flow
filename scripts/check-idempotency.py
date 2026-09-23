"""通过真实 HTTP 验证幂等重放及并发，只创建带随机前缀的本地演示验收数据。"""

from concurrent.futures import ThreadPoolExecutor
import json
import sys
import urllib.error
import urllib.request
import uuid

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080").rstrip("/") + "/api/v1"
PREFIX = "idem-" + uuid.uuid4().hex[:12]


def request(method, path, actor=None, body=None, key=None):
    data = None if body is None else json.dumps(body, separators=(",", ":")).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if actor:
        req.add_header("Authorization", actor)
    if key:
        req.add_header("Idempotency-Key", key)
    try:
        response = urllib.request.urlopen(req, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read().decode()
        return response.status, raw, response.headers.get("Idempotency-Replayed")


def read(path, actor):
    status, raw, _ = request("GET", path, actor)
    assert status == 200, (path, status, raw)
    return json.loads(raw)


def login(username):
    status, raw, _ = request("POST", "/auth/login", body={"tenantId": "demo", "username": username, "password": "demo"})
    assert status == 200, (username, status)
    return "Bearer " + json.loads(raw)["token"]


def repeat(method, path, actor, body=None, status=200, concurrent=False):
    key = str(uuid.uuid4())
    count = 8 if concurrent else 2
    if concurrent:
        with ThreadPoolExecutor(max_workers=count) as pool:
            responses = list(pool.map(lambda _: request(method, path, actor, body, key), range(count)))
    else:
        responses = [request(method, path, actor, body, key) for _ in range(count)]
    assert all(response[0] == status for response in responses), responses
    assert len({response[1] for response in responses}) == 1, responses
    assert sum(response[2] == "false" for response in responses) == 1, responses
    assert sum(response[2] == "true" for response in responses) == count - 1, responses
    return json.loads(responses[0][1]), key


def main():
    admin, employee, manager, outsider = (login(user) for user in ("admin", "employee", "manager", "bob"))
    graph = {
        "nodes": [
            {"id": "start", "name": "开始", "type": "START", "properties": {}},
            {"id": "manager", "name": "部门审批", "type": "USER_TASK", "properties": {"assigneeRule": "role:MANAGER"}},
            {"id": "end", "name": "结束", "type": "END", "properties": {}},
        ],
        "edges": [
            {"id": "first", "source": "start", "target": "manager", "condition": "", "defaultBranch": False},
            {"id": "last", "source": "manager", "target": "end", "condition": "", "defaultBranch": False},
        ],
    }
    definition, _ = repeat("POST", "/process-definitions", admin, {"key": PREFIX, "name": "幂等协议验收", "graph": graph}, concurrent=True)
    definition, _ = repeat("PUT", "/process-definitions/" + definition["id"], admin,
                           {"name": "幂等协议验收已更新", "graph": graph, "expectedRevision": definition["revision"]})
    published, _ = repeat("POST", f"/process-definitions/{definition['id']}/publish?expectedRevision={definition['revision']}", admin, {"changeNote": "幂等发布验收"}, concurrent=True)
    assert published["version"] == 1
    body = {"businessNo": PREFIX.upper(), "processKey": PREFIX, "definitionVersion": 1, "title": "幂等网络验收", "payload": {"amount": 80}}
    missing = request("POST", "/applications", employee, body)
    assert missing[0] == 400 and json.loads(missing[1])["code"] == "IDEMPOTENCY_KEY_REQUIRED", missing
    app, create_key = repeat("POST", "/applications", employee, body, status=201, concurrent=True)
    changed = request("POST", "/applications", employee, {**body, "title": "不同请求"}, create_key)
    assert changed[0] == 409 and json.loads(changed[1])["code"] == "IDEMPOTENCY_KEY_REUSED", changed
    swapped = request("POST", "/applications", outsider, body, create_key)
    assert swapped[0] == 409 and json.loads(swapped[1])["code"] == "IDEMPOTENCY_KEY_REUSED", swapped
    app_path = "/applications/" + app["id"]
    app, _ = repeat("PUT", app_path, employee, {"title": "幂等网络验收已更新", "payload": {"amount": 95}, "expectedVersion": app["version"]})
    first_submit_body = {"expectedVersion": app["version"]}
    app, first_submit_key = repeat("POST", app_path + "/submit", employee, first_submit_body, concurrent=True)
    app, _ = repeat("POST", app_path + "/withdraw", employee, {"expectedVersion": app["version"], "comment": "验证同键撤回仅执行一次"}, concurrent=True)
    app, _ = repeat("POST", app_path + "/submit", employee, {"expectedVersion": app["version"]}, concurrent=True)
    task = next(task for task in read("/tasks", manager) if task["applicationId"] == app["id"])
    result, _ = repeat("POST", f"/tasks/{task['taskId']}/actions", manager,
                       {"action": "APPROVE", "expectedVersion": task["version"], "comment": "验证已完成任务仍可回放"}, concurrent=True)
    assert result["applicationStatus"] == "APPROVED"
    stale = request("POST", app_path + "/submit", employee, first_submit_body, first_submit_key)
    assert stale[0] == 200 and stale[2] == "true" and json.loads(stale[1])["roundNo"] == 1, stale
    rounds = read(app_path + "/rounds", employee)
    audit = read(app_path + "/audit?limit=100", employee)["items"]
    assert [item["status"] for item in rounds] == ["WITHDRAWN", "APPROVED"], rounds
    assert len({item["processInstanceId"] for item in rounds}) == 2
    assert len(audit) == 6, audit
    assert sum(item["action"] == "APPROVE" for item in audit) == 1
    assert not any(item["applicationId"] == app["id"] for item in read("/tasks", manager))
    print(json.dumps({"baseUrl": BASE, "businessNo": body["businessNo"], "applicationId": app["id"],
                      "definitionId": definition["id"], "status": "APPROVED", "auditCount": len(audit),
                      "rounds": [item["status"] for item in rounds], "parallelRequestsPerOperation": 8,
                      "writeEndpointsVerified": 8, "historicalResponseReplayed": True}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
