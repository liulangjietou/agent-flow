"""验证发布基线、当前设计差异和权限，只读已有数据，不创建或删除流程。"""
import copy
import json
import sys
import urllib.error
import urllib.request


base = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080").rstrip("/") + "/api/v1"


def request(path, token=None, body=None, expected=200):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    try:
        response = urllib.request.urlopen(urllib.request.Request(base + path,
            data=None if body is None else json.dumps(body, ensure_ascii=False).encode(), headers=headers), timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        result = json.load(response)
        assert response.status == expected, (path, response.status, result.get("code") if isinstance(result, dict) else None)
        return result


def login(user):
    return request("/auth/login", body={"tenantId": "demo", "username": user, "password": "demo"})["token"]


def snapshot(definition):
    return {key: copy.deepcopy(definition.get(key)) for key in ("key", "name", "graph", "formSchema")}


admin, employee = [login(user) for user in ("admin", "employee")]
resources = ("/process-definitions", "/applications", "/tasks", "/process-templates")
before = [request(resource, admin) for resource in resources]
published = [definition for definition in before[0] if definition["status"] == "PUBLISHED"]
assert published, "Publish at least one demo definition before running this read-only check"
baseline = published[0]
path = "/process-definitions/" + baseline["id"] + "/compare"
body = snapshot(baseline)
assert request(path, body=body, expected=401)["code"] == "UNAUTHENTICATED"
assert request(path, employee, body, expected=403)["code"] == "FORBIDDEN"
for definition in published:
    same = request("/process-definitions/" + definition["id"] + "/compare", admin, snapshot(definition))
    assert not same["changes"] and same["baseline"]["version"] == definition["version"]
assert request(path, admin, {**body, "key": body["key"] + "-different"}, expected=422)["code"] == "COMPARISON_KEY_MISMATCH"

# 当前请求中的新名称和审批规则不会保存到任何定义。
body["name"] += "（未保存比较）"
approver = next(node for node in body["graph"]["nodes"] if node["type"] == "USER_TASK")
approver["properties"]["assigneeRule"] = "role:FINANCE" if approver["properties"].get("assigneeRule") != "role:FINANCE" else "role:MANAGER"
changed = request(path, admin, body)
assert any(change["area"] == "DEFINITION" and change["property"] == "name" for change in changed["changes"])
assert any(change["property"] == "properties.assigneeRule" and change["targetId"] == approver["id"] for change in changed["changes"])

ambiguous = snapshot(baseline)
ambiguous["graph"]["edges"].append(copy.deepcopy(ambiguous["graph"]["edges"][0]))
assert request(path, admin, ambiguous, expected=422)["code"] == "COMPARISON_ID_AMBIGUOUS"
after = [request(resource, admin) for resource in resources]
assert before == after, "Comparison must not change visible business state"
print(json.dumps({"result": "PASS", "origin": base, "identicalPublishedVersions": len(published),
                  "baseline": changed["baseline"], "changedProperties": [change["property"] for change in changed["changes"]],
                  "beforeAfterCounts": dict(zip(resources, map(len, after))),
                  "verified": ["anonymous-401", "employee-403", "same-process-key", "unchanged-versions",
                               "unsaved-name-and-assignee", "duplicate-edge-rejection", "no-idempotency-key-needed",
                               "visible-business-state-unchanged"]}, ensure_ascii=False, indent=2))
