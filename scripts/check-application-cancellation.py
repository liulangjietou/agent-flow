"""在明确指定的独立演示环境验证申请作废，保留全部数据及浏览器验收草稿。"""
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

if len(sys.argv) != 2:
    raise SystemExit("Usage: python3 scripts/check-application-cancellation.py http://127.0.0.1:8082")
origin = urllib.parse.urlparse(sys.argv[1])
if origin.scheme != "http" or origin.hostname not in ("127.0.0.1", "localhost", "::1") or origin.path not in ("", "/"):
    raise SystemExit("Use an isolated loopback demo environment")
BASE = sys.argv[1].rstrip("/") + "/api/v1"
PREFIX = "cancel-" + uuid.uuid4().hex[:10]
tokens = {}


def request(method, path, user=None, body=None, expected=200, key=None):
    headers = {"Content-Type": "application/json"}
    if user:
        headers["Authorization"] = "Bearer " + tokens[user]
    if method != "GET" and not path.startswith("/auth/"):
        headers["Idempotency-Key"] = key or str(uuid.uuid4())
    req = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body, ensure_ascii=False).encode(), headers=headers, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        value = json.load(response)
        if expected is None:
            return response.status, value
        assert response.status == expected, (path, response.status, value.get("code"))
        return value


for user in ("admin", "alice", "manager", "bob"):
    tokens[user] = request("POST", "/auth/login", body={"tenantId": "demo", "username": user, "password": "demo"})["token"]
graph = {"nodes": [
    {"id": "start", "name": "开始", "type": "START", "properties": {}},
    {"id": "review", "name": "部门审批", "type": "USER_TASK", "properties": {"assigneeRule": "role:MANAGER"}},
    {"id": "end", "name": "结束", "type": "END", "properties": {}}
], "edges": [{"id": "begin", "source": "start", "target": "review", "condition": ""}, {"id": "finish", "source": "review", "target": "end", "condition": ""}]}
schema = {"schemaVersion": 1, "fields": [{"key": "reason", "label": "申请事由", "type": "TEXTAREA", "required": True}]}
definition = request("POST", "/process-definitions", "admin", {"key": PREFIX, "name": "申请作废验收", "graph": graph, "formSchema": schema})
request("POST", "/process-definitions/" + definition["id"] + "/publish?expectedRevision=0", "admin", {"changeNote": "独立验收申请作废与历史保留"})


def draft(label, payload=None):
    return request("POST", "/applications", "alice", {"businessNo": PREFIX + "-" + label, "processKey": PREFIX,
        "definitionVersion": 1, "title": "作废验收 · " + label, "payload": payload if payload is not None else {"reason": "独立环境测试内容"}}, 201)


def application_path(value):
    return "/applications/" + value["id"]


def assert_terminal(value):
    path = application_path(value)
    request("POST", path + "/submit", "alice", {"expectedVersion": value["version"]}, 422)
    request("PUT", path, "alice", {"expectedVersion": value["version"], "title": "不能再修改", "payload": {"reason": "不能修改"}}, 422)
    request("POST", path + "/cancel", "alice", {"expectedVersion": value["version"]}, 422)
    assert request("GET", path, "alice") == value
    assert not any(row["applicationId"] == value["id"] for row in request("GET", "/tasks", "manager"))


value = draft("不完整草稿", {})
path = application_path(value)
body = {"expectedVersion": 1, "comment": "计划取消，无须补齐必填项"}
request("POST", path + "/cancel", "bob", body, 403)
request("POST", path + "/cancel", "admin", body, 403)
key = str(uuid.uuid4())
with ThreadPoolExecutor(max_workers=4) as pool:
    results = list(pool.map(lambda _: request("POST", path + "/cancel", "alice", body, key=key), range(4)))
assert all(row == results[0] for row in results)
cancelled = results[0]
assert cancelled["status"] == "CANCELLED" and cancelled["version"] == 2 and cancelled["payload"] == {}
assert request("GET", path + "/rounds", "alice") == []
assert len(request("GET", path + "/audit?action=CANCEL", "alice")["items"]) == 1
assert_terminal(cancelled)

preserved = []
for action in ("RETURN", "WITHDRAW"):
    value = draft(action)
    path = application_path(value)
    request("POST", path + "/submit", "alice", {"expectedVersion": 1})
    request("POST", path + "/cancel", "alice", {"expectedVersion": 2}, 422)
    if action == "RETURN":
        task = next(row for row in request("GET", "/tasks", "manager") if row["applicationId"] == value["id"])
        request("POST", "/tasks/" + task["taskId"] + "/actions", "manager", {"action": "RETURN", "expectedVersion": 2, "comment": "原退回意见必须保留"})
    else:
        request("POST", path + "/withdraw", "alice", {"expectedVersion": 2, "comment": "原撤回说明必须保留"})
    before = request("GET", path + "/rounds", "alice")
    metrics = request("GET", "/operations/approvals?processKey=" + PREFIX, "admin")["metrics"]
    cancelled = request("POST", path + "/cancel", "alice", {"expectedVersion": 3, "comment": "不再重新提交"})
    assert cancelled["status"] == "CANCELLED" and cancelled["version"] == 4
    assert request("GET", path + "/rounds", "alice") == before
    assert request("GET", "/operations/approvals?processKey=" + PREFIX, "admin")["metrics"] == metrics
    if action == "RETURN":
        assert request("GET", path, "manager")["status"] == "CANCELLED"
    assert_terminal(cancelled)
    preserved.append(value["id"])

value = draft("并发提交与作废")
path = application_path(value)
barrier = Barrier(2)


def compete(action):
    barrier.wait(timeout=10)
    return request("POST", path + "/" + action, "alice", {"expectedVersion": 1}, expected=None)


with ThreadPoolExecutor(max_workers=2) as pool:
    results = list(pool.map(compete, ("cancel", "submit")))
assert sorted(status for status, _ in results) == [200, 409], [(status, body.get("code")) for status, body in results]
current = request("GET", path, "alice")
assert current["version"] == 2
history = request("GET", path + "/rounds", "alice")
pending = [row for row in request("GET", "/tasks", "manager") if row["applicationId"] == value["id"]]
assert (current["status"], len(history), len(pending)) in (("CANCELLED", 0, 0), ("IN_APPROVAL", 1, 1))

# 浏览器通过真实 UI 完成确认、未保存保护与网络恢复；脚本只准备可辨认的合成数据。
browser_draft = draft("浏览器草稿", {})
browser_returned = draft("浏览器退回")
path = application_path(browser_returned)
request("POST", path + "/submit", "alice", {"expectedVersion": 1})
task = next(row for row in request("GET", "/tasks", "manager") if row["applicationId"] == browser_returned["id"])
request("POST", "/tasks/" + task["taskId"] + "/actions", "manager", {"action": "RETURN", "expectedVersion": 2, "comment": "浏览器验收原审批意见"})
print(json.dumps({"result": "PASS", "base": BASE, "processKey": PREFIX, "preservedRounds": preserved,
    "raceWinner": current["status"], "browserDraft": browser_draft["id"], "browserReturned": browser_returned["id"]}, ensure_ascii=False))
