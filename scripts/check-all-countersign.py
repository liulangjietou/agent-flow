#!/usr/bin/env python3
"""在独立本地演示库验证全员会签，保留随机业务记录，不打印会话令牌。"""
import json
import sys
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor
from urllib.error import HTTPError
from urllib.parse import urlparse
from urllib.request import Request, urlopen

base = sys.argv[1].rstrip("/") if len(sys.argv) > 1 else ""
if urlparse(base).hostname not in ("localhost", "127.0.0.1", "::1") or sys.argv[2:] != ["--exercise"]:
    raise SystemExit("Use an isolated loopback demo: check-all-countersign.py http://127.0.0.1:8082 --exercise")
tokens = {}


def request(method, path, user="admin", body=None, expected=200, key=None):
    headers = {"Content-Type": "application/json"}
    if user in tokens:
        headers["Authorization"] = "Bearer " + tokens[user]
    if method != "GET":
        headers["Idempotency-Key"] = key or str(uuid.uuid4())
    raw = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    try:
        response = urlopen(Request(base + "/api/v1" + path, data=raw, headers=headers, method=method), timeout=20)
    except HTTPError as error:
        response = error
    with response:
        text = response.read()
        value = json.loads(text) if text else None
        allowed = expected if isinstance(expected, tuple) else (expected,)
        assert response.status in allowed, (method, path, response.status, value)
        return (response.status, value) if isinstance(expected, tuple) else value


def graph(mode):
    return {"nodes": [
        {"id": "start", "name": "开始", "type": "START", "properties": {"x": "40", "y": "180"}},
        {"id": "review", "name": "财务会签", "type": "USER_TASK", "properties": {
            "assigneeRule": "role:FINANCE", "approvalMode": mode, "x": "260", "y": "180"}},
        {"id": "end", "name": "结束", "type": "END", "properties": {"x": "500", "y": "180"}}
    ], "edges": [{"id": "first", "source": "start", "target": "review", "condition": ""},
                 {"id": "last", "source": "review", "target": "end", "condition": ""}]}


def create(mode="ALL", name="全员会签运行验收"):
    return request("POST", "/process-definitions", body={"key": "countersign-" + uuid.uuid4().hex[:10],
                   "name": name, "graph": graph(mode)})


def submit(definition):
    application = request("POST", "/applications", user="alice", expected=201, body={
        "businessNo": "CS-" + uuid.uuid4().hex[:10], "title": "全员会签验收 " + uuid.uuid4().hex[:6],
        "processKey": definition["key"], "definitionVersion": definition["version"], "payload": {}})
    return request("POST", f"/applications/{application['id']}/submit", user="alice",
                   body={"expectedVersion": application["version"]})


def tasks(user, application):
    return [task for task in request("GET", "/tasks", user=user) if task["applicationId"] == application["id"]]


def act(task, user, action="APPROVE", version=None, expected=200, key=None, target=None):
    body = {"action": action, "expectedVersion": task["version"] if version is None else version, "comment": "会签运行验收意见"}
    if target:
        body["targetUser"] = target
    return request("POST", f"/tasks/{task['taskId']}/actions", user=user, body=body, expected=expected, key=key)


for username in ("admin", "alice", "bob", "finance"):
    tokens[username] = request("POST", "/auth/login", user="anonymous", body={
        "tenantId": "demo", "username": username, "password": "demo"})["token"]
draft = create()
definition = request("POST", f"/process-definitions/{draft['id']}/publish?expectedRevision=0",
                     body={"changeNote": "全部同意才通过，任一驳回结束整轮"})
application = submit(definition)
finance, = tasks("finance", application)
admin, = tasks("admin", application)
assert finance["taskId"] != admin["taskId"] and finance["countersign"] == {"total": 2, "completed": 0}
assert not tasks("bob", application)
for action in ("TRANSFER", "RELEASE", "CLAIM"):
    assert act(finance, "finance", action, expected=409, target="bob")["code"] == "COUNTERSIGN_ASSIGNMENT_FIXED"
act(finance, "bob", expected=403)
replay_key = str(uuid.uuid4())
first = act(finance, "finance", key=replay_key)
assert first["applicationStatus"] == "IN_APPROVAL"
assert act(finance, "finance", key=replay_key) == first
admin, = tasks("admin", application)
assert admin["countersign"] == {"total": 2, "completed": 1}
assert act(admin, "admin")["applicationStatus"] == "APPROVED"
assert not tasks("admin", application) and not tasks("finance", application)

negative = {}
for action in ("REJECT", "RETURN", "WITHDRAW"):
    current = submit(definition)
    finance, = tasks("finance", current)
    admin, = tasks("admin", current)
    if action == "WITHDRAW":
        result = request("POST", f"/applications/{current['id']}/withdraw", user="alice",
                         body={"expectedVersion": current["version"], "comment": "补充材料"})
    else:
        result = act(finance, "finance", action)
    assert not tasks("admin", current) and not tasks("finance", current)
    act(admin, "admin", expected=404)
    rounds = request("GET", f"/applications/{current['id']}/rounds", user="alice")
    state = {"REJECT": "REJECTED", "RETURN": "RETURNED", "WITHDRAW": "WITHDRAWN"}[action]
    assert rounds[0]["status"] == state
    if action != "REJECT":
        request("POST", f"/applications/{current['id']}/submit", user="alice", body={"expectedVersion": result["version"]})
        assert len(tasks("admin", current)) == len(tasks("finance", current)) == 1
        assert request("GET", f"/applications/{current['id']}/rounds", user="alice")[0] == rounds[0]
    negative[action] = current["id"]

concurrent = submit(definition)
finance, = tasks("finance", concurrent)
admin, = tasks("admin", concurrent)
barrier = threading.Barrier(2)


def simultaneous(task, user):
    barrier.wait(timeout=5)
    return act(task, user, expected=(200, 409))


with ThreadPoolExecutor(max_workers=2) as executor:
    futures = [executor.submit(simultaneous, finance, "finance"), executor.submit(simultaneous, admin, "admin")]
    results = [future.result(timeout=25) for future in futures]
assert sorted(code for code, _ in results) == [200, 409]
for user in ("admin", "finance"):
    remaining = tasks(user, concurrent)
    if remaining:
        assert remaining[0]["countersign"] == {"total": 2, "completed": 1}
        assert act(remaining[0], user)["applicationStatus"] == "APPROVED"

browser = create("SINGLE", "会签设计器验收 " + uuid.uuid4().hex[:6])
print(json.dumps({"result": "PASS", "definitionId": definition["id"], "approvedApplication": application["id"],
                  "negativeApplications": negative, "concurrentApplication": concurrent["id"],
                  "concurrentStatuses": [code for code, _ in results], "browserDraft": browser["id"],
                  "browserKey": browser["key"], "browserName": browser["name"]}, ensure_ascii=False, indent=2))
