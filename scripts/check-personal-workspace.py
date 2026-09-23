"""独立验收库的个人工作台检查；创建随机前缀的申请并保留验收数据。"""
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8082").rstrip("/") + "/api/v1"
PREFIX = "workspace-" + uuid.uuid4().hex[:10]


def request(method, path, user=None, body=None, expected=200):
    headers = {"Content-Type": "application/json"}
    if user:
        headers["Authorization"] = "Bearer " + tokens[user]
    if method not in ("GET",) and not path.startswith("/auth/"):
        headers["Idempotency-Key"] = str(uuid.uuid4())
    req = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        value = json.load(response)
        assert response.status == expected, (path, response.status, value)
        return value


def query(path, user, **filters):
    return request("GET", "/workspace/" + path + "?" + urllib.parse.urlencode(filters), user)


tokens = {user: request("POST", "/auth/login", body={"tenantId": "demo", "username": user, "password": "demo"})["token"] for user in ("admin", "alice", "bob", "manager", "employee")}
graph = {"nodes": [
    {"id": "start", "name": "开始", "type": "START", "properties": {}},
    {"id": "manager", "name": "经理审批", "type": "USER_TASK", "properties": {"assigneeRule": "user:manager"}},
    {"id": "end", "name": "结束", "type": "END", "properties": {}}
], "edges": [
    {"id": "begin", "source": "start", "target": "manager", "condition": ""},
    {"id": "finish", "source": "manager", "target": "end", "condition": ""}
]}
schema = {"schemaVersion": 1, "fields": [{"key": "reason", "label": "申请事由", "type": "TEXTAREA", "required": True, "maxLength": 1000}]}
definition = request("POST", "/process-definitions", "admin", {"key": PREFIX, "name": "个人工作台验收", "graph": graph, "formSchema": schema})
request("POST", "/process-definitions/" + definition["id"] + "/publish?expectedRevision=0", "admin", {"changeNote": "独立验收：个人归属、真实办理与分页"})


def draft(user, title):
    return request("POST", "/applications", user, {"businessNo": PREFIX + "-" + uuid.uuid4().hex[:8], "processKey": PREFIX, "definitionVersion": 1, "title": title, "payload": {"reason": "工作台表单私有内容，不应进入列表"}}, 201)


for number in range(32):
    draft("alice", "工作台草稿 %02d" % (number + 1))
other = draft("bob", "其他申请人的草稿")
created = []
for action in ("APPROVE", "RETURN", "REJECT", "TRANSFER"):
    application = draft("alice", {"APPROVE": "办公用品采购申请", "RETURN": "待补充合同审批", "REJECT": "已驳回的申请", "TRANSFER": "转交后保留办理记录"}[action])
    request("POST", "/applications/" + application["id"] + "/submit", "alice", {"expectedVersion": 1})
    task = next(item for item in request("GET", "/tasks", "manager") if item["applicationId"] == application["id"])
    request("POST", "/tasks/" + task["taskId"] + "/actions", "manager", {"action": action, "expectedVersion": task["version"], "targetUser": "bob", "comment": "已核对本次申请，保留真实办理意见。"})
    request("GET", "/applications/" + application["id"], "manager")
    created.append(application["id"])
request("GET", "/applications/" + created[-1], "employee", expected=404)
page = query("applications", "alice", q=PREFIX, limit=30)
assert len(page["items"]) == 30 and page["nextCursor"]
second = query("applications", "alice", q=PREFIX, cursor=page["nextCursor"], limit=30)
assert len(second["items"]) == 6 and second.get("nextCursor") is None
items = page["items"] + second["items"]
assert len({row["id"] for row in items}) == 36
assert other["id"] not in {row["id"] for row in items}
assert "payload" not in json.dumps(items) and "formSchema" not in json.dumps(items)
assert query("applications", "admin", q=PREFIX)["items"] == []
assert len(query("applications", "alice", q=PREFIX, view="drafts", limit=100)["items"]) == 32
handled = query("handled", "manager", q=PREFIX)
assert len(handled["items"]) == 4
assert {row["action"] for row in handled["items"]} == {"APPROVE", "RETURN", "REJECT", "TRANSFER"}
assert query("handled", "employee", q=PREFIX)["items"] == []
assert len(query("handled", "manager", q=PREFIX, action="TRANSFER")["items"]) == 1
request("GET", "/workspace/applications?tenantId=other", "alice", expected=400)
request("GET", "/workspace/handled", expected=401)
print(json.dumps({"processKey": PREFIX, "ownApplications": 36, "drafts": 32, "handled": 4, "pages": [30, 6], "applicationIds": created}, ensure_ascii=False, indent=2))
