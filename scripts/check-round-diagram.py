"""在独立本机演示环境保留合成申请，验证流程图轮次、分支、会签及只读边界。"""
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

if len(sys.argv) != 3 or sys.argv[2] != "--exercise":
    raise SystemExit("Usage: python3 scripts/check-round-diagram.py http://127.0.0.1:8082 --exercise")
origin = urllib.parse.urlparse(sys.argv[1])
if origin.scheme != "http" or origin.hostname not in ("127.0.0.1", "localhost", "::1") or origin.path not in ("", "/") or origin.port in (None, 8080, 8180) or origin.query or origin.fragment or origin.username or origin.password:
    raise SystemExit("Use an isolated loopback demo environment")
BASE = sys.argv[1].rstrip("/") + "/api/v1"
PREFIX = "diagram-" + uuid.uuid4().hex[:10]
tokens = {}


def request(method, path, user="admin", body=None, expected=200):
    headers = {"Content-Type": "application/json"}
    if user in tokens:
        headers["Authorization"] = "Bearer " + tokens[user]
    if method != "GET" and not path.startswith("/auth/"):
        headers["Idempotency-Key"] = str(uuid.uuid4())
    req = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body, ensure_ascii=False).encode(), headers=headers, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        value = json.load(response)
        assert response.status == expected, (path, response.status, value.get("code"))
        if path.endswith("/diagram") and expected == 200:
            assert response.headers.get("Cache-Control") == "no-store"
        return value


for user in ("admin", "alice", "bob", "finance", "manager"):
    tokens[user] = request("POST", "/auth/login", body={"tenantId": "demo", "username": user, "password": "demo"})["token"]


def graph(all_members=False, name="部门审批 v1"):
    return {"nodes": [
        {"id": "start", "name": "开始", "type": "START", "properties": {}},
        {"id": "review", "name": name, "type": "USER_TASK", "properties": {"assigneeRule": "role:FINANCE" if all_members else "role:MANAGER", "approvalMode": "ALL" if all_members else "SINGLE"}},
        {"id": "gate", "name": "金额分支", "type": "EXCLUSIVE_GATEWAY", "properties": {}},
        {"id": "finance", "name": "财务复核", "type": "USER_TASK", "properties": {"assigneeRule": "role:FINANCE"}},
        {"id": "end", "name": "结束", "type": "END", "properties": {}}
    ], "edges": [
        {"id": "a", "source": "start", "target": "review", "condition": ""},
        {"id": "b", "source": "review", "target": "gate", "condition": ""},
        {"id": "c", "source": "gate", "target": "finance", "condition": "amount >= 5000"},
        {"id": "d", "source": "gate", "target": "end", "condition": "", "defaultBranch": True},
        {"id": "e", "source": "finance", "target": "end", "condition": ""}]}


def publish(key, content):
    draft = request("POST", "/process-definitions", body={"key": key, "name": "轮次流程图验收", "graph": content})
    return request("POST", "/process-definitions/" + draft["id"] + "/publish?expectedRevision=0", body={"changeNote": "独立环境流程图验收"})


def create(key, suffix, amount=6000):
    return request("POST", "/applications", "alice", {"businessNo": PREFIX + "-" + suffix, "processKey": key,
        "definitionVersion": 1, "title": "流程图验收 · " + suffix, "payload": {"amount": amount, "description": "合成验收数据"}}, 201)


def update(app, operation):
    return request("POST", "/applications/" + app["id"] + "/" + operation, "alice", {"expectedVersion": app["version"]})


def act(app, user, action):
    task = next(task for task in request("GET", "/tasks", user) if task["applicationId"] == app["id"])
    request("POST", "/tasks/" + task["taskId"] + "/actions", user, {"expectedVersion": app["version"], "action": action, "comment": "流程图验收意见"})
    return request("GET", "/applications/" + app["id"], "alice")


def diagram(app, round_no):
    return request("GET", "/applications/" + app["id"] + "/rounds/" + str(round_no) + "/diagram", "alice")


def node(view, node_id):
    return next(item for item in view["nodes"] if item["id"] == node_id)


