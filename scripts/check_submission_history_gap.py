"""在独立演示环境验证未提交草稿与作废历史缺口，保留合成审批数据。"""
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

if len(sys.argv) != 2:
    raise SystemExit("Usage: python3 scripts/check_submission_history_gap.py http://127.0.0.1:8082")
origin = urllib.parse.urlparse(sys.argv[1])
if origin.scheme != "http" or origin.hostname not in ("127.0.0.1", "localhost", "::1") or origin.path not in ("", "/") or origin.port in (8080, 8180):
    raise SystemExit("Use an isolated loopback demo environment, not the main demo ports")
BASE = sys.argv[1].rstrip("/") + "/api/v1"
KEY = "history-gap-" + uuid.uuid4().hex[:10]
tokens = {}


def request(method, path, user=None, body=None, expected=200):
    headers = {"Content-Type": "application/json"}
    if user:
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
        assert response.status == expected, (path, response.status, value.get("code") if isinstance(value, dict) else None)
        if path.startswith(("/system/first-workflow?", "/operations/approvals?")) and expected == 200:
            assert response.headers.get("Cache-Control") == "no-store"
        return value


for user in ("admin", "alice", "manager"):
    tokens[user] = request("POST", "/auth/login", body={"tenantId": "demo", "username": user, "password": "demo"})["token"]
graph = {"nodes": [
    {"id": "start", "name": "开始", "type": "START", "properties": {}},
    {"id": "review", "name": "经理审批", "type": "USER_TASK", "properties": {"assigneeRule": "user:manager"}},
    {"id": "end", "name": "结束", "type": "END", "properties": {}}
], "edges": [{"id": "a", "source": "start", "target": "review", "condition": ""}, {"id": "b", "source": "review", "target": "end", "condition": ""}]}
draft = request("POST", "/process-definitions", "admin", {"key": KEY, "name": "提交历史缺口验收", "graph": graph})
definition = request("POST", f"/process-definitions/{draft['id']}/publish?expectedRevision={draft['revision']}", "admin", {"changeNote": "隔离草稿与真实历史验收"})


def check():
    guide = request("GET", "/system/first-workflow?" + urllib.parse.urlencode({"definitionId": definition['id']}), "admin")
    operations = request("GET", "/operations/approvals?" + urllib.parse.urlencode({"processKey": KEY, "definitionVersion": 1}), "admin")
    assert guide['unrecordedHistoricalRounds'] == operations['unrecordedHistoricalRounds'] == 0
    return guide, operations


def change(app, action):
    return request("POST", f"/applications/{app['id']}/{action}", "alice", {"expectedVersion": app['version'], "comment": "缺口真实接口验收"})


apps = []
for action in ("DRAFT", "CANCEL", "RETURN", "WITHDRAW", "APPROVE"):
    app = request("POST", "/applications", "alice", {"businessNo": KEY + '-' + action, "title": "历史缺口 " + action, "processKey": KEY, "definitionVersion": 1, "payload": {}}, expected=201)
    check()
    if action == "CANCEL":
        app = change(app, "cancel")
    elif action != "DRAFT":
        app = change(app, "submit")
        if action == "WITHDRAW":
            app = change(app, "withdraw")
        else:
            task = next(item for item in request("GET", "/tasks", "manager") if item['applicationId'] == app['id'])
            request("POST", f"/tasks/{task['taskId']}/actions", "manager", {"action": action, "expectedVersion": task['version'], "comment": "验证真实轮次"})
            app = request("GET", f"/applications/{app['id']}", "alice")
        check()
        if action != "APPROVE":
            app = change(app, "cancel")
    check()
    saved = request("GET", f"/applications/{app['id']}", "alice")
    assert saved == app
    apps.append({"id": app['id'], "scenario": action, "status": app['status']})
guide, operations = check()
assert guide['submittedRounds'] == 3 and guide['approvedRounds'] == 1
assert operations['metrics']['decidedRounds'] == 2 and float(operations['metrics']['returnRatePercent']) == 50
assert operations['metrics']['withdrawn'] == 1
print(json.dumps({"result": "PASS", "processKey": KEY, "definitionId": definition['id'], "applications": apps,
                  "unrecordedHistoricalRounds": 0, "submittedRounds": 3, "decidedRounds": 2, "returnRatePercent": 50}, ensure_ascii=False))
