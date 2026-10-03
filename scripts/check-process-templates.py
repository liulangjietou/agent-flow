"""验证演示环境中通用表单模板复制、租户草稿隔离及样例真实流程，不删除已有记录。"""
import copy
import json
import sys
import urllib.error
import urllib.request
import uuid


base = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080").rstrip("/") + "/api/v1"
prefix = uuid.uuid4().hex[:10]


def request(method, path, body=None, token=None, expected=200, key=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if method in ("POST", "PUT") and path != "/auth/login":
        headers["Idempotency-Key"] = key or "template-check-" + str(uuid.uuid4())
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    try:
        response = urllib.request.urlopen(urllib.request.Request(base + path, data=data, headers=headers, method=method), timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        result = json.load(response)
        assert response.status == expected, (method, path, response.status, result.get("code") if isinstance(result, dict) else None)
        return result


def login(user):
    return request("POST", "/auth/login", {"tenantId": "demo", "username": user, "password": "demo"})["token"]


admin, employee, manager = [login(user) for user in ("admin", "employee", "manager")]
catalog = request("GET", "/process-templates", token=admin)
assert {"leave-request", "seal-application", "contract-review"}.issubset({item["key"] for item in catalog})
request("GET", "/process-templates", token=employee, expected=403)
results = []

# 通用表单验收不能绕过采购的任职、财务预检与原应付占用；采购由独立工作流用例验收。
for template in (item for item in catalog if item["businessType"] == "FORM"):
    template_key = template["key"]
    copy_path = "/process-templates/" + template_key + "/copy"
    body = {"key": "verify-" + prefix + "-" + template_key, "name": template["name"] + " HTTP验收", "templateVersion": template["templateVersion"]}
    copy_key = "template-copy-" + prefix + "-" + template_key
    draft = request("POST", copy_path, body, admin, key=copy_key)
    replay = request("POST", copy_path, body, admin, key=copy_key)
    assert replay == draft and draft["status"] == "DRAFT" and draft["version"] == 0 and draft["revision"] == 0
    assert draft["graph"] == template["graph"] and draft["formSchema"] == template["formSchema"]
    conflict = request("POST", copy_path, {**body, "name": "不同正文不得复用请求键"}, admin, expected=409, key=copy_key)
    assert conflict["code"] == "IDEMPOTENCY_KEY_REUSED"
    request("POST", copy_path, {**body, "key": body["key"] + "-denied"}, employee, expected=403)
    request("GET", "/process-definitions/" + draft["id"], token=employee, expected=404)

    scenarios = request("GET", "/process-templates/" + template_key + "/scenarios", token=admin)
    assert scenarios == template["scenarios"]
    path = "/process-definitions/" + draft["id"]
    for scenario in scenarios:
        expected_errors = scenario["expectedFieldErrors"]
        simulated = request("POST", path + "/simulate", {"values": scenario["payload"]}, admin, expected=422 if expected_errors else 200)
        if expected_errors:
            assert simulated["code"] == "FORM_VALIDATION_FAILED"
            assert simulated["details"]["fieldErrors"] == expected_errors
        else:
            assert simulated["path"] == scenario["expectedPath"]

    published = request("POST", path + "/publish?expectedRevision=0", body={"changeNote": "验收脚本发布"}, token=admin)
    application_results = []
    nodes = {node["id"]: node for node in published["graph"]["nodes"]}
    for scenario in scenarios:
        if scenario["expectedFieldErrors"]:
            continue
        app = request("POST", "/applications", {
            "businessNo": "TPL-" + uuid.uuid4().hex[:16].upper(), "processKey": published["key"], "definitionVersion": published["version"],
            "title": template["name"] + "：" + scenario["name"], "payload": scenario["payload"]
        }, employee, expected=201)
        app_path = "/applications/" + app["id"]
        app = request("POST", app_path + "/submit", {"expectedVersion": app["version"]}, employee)
        expected_tasks = [nodes[node_id] for node_id in scenario["expectedPath"] if nodes[node_id]["type"] == "USER_TASK"]
        for node in expected_tasks:
            role = node["properties"]["assigneeRule"]
            assert role in ("role:MANAGER", "role:ADMIN"), "Verification must use an explicitly supported demo role"
            token = manager if role == "role:MANAGER" else admin
            tasks = [task for task in request("GET", "/tasks", token=token) if task["applicationId"] == app["id"]]
            assert len(tasks) == 1 and tasks[0]["taskName"] == node["name"]
            task = tasks[0]
            request("POST", "/tasks/" + task["taskId"] + "/actions", {
                "action": "APPROVE", "expectedVersion": task["version"], "comment": "模板样例真实流程验收"
            }, token)
        app = request("GET", app_path, token=employee)
        rounds = request("GET", app_path + "/rounds", token=employee)
        assert app["status"] == "APPROVED" and len(rounds) == 1 and rounds[0]["status"] == "APPROVED"
        assert rounds[0]["payload"] == scenario["payload"] and rounds[0]["formSchema"] == template["formSchema"]
        application_results.append({"scenario": scenario["id"], "applicationId": app["id"], "businessNo": app["businessNo"], "status": app["status"]})

    # 使用同一个业务 key 复制另一份草稿；修改副本不改变目录、已发布定义或原申请。
    second = request("POST", copy_path, body, admin)
    changed_schema = copy.deepcopy(second["formSchema"])
    changed_schema["fields"][0]["label"] += "（副本调整）"
    request("PUT", "/process-definitions/" + second["id"], {
        "name": second["name"] + "第二份草稿", "graph": second["graph"], "formSchema": changed_schema, "expectedRevision": second["revision"]
    }, admin)
    assert request("GET", path, token=admin) == published
    updated = next(item for item in request("GET", "/process-templates", token=admin) if item["key"] == template_key)
    assert updated["graph"] == template["graph"] and updated["formSchema"] == template["formSchema"]
    copies = {item["definitionId"]: item for item in updated["copies"]}
    assert copies[published["id"]]["status"] == "PUBLISHED" and copies[second["id"]]["status"] == "DRAFT"
    assert copies[published["id"]]["templateVersion"] == template["templateVersion"]
    results.append({"templateKey": template_key, "definitionId": published["id"], "draftCopyId": second["id"], "processKey": published["key"], "applications": application_results})

print(json.dumps({"result": "PASS", "templates": results, "verified": ["permission", "idempotent-copy", "scenario-validation", "real-flowable-paths", "immutable-catalog", "independent-copies", "copy-provenance"]}, ensure_ascii=False, indent=2))
