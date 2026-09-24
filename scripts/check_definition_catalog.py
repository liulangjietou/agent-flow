"""在独立演示环境验证流程目录，创建合成草稿并保留供浏览器验收。"""
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

if len(sys.argv) != 2:
    raise SystemExit("Usage: python3 scripts/check_definition_catalog.py http://127.0.0.1:8082")
origin = urllib.parse.urlparse(sys.argv[1])
if origin.scheme != "http" or origin.hostname not in ("127.0.0.1", "localhost", "::1") or origin.path not in ("", "/") or origin.port in (8080, 8180):
    raise SystemExit("Use an isolated loopback demo environment, not the main demo ports")
BASE = sys.argv[1].rstrip("/") + "/api/v1"
KEY = "catalog-" + uuid.uuid4().hex[:10]
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
definitions = [request("POST", "/process-definitions", "admin", {"key": KEY, "name": f"流程目录验收 {index:02d}" + (" %_!" if index == 0 else ""), "graph": graph, "formSchema": {"schemaVersion": 1, "fields": []}}) for index in range(35)]
published = request("POST", f"/process-definitions/{definitions[0]['id']}/publish?expectedRevision={definitions[0]['revision']}", "admin", {"changeNote": "隔离目录发布版本检索验收"})


def search(user="admin", **filters):
    return request("GET", "/process-definitions/search?" + urllib.parse.urlencode(filters), user)


before = {item["id"]: request("GET", "/process-definitions/" + item["id"], "admin") for item in definitions}
first = search(processKey=KEY, limit=30)
assert len(first["items"]) == 30 and first.get("nextCursor")
second = search(processKey=KEY, limit=30, cursor=first["nextCursor"])
assert len(second["items"]) == 5 and not second.get("nextCursor")
ids = [item["id"] for item in first["items"] + second["items"]]
assert len(set(ids)) == 35 and set(ids) == set(before)
assert all(not {"graph", "formSchema", "tenantId"}.intersection(item) for item in first["items"])
assert len(search(processKey=KEY, status="DRAFT", limit=100)["items"]) == 34
assert [item["id"] for item in search(processKey=KEY, status="PUBLISHED", version=1)["items"]] == [published["id"]]
assert [item["id"] for item in search(processKey=KEY, q="%_!")["items"]] == [published["id"]]
assert [item["id"] for item in search("alice", processKey=KEY)["items"]] == [published["id"]]
request("GET", "/process-definitions/search?status=DRAFT", "alice", expected=403)
request("GET", "/process-definitions/search?tenantId=other", "admin", expected=400)
request("GET", "/process-definitions/search?" + urllib.parse.urlencode({"processKey": KEY, "status": "DRAFT", "cursor": first["nextCursor"]}), "admin", expected=400)
request("GET", "/process-definitions/search?" + urllib.parse.urlencode({"processKey": KEY, "cursor": first["nextCursor"]}), "alice", expected=400)
after = {item["id"]: request("GET", "/process-definitions/" + item["id"], "admin") for item in definitions}
assert before == after
print(json.dumps({"result": "PASS", "base": BASE, "processKey": KEY, "definitions": 35, "pages": [30, 5], "publishedId": published["id"], "draftId": definitions[-1]["id"], "readOnlyFacts": "EXACT_MATCH"}, ensure_ascii=False))
