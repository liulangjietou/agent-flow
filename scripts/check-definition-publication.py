"""独立验收环境的发布记录检查；新增随机流程，保留所有验收数据。"""

from concurrent.futures import ThreadPoolExecutor
import json
import sys
import urllib.error
import urllib.request
import uuid

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080").rstrip("/") + "/api/v1"


def request(method, path, token=None, body=None, key=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if key:
        headers["Idempotency-Key"] = key
    req = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, json.loads(response.read()), response.headers.get("Idempotency-Replayed")


def login(user):
    status, result, _ = request("POST", "/auth/login", body={"tenantId": "demo", "username": user, "password": "demo"})
    assert status == 200, status
    return result["token"]


admin = login("admin")
employee = login("employee")
status, templates, _ = request("GET", "/process-templates", admin)
assert status == 200
template = next(item for item in templates if item["key"] == "leave-request")
status, draft, _ = request("POST", "/process-definitions", admin, {
    "key": "publication-" + uuid.uuid4().hex[:12], "name": "发布记录验收", "graph": template["graph"], "formSchema": template["formSchema"]
}, str(uuid.uuid4()))
assert status == 200, (status, draft)
path = "/process-definitions/" + draft["id"]
publish_path = path + "/publish?expectedRevision=0"
status, invalid, _ = request("POST", publish_path, admin, {"changeNote": " "}, str(uuid.uuid4()))
assert status == 422 and invalid["code"] == "INVALID_PUBLICATION_NOTE", (status, invalid)
assert request("GET", path, admin)[1]["status"] == "DRAFT"
key = str(uuid.uuid4())
body = {"changeNote": "首次发布：启用请假表单与条件审批。\n并发请求仅发布一个版本。", "publishedBy": "forged"}
with ThreadPoolExecutor(max_workers=6) as pool:
    results = list(pool.map(lambda _: request("POST", publish_path, admin, body, key), range(6)))
assert all(result[0] == 200 and result[1] == results[0][1] for result in results), results
assert sum(result[2] == "false" for result in results) == 1, results
status, record, _ = request("GET", path + "/publication", admin)
assert status == 200 and record["recorded"] is True, (status, record)
publication = record["publication"]
assert publication["publishedBy"] == "admin" and publication["authorizedRole"] == "ADMIN", publication
assert publication["changeNote"] == body["changeNote"], publication
assert publication["definitionVersion"] == 1 and publication["validation"]["formBound"], publication
assert publication["validation"]["fieldCount"] == len(template["formSchema"]["fields"]), publication
assert request("GET", path + "/publication", employee)[0] == 403
assert request("GET", path + "/publication")[0] == 401
status, reused, _ = request("POST", publish_path, admin, {"changeNote": "改写说明"}, key)
assert status == 409 and reused["code"] == "IDEMPOTENCY_KEY_REUSED", (status, reused)
assert request("GET", path + "/publication", admin)[1] == record
print(json.dumps({"definitionId": draft["id"], "processKey": draft["key"], "concurrentRequests": 6, "publication": publication}, ensure_ascii=False, indent=2))
