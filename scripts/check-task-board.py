"""在独立本机环境生成真实待办分栏与分页样本，保留记录供浏览器验收。"""
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

origin = urllib.parse.urlsplit(sys.argv[1])
assert origin.hostname in ("localhost", "127.0.0.1", "::1") and origin.path in ("", "/") and sys.argv[2] == "--exercise"
base = sys.argv[1].rstrip("/") + "/api/v1"
prefix = "board-" + uuid.uuid4().hex[:10]
tokens = {}


def request(method, path, user=None, body=None, expected=200):
    headers = {"Content-Type": "application/json"}
    if user:
        headers["Authorization"] = "Bearer " + tokens[user]
    if method != "GET" and not path.startswith("/auth/"):
        headers["Idempotency-Key"] = str(uuid.uuid4())
    req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        value = json.load(response)
        assert response.status == expected, (path, response.status, value.get("code"))
        return value


for user in ("admin", "alice", "finance", "bob", "employee"):
    tokens[user] = request("POST", "/auth/login", body={"tenantId": "demo", "username": user, "password": "demo"})["token"]


def queue(user="finance", **filters):
    return request("GET", "/workspace/tasks?" + urllib.parse.urlencode({"processKey": prefix, **filters}), user)


def act(task, user, action, target=None):
    current = request("GET", "/tasks/" + task["taskId"], user)
    return request("POST", "/tasks/" + task["taskId"] + "/actions", user,
                   {"action": action, "expectedVersion": current["version"], "comment": "隔离看板验收", **({"targetUser": target} if target else {})})


graph = {"nodes": [{"id": "start", "name": "开始", "type": "START", "properties": {}},
                   {"id": "review", "name": "材料复核", "type": "USER_TASK", "properties": {"assigneeRule": "role:FINANCE"}},
                   {"id": "end", "name": "结束", "type": "END", "properties": {}}],
         "edges": [{"id": "begin", "source": "start", "target": "review", "condition": ""},
                   {"id": "finish", "source": "review", "target": "end", "condition": ""}]}
schema = {"schemaVersion": 1, "fields": [{"key": "amount", "label": "金额", "type": "NUMBER", "required": False},
                                       {"key": "reason", "label": "说明", "type": "TEXTAREA", "required": True}]}
definition = request("POST", "/process-definitions", "admin", {"key": prefix, "name": "待办看板验收", "graph": graph, "formSchema": schema})
request("POST", "/process-definitions/" + definition["id"] + "/publish?expectedRevision=0", "admin", {"changeNote": "核对任务分栏、跨页加载与真实动作"})
for index in range(36):
    payload = {"reason": "仅用于隔离验收的完整正文，不应进入待办摘要。"}
    if index != 32:
        payload["amount"] = "99999999999999999999.123456789" if index == 31 else str(index) + ".01"
    app = request("POST", "/applications", "alice", {"businessNo": prefix + "-" + str(index).zfill(2), "processKey": prefix,
        "definitionVersion": 1, "title": "材料申请 · " + str(index).zfill(2), "payload": payload}, 201)
    request("POST", "/applications/" + app["id"] + "/submit", "alice", {"expectedVersion": app["version"]})

first = queue()
second = queue(cursor=first["nextCursor"])
tasks = first["items"] + second["items"]
assert len(tasks) == len({item["taskId"] for item in tasks}) == 36
lookup = {item["businessNo"].rsplit("-", 1)[1]: item for item in tasks}
# 回交后是已指派任务，批准后从待办移除，不能以展示分栏代替实际引擎动作。
act(lookup["00"], "finance", "DELEGATE", "bob")
assert queue("bob", assignment="delegated")["total"] == 1
act(lookup["00"], "bob", "RESOLVE")
resolved = request("GET", "/tasks/" + lookup["00"]["taskId"], "finance")
assert resolved["delegationState"] == "RESOLVED" and resolved["assignee"] == "finance"
act(lookup["00"], "finance", "APPROVE")
act(lookup["01"], "finance", "CLAIM")
act(lookup["01"], "finance", "RELEASE")
act(lookup["34"], "finance", "CLAIM")
act(lookup["35"], "admin", "DELEGATE", "finance")
assert queue("employee")["total"] == 0
first = queue(); second = queue(cursor=first["nextCursor"])
assert (first["total"], len(first["items"]), len(second["items"])) == (35, 30, 5)
assert not second.get("nextCursor")
lookup = {item["businessNo"].rsplit("-", 1)[1]: item for item in first["items"] + second["items"]}
assert len(lookup) == 35
assert lookup["34"].get("assignee") == "finance" and lookup["34"]["delegationState"] != "PENDING"
assert lookup["35"].get("assignee") == "finance" and lookup["35"].get("owner") == "admin" and lookup["35"]["delegationState"] == "PENDING"
assert all(not item.get("assignee") and item["delegationState"] != "PENDING" for item in first["items"])
# 既有“指派给我”筛选包含委派待回交；看板再把待回交独立列出。
assert queue(assignment="assigned")["total"] == 2
assert queue(assignment="delegated")["total"] == 1
assert queue(assignment="unclaimed")["total"] == 33
assert "payload" not in json.dumps(first) and "完整正文" not in json.dumps(first, ensure_ascii=False)


def facts():
    return {key: [request("GET", "/applications/" + lookup[key]["applicationId"] + path, "alice")
                  for path in ("", "/rounds", "/audit?limit=100", "/timeline?limit=100")] for key in ("01", "34", "35")}


before = facts()
for assignment in ("all", "assigned", "unclaimed", "delegated"):
    queue(assignment=assignment)
assert before == facts()
print(json.dumps({"result": "PASS", "processKey": prefix, "pages": [30, 5], "total": 35,
                  "lanes": {"unclaimed": 33, "assigned": 1, "delegated": 1}, "reads": "EXACT_MATCH",
                  "unclaimed": lookup["01"], "assigned": lookup["34"], "delegated": lookup["35"]}, ensure_ascii=False, indent=2))
