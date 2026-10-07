"""验证演示入口的自检、权限、同源代理和业务数据只读性，不创建或删除业务记录。"""
import json
import sys
import urllib.error
import urllib.request


origin = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8180").rstrip("/")


def request(path, token=None, body=None, expected=200, browser_origin=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if browser_origin:
        headers["Origin"] = browser_origin
    data = None if body is None else json.dumps(body).encode()
    try:
        response = urllib.request.urlopen(urllib.request.Request(origin + path, data=data, headers=headers), timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        content = response.read().decode()
        assert response.status == expected, (path, response.status)
        content_type = response.headers.get_content_type()
        return json.loads(content) if content_type == "application/json" or content_type.endswith("+json") else content


def login(user):
    return request("/api/v1/auth/login", body={"tenantId": "demo", "username": user, "password": "demo"}, browser_origin=origin)["token"]


admin = login("admin")
manager = login("manager")
path = "/api/v1/system/checks"
assert request(path, expected=401)["code"] == "UNAUTHENTICATED"
assert request(path, manager, expected=403)["code"] == "FORBIDDEN"
request("/api/v1/auth/login", body={"tenantId": "demo", "username": "admin", "password": "demo"},
        browser_origin="https://untrusted.invalid", expected=403)
resources = ["/api/v1/process-definitions", "/api/v1/applications", "/api/v1/tasks", "/api/v1/process-templates",
             "/api/v1/organization"]
before = [request(resource, admin) for resource in resources]
report = request(path, admin)
after = [request(resource, admin) for resource in resources]
assert before == after, "Read-only check must not modify visible business state"
checks = {check["id"]: check for check in report["checks"]}
assert len(checks) == len(report["checks"]), "Check identifiers must be unique"
assert set(checks) == {"database", "migrations", "flowable", "templates", "authentication", "notifications",
                       "sessionStorage", "organization", "organizationSync", "objectStorage", "model"}
for check_id in ("database", "migrations", "flowable", "templates"):
    assert checks[check_id]["status"] == "UP", (check_id, checks[check_id]["code"])
assert checks["authentication"]["code"] == "DEMO_AUTH_ONLY"
# 组织来源只核对本地配置，不把配置成功报告为企业连接或同步验收。
assert checks["organizationSync"]["status"] == "WARNING"
assert checks["organizationSync"]["code"] in {"ORGANIZATION_SYNC_" + value for value in
    ("DISABLED", "UNCONFIGURED", "SOURCE_CHANGED", "WORKER_DISABLED", "CONFIGURED")}
assert not any(check["status"] == "NOT_IMPLEMENTED" for check in report["checks"])
assert checks["objectStorage"]["code"] in {"LOCAL_ATTACHMENT_STORAGE", "ATTACHMENT_STORAGE_NOT_CONFIGURED"}
assert checks["objectStorage"]["status"] == ("UP" if checks["objectStorage"]["code"] == "LOCAL_ATTACHMENT_STORAGE" else "WARNING")
assert checks["model"]["status"] == "WARNING"
assert checks["model"]["code"] in {"AGENT_MODEL_DISABLED", "AGENT_MODEL_WORKER_DISABLED", "AGENT_MODEL_CONFIGURED"}
initialized = before[-1]["initialized"]
assert checks["organization"]["status"] == ("UP" if initialized else "WARNING")
assert checks["organization"]["code"] == ("LOCAL_ORGANIZATION_ENABLED" if initialized else "LOCAL_ORGANIZATION_NOT_INITIALIZED")
assert checks["notifications"]["status"] == "WARNING"
assert checks["notifications"]["code"] in {"NOTIFICATION_WORKER_DISABLED", "NOTIFICATION_BINDINGS_MISSING", "NOTIFICATION_CHANNELS_CONFIGURED"}
assert request("/actuator/health/readiness")["status"] == "UP"
for forbidden in ("jdbc:", "password=", "Bearer ", "agentflow-local-demo-only"):
    assert forbidden not in json.dumps(report)
print(json.dumps({"result": "PASS", "origin": origin, "report": report,
                  "verified": ["anonymous-401", "approver-403", "admin-checks", "same-origin-login",
                               "untrusted-origin-403", "readiness", "business-state-unchanged", "redacted-response",
                               "local-organization-state", "organization-sync-configuration", "adapter-configuration-not-delivery"]},
                 ensure_ascii=False, indent=2))
