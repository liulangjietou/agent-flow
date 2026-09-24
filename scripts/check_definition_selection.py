"""在独立演示环境验证按需选择流程，创建合成发布版本并保留供浏览器验收。"""
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

if len(sys.argv) != 2:
    raise SystemExit("Usage: python3 scripts/check_definition_selection.py http://127.0.0.1:8082")
origin = urllib.parse.urlparse(sys.argv[1])
if origin.scheme != "http" or origin.hostname not in ("127.0.0.1", "localhost", "::1") or origin.path not in ("", "/") or origin.port in (8080, 8180):
    raise SystemExit("Use an isolated loopback demo environment, not the main demo ports")
BASE = sys.argv[1].rstrip("/") + "/api/v1"
KEY = "selection-" + uuid.uuid4().hex[:10]
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
        if path.startswith("/process-definitions/search") and expected == 200:
            assert response.headers.get("Cache-Control") == "no-store"
        return value


for user in ("admin", "alice"):
    tokens[user] = request("POST", "/auth/login", body={"tenantId": "demo", "username": user, "password": "demo"})["token"]
graph = {"nodes": [
    {"id": "start", "name": "开始", "type": "START", "properties": {}},
    {"id": "review", "name": "部门审批", "type": "USER_TASK", "properties": {"assigneeRule": "role:MANAGER"}},
    {"id": "end", "name": "结束", "type": "END", "properties": {}}
], "edges": [{"id": "a", "source": "start", "target": "review", "condition": ""}, {"id": "b", "source": "review", "target": "end", "condition": ""}]}
definitions = []
for index in range(1, 36):
    field = ({"key": "reason", "label": "早期版本事由", "type": "TEXT", "required": True, "maxLength": 100}
             if index == 1 else {"key": "days", "label": "本版申请天数", "type": "NUMBER", "required": True, "minimum": "1", "maximum": "30"})
    draft = request("POST", "/process-definitions", "admin", {"key": KEY, "name": f"按需流程验收 {index:02d}", "graph": graph, "formSchema": {"schemaVersion": 1, "fields": [field]}})
    published = request("POST", f"/process-definitions/{draft['id']}/publish?expectedRevision={draft['revision']}", "admin", {"changeNote": "隔离版本选择与表单绑定验收"})
    definitions.append(published)
filters = {"processKey": KEY, "status": "PUBLISHED", "limit": 30}
first = request("GET", "/process-definitions/search?" + urllib.parse.urlencode(filters), "alice")
second = request("GET", "/process-definitions/search?" + urllib.parse.urlencode({**filters, "cursor": first['nextCursor']}), "alice")
assert len(first['items']) == 30 and len(second['items']) == 5
assert {x['id'] for x in first['items'] + second['items']} == {x['id'] for x in definitions}
apps = []
for definition in (definitions[0], definitions[-1]):
    exact = request("GET", "/process-definitions/" + definition['id'], "alice")
    assert exact['formSchema'] == definition['formSchema']
    payload = {"reason": "早期版本绑定"} if definition['version'] == 1 else {"days": "3"}
    app = request("POST", "/applications", "alice", {"businessNo": KEY + '-v' + str(definition['version']), "title": "按需选择绑定验收", "processKey": KEY, "definitionVersion": definition['version'], "payload": payload}, expected=201)
    assert app['definitionVersion'] == definition['version'] and app['formSchema'] == definition['formSchema']
    assert request("GET", "/applications/" + app['id'], "alice")['payload'] == payload
    apps.append(app['id'])
print(json.dumps({"result": "PASS", "processKey": KEY, "pages": [30, 5], "firstVersionId": definitions[0]['id'], "latestVersionId": definitions[-1]['id'], "draftApplications": apps, "versions": [1,35]}, ensure_ascii=False))
