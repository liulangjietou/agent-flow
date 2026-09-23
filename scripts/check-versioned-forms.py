"""验证本地演示服务的版本化表单、精确路由及补正历史，不删除既有数据。"""
import copy
import json
import sys
import urllib.error
import urllib.request
import uuid


base = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080").rstrip("/") + "/api/v1"
prefix = uuid.uuid4().hex[:12]


def request(method, path, body=None, token=None, expected=200, key=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if method in ("POST", "PUT") and path != "/auth/login":
        headers["Idempotency-Key"] = key or "forms-" + str(uuid.uuid4())
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        result = json.load(response)
        assert response.status == expected, (method, path, response.status, result.get("code"))
        return result


def login(user, tenant="demo"):
    return request("POST", "/auth/login", {"tenantId": tenant, "username": user, "password": "demo"})["token"]


def task_for(token, application_id):
    tasks = [item for item in request("GET", "/tasks", token=token) if item["applicationId"] == application_id]
    assert len(tasks) == 1, "Expected exactly one task for this application"
    return tasks[0]


def act(token, application_id, action):
    task = task_for(token, application_id)
    return request("POST", "/tasks/" + task["taskId"] + "/actions",
                   {"action": action, "expectedVersion": task["version"], "comment": "版本化表单验收"}, token)


admin, employee, manager, finance = [login(user) for user in ("admin", "employee", "manager", "finance")]
check_bundled_binding = "--check-bundled-binding" in sys.argv[2:]
# 来源验证会发布固定标识；在创建任何验收业务记录前检查，避免不满足前置条件时留下半套数据。
if check_bundled_binding:
    assert not any(item["key"] == "expense-reimbursement" for item in request("GET", "/process-definitions?status=PUBLISHED", token=admin)), "Bundled-binding verification needs a demo tenant without an existing expense-reimbursement definition"

schema = {"schemaVersion": 1, "fields": [
    {"key": "leaveType", "label": "请假类型", "type": "SELECT", "required": True,
     "options": [{"value": "01", "label": "年假"}, {"value": "1", "label": "事假"}]},
    {"key": "startDate", "label": "开始日期", "type": "DATE", "required": True},
    {"key": "days", "label": "请假天数", "type": "NUMBER", "required": True, "minimum": "0.5", "maximum": "30"},
    {"key": "reason", "label": "申请事由", "type": "TEXTAREA", "required": True, "maxLength": 1000},
    {"key": "urgent", "label": "是否紧急", "type": "BOOLEAN", "required": True},
    {"key": "referenceNumber", "label": "精确数字", "type": "NUMBER", "required": False},
    {"key": "contact", "label": "联系方式说明", "type": "TEXT", "required": False, "maxLength": 100}
]}
graph = {"nodes": [
    {"id": "start", "name": "开始", "type": "START", "properties": {}},
    {"id": "manager", "name": "部门审批", "type": "USER_TASK", "properties": {"assigneeRule": "role:MANAGER"}},
    {"id": "route", "name": "年假天数判断", "type": "EXCLUSIVE_GATEWAY", "properties": {}},
    {"id": "finance", "name": "长年假复核", "type": "USER_TASK", "properties": {"assigneeRule": "role:FINANCE"}},
    {"id": "end", "name": "结束", "type": "END", "properties": {}}
], "edges": [
    {"id": "e1", "source": "start", "target": "manager", "condition": "", "defaultBranch": False},
    {"id": "e2", "source": "manager", "target": "route", "condition": "", "defaultBranch": False},
    {"id": "e3", "source": "route", "target": "finance", "condition": "leaveType == '01' AND days > 3", "defaultBranch": False},
    {"id": "e4", "source": "route", "target": "end", "condition": "", "defaultBranch": True},
    {"id": "e5", "source": "finance", "target": "end", "condition": "", "defaultBranch": False}
]}
process_key = "forms-" + prefix
definition_body = {"key": process_key, "name": "版本化请假表单验收", "graph": graph, "formSchema": schema}
definition = request("POST", "/process-definitions", definition_body, admin)
definition = request("POST", f'/process-definitions/{definition["id"]}/publish?expectedRevision={definition["revision"]}', token=admin)
assert definition["version"] == 1 and definition["formSchema"]["fields"][0]["label"] == "请假类型"

create_body = {"businessNo": "FORM-" + prefix.upper(), "processKey": process_key,
               "definitionVersion": 1, "title": "版本化请假申请验收", "payload": {}}
create_key = "forms-create-" + prefix
application = request("POST", "/applications", create_body, employee, 201, create_key)
path = "/applications/" + application["id"]
rejected = request("POST", path + "/submit", {"expectedVersion": application["version"]}, employee, 422)
assert rejected["code"] == "FORM_VALIDATION_FAILED" and rejected["details"]["fieldErrors"]["days"] == "REQUIRED"
assert request("GET", path, token=employee)["status"] == "DRAFT"
assert request("GET", path + "/rounds", token=employee) == []

payload = {"leaveType": "01", "startDate": "2028-02-29", "days": "10.5", "reason": "先验收长年假分支",
           "urgent": False, "referenceNumber": "9007199254740993.12345678", "contact": None}
rejected = request("PUT", path, {"expectedVersion": application["version"], "title": create_body["title"],
                                 "payload": {**payload, "unknownField": "不能进入流程"}}, employee, 422)
assert rejected["details"]["fieldErrors"]["unknownField"] == "UNKNOWN_FIELD"
application = request("PUT", path, {"expectedVersion": application["version"], "title": create_body["title"], "payload": payload}, employee)
application = request("POST", path + "/submit", {"expectedVersion": application["version"]}, employee)
assert act(manager, application["id"], "APPROVE")["applicationStatus"] == "IN_APPROVAL"
assert act(finance, application["id"], "RETURN")["applicationStatus"] == "RETURNED"
first_round = request("GET", path + "/rounds", token=employee)[0]
assert first_round["payload"] == payload and first_round["status"] == "RETURNED"

schema_v2 = copy.deepcopy(schema)
schema_v2["fields"][0]["label"] = "新版请假类别"
schema_v2["fields"][0]["options"][0]["label"] = "新版年假"
schema_v2["fields"][2]["minimum"] = "2"
definition_v2 = request("POST", "/process-definitions", {**definition_body, "formSchema": schema_v2}, admin)
definition_v2 = request("POST", f'/process-definitions/{definition_v2["id"]}/publish?expectedRevision={definition_v2["revision"]}', token=admin)
assert definition_v2["version"] == 2

application = request("GET", path, token=employee)
assert application["definitionVersion"] == 1 and application["formSchema"] == first_round["formSchema"]
second_payload = {**payload, "leaveType": "1", "reason": "改为事假，01与1必须按单选文本区分"}
application = request("PUT", path, {"expectedVersion": application["version"], "title": create_body["title"], "payload": second_payload}, employee)
application = request("POST", path + "/submit", {"expectedVersion": application["version"]}, employee)
assert act(manager, application["id"], "APPROVE")["applicationStatus"] == "APPROVED"
rounds = sorted(request("GET", path + "/rounds", token=employee), key=lambda value: value["roundNo"])
assert len(rounds) == 2 and rounds[0] == first_round
assert rounds[1]["payload"] == second_payload and rounds[1]["formSchema"] == first_round["formSchema"]
assert rounds[0]["processInstanceId"] != rounds[1]["processInstanceId"]
replay = request("POST", "/applications", create_body, employee, 201, create_key)
assert replay["id"] == application["id"] and replay["status"] == "DRAFT" and replay["formSchema"] == first_round["formSchema"]
new_application = request("POST", "/applications", {**create_body, "businessNo": create_body["businessNo"] + "-V2", "definitionVersion": 2}, employee, 201)
assert new_application["formSchema"]["fields"][0]["label"] == "新版请假类别"
assert request("GET", path, token=employee)["status"] == "APPROVED"

# 额外来源验证只在显式请求且 demo 尚无同名租户模板时运行，不覆盖已有模板。
binding_result = None
if check_bundled_binding:
    builtin_body = {"businessNo": "BIND-" + prefix, "processKey": "expense-reimbursement", "definitionVersion": 1,
                    "title": "内置来源绑定验收", "payload": {"amount": 100}}
    builtin = request("POST", "/applications", builtin_body, employee, 201)
    assert builtin["formSchema"] is None
    tenant_graph = {"nodes": [graph["nodes"][0], graph["nodes"][1], graph["nodes"][4]], "edges": [
        {"id": "e1", "source": "start", "target": "manager", "condition": "", "defaultBranch": False},
        {"id": "e2", "source": "manager", "target": "end", "condition": "", "defaultBranch": False}
    ]}
    tenant_definition = request("POST", "/process-definitions", {"key": "expense-reimbursement", "name": "租户同名流程",
                                                                 "graph": tenant_graph, "formSchema": schema}, admin)
    tenant_definition = request("POST", f'/process-definitions/{tenant_definition["id"]}/publish?expectedRevision={tenant_definition["revision"]}', token=admin)
    assert tenant_definition["version"] == 1
    builtin = request("POST", f'/applications/{builtin["id"]}/submit', {"expectedVersion": builtin["version"]}, employee)
    assert task_for(finance, builtin["id"])["taskName"] == "财务审批"
    assert act(finance, builtin["id"], "APPROVE")["applicationStatus"] == "APPROVED"
    tenant_application = request("POST", "/applications", {**builtin_body, "businessNo": builtin_body["businessNo"] + "-TENANT", "payload": {}}, employee, 201)
    assert tenant_application["formSchema"]["fields"][0]["key"] == "leaveType"
    request("POST", f'/applications/{tenant_application["id"]}/submit', {"expectedVersion": tenant_application["version"]}, employee, 422)
    binding_result = {"result": "PASS", "builtinApplicationId": builtin["id"], "tenantApplicationId": tenant_application["id"]}
print(json.dumps({"result": "PASS", "processKey": process_key, "definitionIds": [definition["id"], definition_v2["id"]],
                  "applicationId": application["id"], "businessNo": create_body["businessNo"],
                  "newVersionApplicationId": new_application["id"], "rounds": 2, "status": "APPROVED",
                  "bundledTenantBinding": binding_result,
                  "verified": ["draft-required-validation", "unknown-field-rejected", "exact-decimal", "false-and-null",
                               "typed-runtime-route", "v1-v2-binding", "immutable-rounds", "idempotent-schema-replay"]}, ensure_ascii=False, indent=2))
