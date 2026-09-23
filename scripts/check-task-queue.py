"""在独立验收库验证真实待办筛选、分页、单项读取与办理流转，保留浏览器样本。"""
from decimal import Decimal
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8082").rstrip("/") + "/api/v1"
PREFIX = "queue-" + uuid.uuid4().hex[:10]


def request(method, path, user=None, body=None, expected=200):
    headers = {"Content-Type": "application/json"}
    if user:
        headers["Authorization"] = "Bearer " + tokens[user]
    if method != "GET" and not path.startswith("/auth/"):
        headers["Idempotency-Key"] = str(uuid.uuid4())
    req = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        value = json.load(response)
        assert response.status == expected, (path, response.status, value)
        return value


def queue(user="finance", **filters):
    return request("GET", "/workspace/tasks?" + urllib.parse.urlencode({"processKey": PREFIX, **filters}), user)


tokens = {user: request("POST", "/auth/login", body={"tenantId": "demo", "username": user, "password": "demo"})["token"]
          for user in ("admin", "alice", "bob", "finance", "employee")}
graph = {"nodes": [{"id": "start", "name": "开始", "type": "START", "properties": {}},
                   {"id": "review", "name": "财务复核", "type": "USER_TASK", "properties": {"assigneeRule": "role:FINANCE"}},
                   {"id": "end", "name": "结束", "type": "END", "properties": {}}],
         "edges": [{"id": "begin", "source": "start", "target": "review", "condition": ""},
                   {"id": "finish", "source": "review", "target": "end", "condition": ""}]}
schema = {"schemaVersion": 1, "fields": [{"key": "amount", "label": "金额", "type": "NUMBER", "required": False},
                                       {"key": "reason", "label": "申请说明", "type": "TEXTAREA", "required": True}]}
definition = request("POST", "/process-definitions", "admin", {"key": PREFIX, "name": "待办检索验收", "graph": graph, "formSchema": schema})
request("POST", "/process-definitions/" + definition["id"] + "/publish?expectedRevision=0", "admin", {"changeNote": "待办搜索、筛选与分页验收"})
applications = []
for index in range(36):
    user = "bob" if index % 2 else "alice"
    payload = {"reason": "这段完整正文不应出现在待办列表内。"}
    if index < 35:
        payload["amount"] = str(Decimal(index * 10) + Decimal("0.01"))
    result = request("POST", "/applications", user, {"businessNo": PREFIX + "-" + str(index).zfill(2), "processKey": PREFIX,
        "definitionVersion": 1, "title": "办公申请 %_ " + str(index).zfill(2), "payload": payload}, 201)
    request("POST", "/applications/" + result["id"] + "/submit", user, {"expectedVersion": 1})
    applications.append(result["id"])
first = queue()
assert len(first["items"]) == 30 and first["total"] == 36
second = queue(cursor=first["nextCursor"])
assert len(second["items"]) == 6 and not second.get("nextCursor")
assert {row["applicationId"] for row in first["items"] + second["items"]} == set(applications)
assert "完整正文" not in json.dumps(first, ensure_ascii=False) and "payload" not in json.dumps(first)
assert queue(applicant="alice")["total"] == 18
assert queue(minAmount="100.01", maxAmount="200.01")["total"] == 11
assert queue(minAmount="0")["total"] == 35
assert queue(q="%_")["total"] == 36
assert queue("employee")["total"] == 0
path = "/workspace/tasks?" + urllib.parse.urlencode({"processKey": PREFIX, "cursor": first["nextCursor"]})
request("GET", path, "bob", expected=400)
current = request("GET", "/tasks/" + first["items"][0]["taskId"], "finance")
task_path = "/tasks/" + current["taskId"]
request("POST", task_path + "/actions", "finance", {"action": "CLAIM", "expectedVersion": 2})
assert queue("admin")["total"] == 35
assert queue(assignment="assigned")["total"] == 1
request("POST", task_path + "/actions", "finance", {"action": "DELEGATE", "expectedVersion": 3, "targetUser": "bob"})
assert queue()["total"] == 35 and queue("bob", assignment="delegated")["total"] == 1
request("GET", task_path, "finance", expected=403)
request("POST", task_path + "/actions", "bob", {"action": "RESOLVE", "expectedVersion": 4, "comment": "检索后核对并回交"})
request("POST", task_path + "/actions", "finance", {"action": "APPROVE", "expectedVersion": 5})
request("GET", task_path, "finance", expected=404)
assert queue()["total"] == 35
browser = queue(q="%_ 01")["items"][0]
print(json.dumps({"result": "PASS", "processKey": PREFIX, "initialTotal": 36, "pageSizes": [30, 6], "remainingTotal": 35,
                  "completedTaskId": current["taskId"], "browserTaskId": browser["taskId"], "browserApplicationId": browser["applicationId"],
                  "browserBusinessNo": browser["businessNo"], "browserApplicant": browser["applicant"], "browserAmount": browser["amount"]}, ensure_ascii=False, indent=2))
