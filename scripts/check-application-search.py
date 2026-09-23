"""在独立演示环境验证管理员申请检索，创建合成数据且保留供浏览器验收。"""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

if len(sys.argv) != 2:
    raise SystemExit("Usage: python3 scripts/check-application-search.py http://127.0.0.1:8082")
origin = urllib.parse.urlparse(sys.argv[1])
if origin.scheme != "http" or origin.hostname not in ("127.0.0.1", "localhost", "::1") or origin.path not in ("", "/"):
    raise SystemExit("Use an isolated loopback demo environment")
BASE = sys.argv[1].rstrip("/") + "/api/v1"
PREFIX = "search-" + uuid.uuid4().hex[:10]
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
        assert response.status == expected, (path, response.status, value.get("code"))
        return value


for user in ("admin", "alice", "bob"):
    tokens[user] = request("POST", "/auth/login", body={"tenantId": "demo", "username": user, "password": "demo"})["token"]
graph = {"nodes": [
    {"id": "start", "name": "开始", "type": "START", "properties": {}},
    {"id": "review", "name": "部门审批", "type": "USER_TASK", "properties": {"assigneeRule": "role:MANAGER"}},
    {"id": "end", "name": "结束", "type": "END", "properties": {}}
], "edges": [{"id": "a", "source": "start", "target": "review", "condition": ""}, {"id": "b", "source": "review", "target": "end", "condition": ""}]}
definition = request("POST", "/process-definitions", "admin", {"key": PREFIX, "name": "管理员检索验收", "graph": graph, "formSchema": {"schemaVersion": 1, "fields": []}})
request("POST", "/process-definitions/" + definition["id"] + "/publish?expectedRevision=0", "admin", {"changeNote": "独立环境检索验收"})


def create(index):
    return request("POST", "/applications", "alice" if index % 2 == 0 else "bob", {"businessNo": PREFIX + "-" + str(index),
        "processKey": PREFIX, "definitionVersion": 1, "title": "检索验收 · " + str(index) + (" %_!" if index == 0 else ""), "payload": {}}, 201)


with ThreadPoolExecutor(max_workers=4) as pool:
    applications = list(pool.map(create, range(35)))
cancelled = request("POST", "/applications/" + applications[0]["id"] + "/cancel", "alice", {"expectedVersion": 1, "comment": "验证已作废筛选"})
before = {row["id"]: request("GET", "/applications/" + row["id"], "admin") for row in applications}


def search(**filters):
    return request("GET", "/operations/applications?" + urllib.parse.urlencode(filters), "admin")


first = search(processKey=PREFIX, limit=30)
assert len(first["items"]) == 30 and first.get("nextCursor")
second = search(processKey=PREFIX, limit=30, cursor=first["nextCursor"])
assert len(second["items"]) == 5 and not second.get("nextCursor")
ids = [row["id"] for row in first["items"] + second["items"]]
assert len(set(ids)) == 35 and set(ids) == set(before)
assert all("payload" not in row and "formSchema" not in row for row in first["items"])
assert len(search(processKey=PREFIX, applicant="alice")["items"]) == 18
assert len(search(processKey=PREFIX, applicant="bob")["items"]) == 17
assert [row["id"] for row in search(processKey=PREFIX, status="CANCELLED", definitionVersion=1)["items"]] == [cancelled["id"]]
assert [row["id"] for row in search(processKey=PREFIX, q="%_!")["items"]] == [cancelled["id"]]
assert not search(processKey=PREFIX, applicant="alice' OR '1'='1")["items"]
today = datetime.now(timezone.utc).date().isoformat()
assert len(search(processKey=PREFIX, **{"from": today, "to": today}, limit=100)["items"]) == 35
request("GET", "/operations/applications", "alice", expected=403)
request("GET", "/operations/applications?tenantId=other", "admin", expected=400)
request("GET", "/operations/applications?" + urllib.parse.urlencode({"processKey": PREFIX, "status": "DRAFT", "cursor": first["nextCursor"]}), "admin", expected=400)
after = {row["id"]: request("GET", "/applications/" + row["id"], "admin") for row in applications}
assert before == after
print(json.dumps({"result": "PASS", "base": BASE, "processKey": PREFIX, "applications": len(ids), "pages": [30, 5], "cancelledId": cancelled["id"],
    "businessNo": cancelled["businessNo"], "readOnlyFacts": "EXACT_MATCH", "date": today}, ensure_ascii=False))
