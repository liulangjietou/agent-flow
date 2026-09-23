"""在独立演示库验证模板文件的创建、发布和运行链路；保留所有合成记录。"""
import argparse
import copy
import json
import pathlib
import urllib.error
import urllib.request
import uuid


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("base")
parser.add_argument("output", help="新的验收目录")
args = parser.parse_args()
base = args.base.rstrip("/") + "/api/v1"
output = pathlib.Path(args.output)
output.mkdir(parents=True, exist_ok=False)
prefix = "portable-" + uuid.uuid4().hex[:10]


def request(method, path, body=None, token=None, expected=200, key=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if method in ("POST", "PUT") and path != "/auth/login":
        headers["Idempotency-Key"] = key or str(uuid.uuid4())
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    try:
        response = urllib.request.urlopen(urllib.request.Request(base + path, data=data, headers=headers, method=method), timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        result = json.load(response)
        assert response.status == expected, (path, response.status, expected, result.get("code") if isinstance(result, dict) else None)
        return result


tokens = {user: request("POST", "/auth/login", {"tenantId": "demo", "username": user, "password": "demo"})["token"]
          for user in ("admin", "employee", "manager")}
admin, employee = tokens["admin"], tokens["employee"]
template = next(item for item in request("GET", "/process-templates", token=admin) if item["key"] == "leave-request")
source = request("POST", "/process-definitions", {"key": prefix + "-source", "name": "模板文件来源流程", "graph": template["graph"], "formSchema": template["formSchema"]}, admin)
source_path = "/process-definitions/" + source["id"]
source = request("POST", source_path + "/publish?expectedRevision=0", {"changeNote": "模板文件验收来源"}, admin)
portable = {"format": "agentflow-process-template", "formatVersion": 1, "process": {key: source[key] for key in ("key", "name", "graph", "formSchema")}}
raw = json.dumps(portable, ensure_ascii=False, indent=2) + "\n"
(output / "source-template.json").write_text(raw)
(output / "unsupported-template.json").write_text(json.dumps({**portable, "formatVersion": 99}))
body = {**json.loads(raw)["process"], "key": prefix + "-import", "name": "文件导入独立草稿"}
assert request("POST", "/process-definitions/validate", {key: body[key] for key in ("graph", "formSchema")}, admin)["errors"] == []
request("POST", "/process-definitions", body, employee, expected=403)
write_key = prefix + "-create"
draft = request("POST", "/process-definitions", body, admin, key=write_key)
assert request("POST", "/process-definitions", body, admin, key=write_key) == draft
assert draft["id"] != source["id"] and draft["status"] == "DRAFT" and draft["version"] == 0
assert draft["graph"] == source["graph"] and draft["formSchema"] == source["formSchema"]
assert request("POST", "/process-definitions", {**body, "name": "另一请求"}, admin, expected=409, key=write_key)["code"] == "IDEMPOTENCY_KEY_REUSED"

# 未识别的审批账号允许先保存草稿，但预检与发布都必须报告问题。
unavailable = copy.deepcopy(body)
unavailable["key"] = prefix + "-unavailable"
review_node = next(node for node in unavailable["graph"]["nodes"] if node["type"] == "USER_TASK")
review_node["properties"]["assigneeRule"] = "user:portable-missing-user"
assert "ASSIGNEE_NOT_AVAILABLE:" + review_node["id"] in request("POST", "/process-definitions/validate", unavailable, admin)["errors"]
blocked = request("POST", "/process-definitions", unavailable, admin)
request("POST", "/process-definitions/" + blocked["id"] + "/publish?expectedRevision=0", {"changeNote": "不可用审批人必须阻断"}, admin, expected=422)
assert request("GET", "/process-definitions/" + blocked["id"], token=admin)["status"] == "DRAFT"
(output / "unavailable-template.json").write_text(json.dumps({**portable, "process": unavailable}, ensure_ascii=False, indent=2))
unsafe = copy.deepcopy(body)
next(node for node in unsafe["graph"]["nodes"] if node["type"] == "USER_TASK")["name"] = "${unsafe.run()}"
request("POST", "/process-definitions", unsafe, admin, expected=422)

path = "/process-definitions/" + draft["id"]
published = request("POST", path + "/publish?expectedRevision=0", {"changeNote": "文件导入配置核对后发布"}, admin)
scenario = next(item for item in template["scenarios"] if not item["expectedFieldErrors"])
assert request("POST", path + "/simulate", {"values": scenario["payload"]}, admin)["path"] == scenario["expectedPath"]
app = request("POST", "/applications", {"businessNo": prefix.upper(), "processKey": published["key"], "definitionVersion": published["version"], "title": "模板文件真实审批", "payload": scenario["payload"]}, employee, expected=201)
app_path = "/applications/" + app["id"]
app = request("POST", app_path + "/submit", {"expectedVersion": app["version"]}, employee)

# 同标识再建草稿并发布新版本，不能覆盖旧定义或在途申请。
new_body = copy.deepcopy(body)
new_body["name"] = "文件导入的后续版本"
new_body["formSchema"]["fields"][0]["label"] += "（新版本）"
second = request("POST", "/process-definitions", new_body, admin)
assert second["id"] not in (source["id"], draft["id"])
second = request("POST", "/process-definitions/" + second["id"] + "/publish?expectedRevision=0", {"changeNote": "验证同标识导入不会覆盖历史"}, admin)
assert second["version"] == 2
assert request("GET", path, token=admin) == published
nodes = {node["id"]: node for node in published["graph"]["nodes"]}
for node_id in scenario["expectedPath"]:
    node = nodes[node_id]
    if node["type"] != "USER_TASK":
        continue
    user = {"role:MANAGER": "manager", "role:ADMIN": "admin"}[node["properties"]["assigneeRule"]]
    tasks = [task for task in request("GET", "/tasks", token=tokens[user]) if task["applicationId"] == app["id"]]
    assert len(tasks) == 1 and tasks[0]["taskName"] == node["name"]
    task = tasks[0]
    request("POST", "/tasks/" + task["taskId"] + "/actions", {"action": "APPROVE", "expectedVersion": task["version"], "comment": "模板文件真实流程验收"}, tokens[user])
assert request("GET", app_path, token=employee)["status"] == "APPROVED"
rounds = request("GET", app_path + "/rounds", token=employee)
assert len(rounds) == 1 and rounds[0]["formSchema"] == source["formSchema"] and rounds[0]["payload"] == scenario["payload"]
assert request("GET", source_path, token=admin) == source
assert next(item for item in request("GET", "/process-templates", token=admin) if item["key"] == template["key"])["graph"] == template["graph"]
report = {"result": "PASS", "prefix": prefix, "source": source["id"], "imported": published["id"], "secondVersion": second["id"],
          "blockedDraft": blocked["id"], "application": app["id"], "browserTargetKey": prefix + "-browser",
          "verified": ["readable-file", "create-only", "permissions", "idempotency", "assignee-preflight", "publish-block", "expression-block", "simulation", "real-approval", "same-key-version-isolation", "source-unchanged"]}
(output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
print(json.dumps(report, ensure_ascii=False))
