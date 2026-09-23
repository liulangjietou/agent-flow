"""验证当前设计模拟、权限、分支说明和业务只读性；不保存草稿或创建申请。"""
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
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    try:
        response = urllib.request.urlopen(urllib.request.Request(base + path, data=data, headers=headers), timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        result = json.load(response)
        assert response.status == expected, (path, response.status, result.get("code") if isinstance(result, dict) else None)
        return result


def login(user):
    return request("/auth/login", body={"tenantId": "demo", "username": user, "password": "demo"})["token"]


admin, employee = [login(user) for user in ("admin", "employee")]
resources = ("/process-definitions", "/applications", "/tasks", "/process-templates")
before = [request(path, admin) for path in resources]
catalog = before[3]
path = "/process-definitions/simulate"
seed = catalog[0]
body = {"graph": seed["graph"], "formSchema": seed["formSchema"], "values": {}}
assert request(path, body=body, expected=401)["code"] == "UNAUTHENTICATED"
assert request(path, employee, body, expected=403)["code"] == "FORBIDDEN"
results = []
for template in catalog:
    for scenario in template["scenarios"]:
        errors = scenario["expectedFieldErrors"]
        payload = {"graph": template["graph"], "formSchema": template["formSchema"], "values": scenario["payload"]}
        result = request(path, admin, payload, expected=422 if errors else 200)
        if errors:
            assert result["code"] == "FORM_VALIDATION_FAILED" and result["details"]["fieldErrors"] == errors
        else:
            assert result["path"] == scenario["expectedPath"]
            edges = {edge["id"]: edge for edge in template["graph"]["edges"]}
            assert len(result["edgeIds"]) == len(result["path"]) - 1
            for index, edge_id in enumerate(result["edgeIds"]):
                assert edges[edge_id]["source"] == result["path"][index]
                assert edges[edge_id]["target"] == result["path"][index + 1]
            for decision in result["decisions"]:
                selected = [branch for branch in decision["branches"] if branch["outcome"] in ("MATCHED", "DEFAULT_SELECTED")]
                assert len(selected) == 1 and selected[0]["edgeId"] == decision["selectedEdgeId"]
        results.append({"template": template["key"], "scenario": scenario["id"], "valid": not bool(errors)})

# 直接修改请求快照，证明未保存的新条件会参与路由，且边界值使用精确十进制。
leave = next(template for template in catalog if template["key"] == "leave-request")
changed = copy.deepcopy(leave["graph"])
for edge in changed["edges"]:
    if edge["condition"]:
        edge["condition"] = "durationDays > 2"
values = {"leaveType": "ANNUAL", "startDate": "2026-09-24", "durationDays": "2", "reason": "模拟验收"}
for days, review in (("2", False), ("2.000000000000000001", True)):
    values["durationDays"] = days
    result = request(path, admin, {"graph": changed, "formSchema": leave["formSchema"], "values": values})
    assert ("review" in result["path"]) == review

too_precise = {**values, "durationDays": "2.0000000000000000001"}
result = request(path, admin, {"graph": changed, "formSchema": leave["formSchema"], "values": too_precise}, expected=422)
assert result["details"]["fieldErrors"]["durationDays"] == "INVALID_NUMBER"

invalid = copy.deepcopy(changed)
manager = next(node for node in invalid["nodes"] if node["id"] == "manager")
manager["properties"]["assigneeRule"] = ""
result = request(path, admin, {"graph": invalid, "formSchema": leave["formSchema"], "values": values}, expected=422)
assert result["code"] == "INVALID_DEFINITION"
assert "ASSIGNEE_RULE_REQUIRED:manager" in result["details"]["definitionErrors"]
after = [request(resource, admin) for resource in resources]
assert before == after, "Simulation must not modify visible business state"
print(json.dumps({"result": "PASS", "origin": base, "scenarios": results,
                  "beforeAfterCounts": dict(zip(resources, map(len, after))),
                  "verified": ["anonymous-401", "employee-403", "all-template-scenarios", "edge-continuity",
                               "decision-evidence", "unsaved-condition", "decimal-boundary", "error-location",
                               "no-idempotency-key-needed", "visible-business-state-unchanged"]}, ensure_ascii=False, indent=2))
