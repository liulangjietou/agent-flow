"""用退回重提、部分会签和幂等回放验证恢复；仅用于允许写入合成数据的隔离本机实例。"""
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

mode, base, state_path, flag = sys.argv[1:]
assert mode in ("prepare", "verify", "finish") and flag == "--exercise"
assert urllib.parse.urlsplit(base).hostname in ("127.0.0.1", "localhost", "::1")
tokens = {}


def request(method, path, user, body=None, expected=200, key=None):
    headers = {"Content-Type": "application/json"}
    if user in tokens:
        headers["Authorization"] = "Bearer " + tokens[user]
    if method != "GET":
        headers["Idempotency-Key"] = key or str(uuid.uuid4())
    req = urllib.request.Request(base.rstrip("/") + "/api/v1" + path, headers=headers, method=method,
                                 data=None if body is None else json.dumps(body, ensure_ascii=False).encode())
    try:
        response = urllib.request.urlopen(req, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        value = json.load(response)
        assert response.status == expected, (path, response.status, value.get("code"))
        return value


for user in ("admin", "alice", "finance", "bob"):
    tokens[user] = request("POST", "/auth/login", user, {"tenantId": "demo", "username": user, "password": "demo"})["token"]


def task(user, application_id):
    return next(item for item in request("GET", "/tasks", user) if item["applicationId"] == application_id)


def facts(application_id):
    return {path: request("GET", "/applications/" + application_id + path, "alice")
            for path in ("", "/rounds", "/audit?limit=100", "/timeline?limit=100", "/comments?limit=100")}


if mode == "prepare":
    prefix = "recovery-" + uuid.uuid4().hex[:10]
    graph = {"nodes": [{"id": "start", "name": "开始", "type": "START", "properties": {}},
                       {"id": "review", "name": "恢复验证会签", "type": "USER_TASK", "properties": {"assigneeRule": "role:FINANCE", "approvalMode": "ALL"}},
                       {"id": "end", "name": "结束", "type": "END", "properties": {}}],
             "edges": [{"id": "first", "source": "start", "target": "review", "condition": ""},
                       {"id": "last", "source": "review", "target": "end", "condition": ""}]}
    schema = {"schemaVersion": 1, "fields": [{"key": "amount", "label": "核对金额", "type": "NUMBER", "required": True}]}
    draft = request("POST", "/process-definitions", "admin", {"key": prefix, "name": "备份恢复会签", "graph": graph, "formSchema": schema})
    request("POST", "/process-definitions/" + draft["id"] + "/publish?expectedRevision=0", "admin", {"changeNote": "验证未完成审批恢复后继续办理"})
    app = request("POST", "/applications", "alice", {"businessNo": prefix, "title": "恢复后继续办理的会签申请", "processKey": prefix,
                  "definitionVersion": 1, "payload": {"amount": "99999999999999999999.123456789"}}, expected=201)
    app = request("POST", "/applications/" + app["id"] + "/submit", "alice", {"expectedVersion": app["version"]})
    first = task("finance", app["id"])
    request("POST", "/tasks/" + first["taskId"] + "/actions", "finance", {"action": "RETURN", "expectedVersion": first["version"], "comment": "保留原退回轮次供恢复核对"})
    app = request("GET", "/applications/" + app["id"], "alice")
    app = request("POST", "/applications/" + app["id"] + "/submit", "alice", {"expectedVersion": app["version"]})
    partial = task("finance", app["id"])
    body = {"action": "APPROVE", "expectedVersion": partial["version"], "comment": "财务已核对，等待另一会签人"}
    replay_key = str(uuid.uuid4())
    result = request("POST", "/tasks/" + partial["taskId"] + "/actions", "finance", body, key=replay_key)
    assert result["applicationStatus"] == "IN_APPROVAL"
    app = request("GET", "/applications/" + app["id"], "alice")
    request("POST", "/applications/" + app["id"] + "/comments", "alice", {"expectedVersion": app["version"], "content": "恢复验证：保留这条协作评论。"}, expected=201)
    pending = task("admin", app["id"])
    assert pending["countersign"] == {"total": 2, "completed": 1}
    state = {"applicationId": app["id"], "businessNo": prefix, "pending": pending, "facts": facts(app["id"]),
             "replay": {"taskId": partial["taskId"], "key": replay_key, "body": body, "result": result}}
    with Path(state_path).open("x") as stream:
        os.chmod(state_path, 0o600); json.dump(state, stream, ensure_ascii=False, indent=2)
    print(json.dumps({"status": "PREPARED", "applicationId": app["id"], "businessNo": prefix, "countersign": pending["countersign"]}))
else:
    state = json.loads(Path(state_path).read_text())
    application_id = state["applicationId"]
    current = facts(application_id)
    if mode == "verify":
        assert current == state["facts"]
        assert task("admin", application_id) == state["pending"]
        replay = state["replay"]
        assert request("POST", "/tasks/" + replay["taskId"] + "/actions", "finance", replay["body"], key=replay["key"]) == replay["result"]
        assert facts(application_id) == current
        request("GET", "/applications/" + application_id, "bob", expected=404)
    else:
        assert current[""]["status"] == "APPROVED" and current[""]["roundNo"] == 2
        assert current["/rounds"][0] == state["facts"]["/rounds"][0]
        assert current["/rounds"][1]["status"] == "APPROVED"
        assert current["/comments?limit=100"] == state["facts"]["/comments?limit=100"]
        assert not any(item["applicationId"] == application_id for item in request("GET", "/tasks", "admin"))
    print(json.dumps({"status": "PASS", "mode": mode, "applicationId": application_id}))
