"""在独立验收环境验证委派回交；保留数据并留一张待办供浏览器验收。"""
from concurrent.futures import ThreadPoolExecutor
import json
import sys
import urllib.error
import urllib.request
import uuid

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8082").rstrip("/") + "/api/v1"
PREFIX = "delegation-" + uuid.uuid4().hex[:10]


def request(method, path, user=None, body=None, expected=200, key=None):
    headers = {"Content-Type": "application/json"}
    if user:
        headers["Authorization"] = "Bearer " + tokens[user]
    if method != "GET" and not path.startswith("/auth/"):
        headers["Idempotency-Key"] = key or str(uuid.uuid4())
    req = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        value = json.load(response)
        assert response.status == expected, (path, response.status, value)
        return value


tokens = {user: request("POST", "/auth/login", body={"tenantId": "demo", "username": user, "password": "demo"})["token"] for user in ("admin", "alice", "bob", "finance", "employee")}
graph = {"nodes": [
    {"id": "start", "name": "开始", "type": "START", "properties": {}},
    {"id": "review", "name": "财务审批", "type": "USER_TASK", "properties": {"assigneeRule": "role:FINANCE"}},
    {"id": "end", "name": "结束", "type": "END", "properties": {}}
], "edges": [
    {"id": "begin", "source": "start", "target": "review", "condition": ""},
    {"id": "finish", "source": "review", "target": "end", "condition": ""}
]}
schema = {"schemaVersion": 1, "fields": [{"key": "reason", "label": "申请事由", "type": "TEXTAREA", "required": True}]}
definition = request("POST", "/process-definitions", "admin", {"key": PREFIX, "name": "委派回交验收", "graph": graph, "formSchema": schema})
request("POST", "/process-definitions/" + definition["id"] + "/publish?expectedRevision=0", "admin", {"changeNote": "独立验收：委派、回交、原审批人最终决定"})


def application(suffix):
    value = request("POST", "/applications", "alice", {"businessNo": PREFIX + "-" + suffix, "processKey": PREFIX,
        "definitionVersion": 1, "title": "委派回交验收 · " + suffix, "payload": {"reason": "请核对附件之外的示例申请说明，无真实业务数据。"}}, 201)
    request("POST", "/applications/" + value["id"] + "/submit", "alice", {"expectedVersion": 1})
    return value


def task(user, application_id):
    return next(row for row in request("GET", "/tasks", user) if row["applicationId"] == application_id)


value = application("HTTP闭环")
initial = task("finance", value["id"])
path = "/tasks/" + initial["taskId"]
users = request("GET", path + "/recipients", "finance")
assert "bob" in users and "finance" not in users
request("GET", path + "/recipients", "employee", expected=403)
request("POST", path + "/actions", "finance", {"action": "DELEGATE", "expectedVersion": 2, "targetUser": "missing"}, 422)
request("POST", path + "/actions", "finance", {"action": "DELEGATE", "expectedVersion": 2, "targetUser": "bob", "comment": "请补充核实意见"})
pending = task("bob", value["id"])
assert pending["owner"] == "finance" and pending["delegationState"] == "PENDING" and pending["allowedActions"] == ["RESOLVE"]
for action in ("APPROVE", "RETURN", "REJECT", "TRANSFER", "DELEGATE", "RELEASE"):
    request("POST", path + "/actions", "bob", {"action": action, "expectedVersion": 3, "targetUser": "alice", "comment": "不能越过原审批人"}, 409)
body = {"action": "RESOLVE", "expectedVersion": 3, "targetUser": "forged", "comment": "已核对，请原审批人作最终决定"}
key = str(uuid.uuid4())
with ThreadPoolExecutor(max_workers=4) as pool:
    responses = list(pool.map(lambda _: request("POST", path + "/actions", "bob", body, key=key), range(4)))
assert all(row == responses[0] for row in responses) and responses[0]["applicationStatus"] == "IN_APPROVAL"
resolved = task("finance", value["id"])
assert resolved["delegationState"] == "RESOLVED" and resolved["assignee"] == "finance"
request("POST", path + "/actions", "finance", {"action": "APPROVE", "expectedVersion": resolved["version"], "comment": "核对受托意见后批准"})
assert request("POST", path + "/actions", "bob", body, key=key) == responses[0]
handled = request("GET", "/workspace/handled?action=RESOLVE&q=" + PREFIX, "bob")["items"]
assert len(handled) == 1 and handled[0]["applicationStatus"] == "APPROVED" and handled[0]["handledStatus"] == "IN_APPROVAL"
assert handled[0]["targetUser"] == "finance"
request("GET", "/applications/" + value["id"], "bob")
request("GET", "/applications/" + value["id"], "employee", expected=404)
browser = application("浏览器闭环")
print(json.dumps({"processKey": PREFIX, "completedApplicationId": value["id"], "concurrentResolutions": 4,
    "resolveAudit": handled[0], "browserApplicationId": browser["id"], "browserTaskId": task("finance", browser["id"])["taskId"]}, ensure_ascii=False, indent=2))