def taken(view, *ids):
    expected = set(ids)
    for edge in view["edges"]:
        recorded = edge["id"] in expected
        assert edge["state"] == ("TAKEN" if recorded else "NOT_RECORDED"), edge
        assert edge["traversalCount"] == (1 if recorded else 0), edge
        if recorded:
            assert edge["firstTakenAt"] == edge["lastTakenAt"]
        else:
            assert "firstTakenAt" not in edge and "lastTakenAt" not in edge


publish(PREFIX, graph())
app = update(create(PREFIX, "重提当前节点"), "submit")
assert node(diagram(app, 1), "review")["state"] == "ACTIVE"
taken(diagram(app, 1), "a")
app = act(app, "manager", "RETURN")
assert node(diagram(app, 1), "end")["state"] == "NOT_REACHED"
publish(PREFIX, graph(name="部门审批 v2 不可串入旧轮次"))
app = update(app, "submit")
app = act(app, "manager", "APPROVE")
assert node(diagram(app, 2), "review")["name"] == "部门审批 v1"
assert node(diagram(app, 2), "finance")["state"] == "ACTIVE"
assert node(diagram(app, 1), "finance")["state"] == "NOT_REACHED"
taken(diagram(app, 1), "a")
taken(diagram(app, 2), "a", "b", "c")
low = update(create(PREFIX, "默认分支已批准", 100), "submit")
low = act(low, "manager", "APPROVE")
assert diagram(low, 1)["status"] == "APPROVED"
assert node(diagram(low, 1), "finance")["state"] == "NOT_REACHED"
assert node(diagram(low, 1), "end")["state"] == "LEFT"
taken(diagram(low, 1), "a", "b", "d")
high = update(create(PREFIX, "条件分支已批准"), "submit")
high = act(act(high, "manager", "APPROVE"), "finance", "APPROVE")
taken(diagram(high, 1), "a", "b", "c", "e")
publish(PREFIX + "-all", graph(True, "财务会签"))
all_app = update(create(PREFIX + "-all", "会签剩余任务"), "submit")
assert node(diagram(all_app, 1), "review")["activeTasks"] == 2
all_app = act(all_app, "finance", "APPROVE")
assert node(diagram(all_app, 1), "review")["activeTasks"] == 1
taken(diagram(all_app, 1), "a")
rejected = update(create(PREFIX + "-all", "会签已驳回"), "submit")
rejected = act(rejected, "admin", "REJECT")
assert diagram(rejected, 1)["status"] == "REJECTED"
assert node(diagram(rejected, 1), "review")["state"] == "LEFT"
assert node(diagram(rejected, 1), "end")["state"] == "NOT_REACHED"
taken(diagram(rejected, 1), "a")
withdrawn = update(create(PREFIX, "已撤回"), "submit")
withdrawn = update(withdrawn, "withdraw")
assert diagram(withdrawn, 1)["status"] == "WITHDRAWN"
taken(diagram(withdrawn, 1), "a")
draft = create(PREFIX, "未提交")
request("GET", "/applications/" + draft["id"] + "/rounds/1/diagram", "alice", expected=404)
request("GET", "/applications/" + app["id"] + "/rounds/1/diagram", "bob", expected=404)
request("GET", "/applications/" + app["id"] + "/rounds/0/diagram", "alice", expected=400)


def facts():
    return {item["id"]: {path: request("GET", "/applications/" + item["id"] + path, "alice") for path in ("", "/rounds", "/audit?limit=100", "/timeline?limit=100")}
        for item in (app, low, high, all_app, rejected, withdrawn, draft)}


before = facts()
for item in (app, low, high, all_app, rejected, withdrawn):
    diagram(item, 1)
assert before == facts()
print(json.dumps({"result": "PASS", "base": BASE, "processKey": PREFIX, "readOnlyFacts": "EXACT_MATCH", "applications": {
    "resubmitted": app["id"], "defaultApproved": low["id"], "conditionalApproved": high["id"], "countersign": all_app["id"], "rejected": rejected["id"], "withdrawn": withdrawn["id"], "draft": draft["id"]}}, ensure_ascii=False))
