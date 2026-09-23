"""独立验收站内消息与审批事务；创建随机数据并保留一张浏览器待办。"""
from concurrent.futures import ThreadPoolExecutor
import json
import sys
import urllib.error
import urllib.request
import uuid

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8082").rstrip("/") + "/api/v1"
PREFIX = "inbox-" + uuid.uuid4().hex[:10]


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


tokens = {user: request("POST", "/auth/login", body={"tenantId": "demo", "username": user, "password": "demo"})["token"]
          for user in ("admin", "alice", "bob", "finance", "employee")}
graph = {"nodes": [{"id": "start", "name": "开始", "type": "START", "properties": {}},
                   {"id": "review", "name": "财务审批", "type": "USER_TASK", "properties": {"assigneeRule": "role:FINANCE"}},
                   {"id": "end", "name": "结束", "type": "END", "properties": {}}],
         "edges": [{"id": "begin", "source": "start", "target": "review", "condition": ""},
                   {"id": "finish", "source": "review", "target": "end", "condition": ""}]}
schema = {"schemaVersion": 1, "fields": [{"key": "reason", "label": "申请说明", "type": "TEXTAREA", "required": True}]}
definition = request("POST", "/process-definitions", "admin", {"key": PREFIX, "name": "站内消息验收", "graph": graph, "formSchema": schema})
request("POST", "/process-definitions/" + definition["id"] + "/publish?expectedRevision=0", "admin", {"changeNote": "站内消息业务链路验收"})


def application(suffix):
    result = request("POST", "/applications", "alice", {"businessNo": PREFIX + "-" + suffix, "processKey": PREFIX,
        "definitionVersion": 1, "title": "站内消息验收 · " + suffix, "payload": {"reason": "通知摘要不应包含这段独立验收表单内容。"}}, 201)
    key = str(uuid.uuid4())
    submitted = request("POST", "/applications/" + result["id"] + "/submit", "alice", {"expectedVersion": 1}, key=key)
    assert request("POST", "/applications/" + result["id"] + "/submit", "alice", {"expectedVersion": 1}, key=key) == submitted
    return result


def messages(user, application_id):
    found, cursor, seen = [], None, set()
    while True:
        path = "/notifications?limit=30" + ("&cursor=" + cursor if cursor else "")
        page = request("GET", path, user)
        found.extend(row for row in page["items"] if row["applicationId"] == application_id)
        cursor = page.get("nextCursor")
        if not cursor:
            return found
        assert cursor not in seen
        seen.add(cursor)


def task(user, application_id):
    return next(row for row in request("GET", "/tasks", user) if row["applicationId"] == application_id)


value = application("HTTP闭环")
id_ = value["id"]
assert [row["kind"] for row in messages("alice", id_)] == ["APPLICATION_SUBMITTED"]
assert [row["kind"] for row in messages("finance", id_)] == ["TASK_PENDING"]
assert [row["kind"] for row in messages("admin", id_)] == ["TASK_PENDING"]
assert not messages("employee", id_)
initial = task("finance", id_)
path = "/tasks/" + initial["taskId"] + "/actions"
request("POST", path, "finance", {"action": "DELEGATE", "expectedVersion": 2, "targetUser": "bob"})
delegated = messages("bob", id_)
assert len(delegated) == 1 and delegated[0]["kind"] == "TASK_DELEGATED"
request("POST", path, "bob", {"action": "RESOLVE", "expectedVersion": 3, "comment": "已核对并回交"})
assert {row["kind"] for row in messages("finance", id_)} == {"TASK_PENDING", "TASK_RESOLVED"}
request("POST", path, "finance", {"action": "APPROVE", "expectedVersion": 4})
assert {row["kind"] for row in messages("alice", id_)} == {"APPLICATION_SUBMITTED", "APPLICATION_APPROVED"}
read_path = "/notifications/" + delegated[0]["id"] + "/read"
request("POST", read_path, "alice", expected=404)
request("POST", read_path, "admin", expected=404)
key = str(uuid.uuid4())
before_unread = request("GET", "/notifications?read=unread&limit=1", "bob")["unreadCount"]
with ThreadPoolExecutor(max_workers=4) as pool:
    reads = list(pool.map(lambda _: request("POST", read_path, "bob", key=key), range(4)))
assert all(row == reads[0] for row in reads) and reads[0]["readAt"]
assert request("POST", read_path, "bob") == reads[0]
assert request("GET", "/notifications?read=unread&limit=1", "bob")["unreadCount"] == before_unread - 1
withdrawn = application("撤回提醒")
request("POST", "/applications/" + withdrawn["id"] + "/withdraw", "alice", {"expectedVersion": 2, "comment": "撤回通知验收"})
assert {row["kind"] for row in messages("finance", withdrawn["id"])} == {"TASK_PENDING", "APPLICATION_WITHDRAWN"}
request("GET", "/applications/" + withdrawn["id"], "finance", expected=404)
browser = application("浏览器待办")
print(json.dumps({"processKey": PREFIX, "completedApplicationId": id_, "withdrawnApplicationId": withdrawn["id"],
    "browserApplicationId": browser["id"], "browserTaskId": task("finance", browser["id"])["taskId"],
    "concurrentReadRequests": len(reads), "readAt": reads[0]["readAt"],
    "applicantMessages": [row["kind"] for row in messages("alice", id_)], "result": "PASS"}, ensure_ascii=False, indent=2))
