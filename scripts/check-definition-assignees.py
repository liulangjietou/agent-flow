#!/usr/bin/env python3
"""在独立演示库验收指定审批人、无人发布拦截与版本隔离，保留全部随机测试记录。"""
import json
import sys
import uuid
from urllib.error import HTTPError
from urllib.parse import urlparse
from urllib.request import Request, urlopen

base = sys.argv[1].rstrip("/") if len(sys.argv) > 1 else ""
if urlparse(base).hostname not in ("localhost", "127.0.0.1", "::1") or sys.argv[2:] != ["--exercise"]:
    raise SystemExit("Use an isolated loopback demo: check-definition-assignees.py http://127.0.0.1:8082 --exercise")
tokens = {}


def request(method, path, user="admin", body=None, expected=200, key=None):
    headers = {"Content-Type": "application/json"}
    if user in tokens:
        headers["Authorization"] = "Bearer " + tokens[user]
    if method != "GET":
        headers["Idempotency-Key"] = key or str(uuid.uuid4())
    raw = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    try:
        response = urlopen(Request(base + "/api/v1" + path, data=raw, headers=headers, method=method), timeout=15)
    except HTTPError as error:
        response = error
    with response:
        text = response.read()
        value = json.loads(text) if text else None
        assert response.status == expected, (method, path, response.status, value)
        return value


def graph(rule):
    return {"nodes": [
        {"id": "start", "name": "开始", "type": "START", "properties": {"x": "40", "y": "180"}},
        {"id": "review", "name": "指定审批", "type": "USER_TASK", "properties": {"assigneeRule": rule, "x": "260", "y": "180"}},
        {"id": "end", "name": "结束", "type": "END", "properties": {"x": "500", "y": "180"}}
    ], "edges": [
        {"id": "first", "source": "start", "target": "review", "condition": ""},
        {"id": "last", "source": "review", "target": "end", "condition": ""}
    ]}


def create(key, rule, name="指定审批人验收"):
    return request("POST", "/process-definitions", body={"key": key, "name": name, "graph": graph(rule)})


def publish(definition, expected=200, key=None):
    return request("POST", f"/process-definitions/{definition['id']}/publish?expectedRevision={definition['revision']}",
                   body={"changeNote": "指定审批人运行验收"}, expected=expected, key=key)


def submit(definition):
    application = request("POST", "/applications", user="alice", expected=201, body={
        "businessNo": "ASSIGNEE-" + str(uuid.uuid4()), "title": "指定审批人运行验收",
        "processKey": definition["key"], "definitionVersion": definition["version"], "payload": {"amount": "10"}})
    return request("POST", f"/applications/{application['id']}/submit", user="alice", body={"expectedVersion": application["version"]})


def tasks(user, application):
    return [task for task in request("GET", "/tasks", user=user) if task["applicationId"] == application["id"]]


for username in ("admin", "alice", "bob", "manager", "finance"):
    tokens[username] = request("POST", "/auth/login", user="anonymous", body={"tenantId": "demo", "username": username, "password": "demo"})["token"]
directory = "/process-definitions/assignee-options"
request("GET", directory, user="anonymous", expected=401)
request("GET", directory, user="alice", expected=403)
options = {option["rule"]: option for option in request("GET", directory)}
assert options["user:bob"]["memberCount"] == 1 and options["role:FINANCE"]["memberCount"] == 2

suffix = uuid.uuid4().hex[:8]
for rule in ("user:missing-user", "role:MISSING_ROLE"):
    invalid = create("missing-" + uuid.uuid4().hex[:8], rule, "审批人待修正验收")
    check = request("POST", "/process-definitions/validate", body={"graph": graph(rule)})
    assert check["errors"] == ["ASSIGNEE_NOT_AVAILABLE:review"]
    denied = publish(invalid, expected=422)
    assert denied["code"] == "INVALID_DEFINITION"
    assert denied["details"]["definitionErrors"] == check["errors"]
    after = request("GET", "/process-definitions/" + invalid["id"])
    assert after == invalid
    request("GET", f"/process-definitions/{invalid['id']}/publication", expected=422)

version1 = publish(create("assignee-" + suffix, "user:bob"))
application1 = submit(version1)
task1, = tasks("bob", application1)
assert task1["assignee"] == "bob"
for user in ("manager", "finance", "admin"):
    assert not tasks(user, application1)
request("POST", f"/tasks/{task1['taskId']}/actions", user="manager", expected=403,
        body={"action": "APPROVE", "expectedVersion": task1["version"]})

draft2 = create(version1["key"], "role:FINANCE")
publish_key = str(uuid.uuid4())
version2 = publish(draft2, key=publish_key)
assert version2["version"] == 2 and publish(draft2, key=publish_key) == version2
assert tasks("bob", application1)[0]["assignee"] == "bob"
application2 = submit(version2)
task2, = tasks("finance", application2)
assert not tasks("bob", application2)
for user, task, application in (("bob", task1, application1), ("finance", task2, application2)):
    result = request("POST", f"/tasks/{task['taskId']}/actions", user=user,
                     body={"action": "APPROVE", "expectedVersion": task["version"]})
    assert result["applicationStatus"] == "APPROVED"
    assert request("GET", "/applications/" + application["id"], user="alice")["status"] == "APPROVED"

# 保留一个不存在审批人的草稿供真实浏览器编辑、定位错误和发布验收。
browser = create("assignee-ui-" + suffix, "user:missing-user", "审批人浏览器验收 " + suffix)
print(json.dumps({"result": "PASS", "options": len(options), "processKey": version1["key"],
                  "versions": [version1["version"], version2["version"]],
                  "approvedApplications": [application1["id"], application2["id"]],
                  "browserDraft": browser["id"], "browserKey": browser["key"], "browserName": browser["name"]}, ensure_ascii=False, indent=2))
