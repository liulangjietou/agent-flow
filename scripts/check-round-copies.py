"""在独立演示实例验证抄送快照、权限、附件和幂等，并保留浏览器验收数据。"""
import hashlib
import json
import sys
import urllib.error
import urllib.request
import uuid

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:18210").rstrip("/") + "/api/v1"
PREFIX = "copy-" + uuid.uuid4().hex[:10]
tokens = {}
count = 0


def request(method, path, user=None, body=None, expected=200, key=None, binary=False, headers=None):
    global count
    values = {"Content-Type": "application/octet-stream" if binary else "application/json", **(headers or {})}
    if user:
        values["Authorization"] = "Bearer " + tokens[user]
    if method != "GET" and not path.startswith("/auth/") and not binary:
        values["Idempotency-Key"] = key or str(uuid.uuid4())
    data = body if binary else None if body is None else json.dumps(body, ensure_ascii=False).encode()
    req = urllib.request.Request(BASE + path, data=data, headers=values, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        count += 1
        raw = response.read()
        assert response.status == expected, (path, response.status, raw.decode(errors="replace"))
        if response.headers.get_content_type() == "application/octet-stream":
            assert response.headers["Cache-Control"] == "no-store"
            assert response.headers["X-Content-Type-Options"] == "nosniff"
            assert response.headers["Content-Disposition"].startswith("attachment;")
            return raw
        return json.loads(raw)


for user in ("admin", "alice", "bob", "manager"):
    tokens[user] = request("POST", "/auth/login", body={"tenantId": "demo", "username": user, "password": "demo"})["token"]
assert any(option["rule"] == "user:bob" for option in request("GET", "/process-definitions/copy-options", "admin"))
request("GET", "/process-definitions/copy-options", "bob", expected=403)
graph = {"nodes": [
    {"id": "start", "name": "开始", "type": "START", "properties": {}},
    {"id": "copy", "name": "抄送经办人", "type": "COPY", "properties": {"recipientRule": "user:bob"}},
    {"id": "review", "name": "主管审批", "type": "USER_TASK", "properties": {"assigneeRule": "user:manager"}},
    {"id": "end", "name": "结束", "type": "END", "properties": {}}],
    "edges": [{"id": "e1", "source": "start", "target": "copy", "condition": ""},
              {"id": "e2", "source": "copy", "target": "review", "condition": ""},
              {"id": "e3", "source": "review", "target": "end", "condition": ""}]}
schema = {"schemaVersion": 2, "fields": [
    {"key": "reason", "label": "公开说明", "type": "TEXTAREA", "required": False},
    {"key": "account", "label": "账户信息", "type": "TEXT", "required": False, "nodeAccess": {"copy": "MASKED"}},
    {"key": "secret", "label": "内部敏感信息", "type": "TEXT", "required": False, "nodeAccess": {"copy": "HIDDEN"}},
    {"key": "proof", "label": "公开附件", "type": "ATTACHMENT", "required": False},
    {"key": "items", "label": "内部明细", "type": "TABLE", "required": False, "columns": [
        {"key": "receipt", "label": "内部票据", "type": "ATTACHMENT", "required": False, "nodeAccess": {"copy": "HIDDEN"}}]}]}
definition = request("POST", "/process-definitions", "admin", {"key": PREFIX, "name": "抄送快照验收", "graph": graph, "formSchema": schema})
request("POST", f"/process-definitions/{definition['id']}/publish?expectedRevision=0", "admin", {"changeNote": "抄送快照与附件验收"})
draft = request("POST", "/process-definitions", "admin", {"key": PREFIX + "-ui", "name": "抄送节点设计验收", "graph": graph, "formSchema": schema})
app = request("POST", "/applications", "alice", {"businessNo": PREFIX, "processKey": PREFIX, "definitionVersion": 1, "title": "第一轮抄送：采购资料核对", "payload": {}}, expected=201)
app_id = app["id"]
content = "本轮公开材料，原始内容保持不变。\n".encode()
files = []
for path, filename in [("proof", "公开材料.txt"), ("items.receipt", "内部票据.txt")]:
    metadata = request("POST", f"/applications/{app_id}/attachments", "alice", {
        "expectedVersion": 1, "fieldPath": path, "filename": filename, "size": len(content), "sha256": hashlib.sha256(content).hexdigest()}, expected=201)
    request("PUT", f"/applications/{app_id}/attachments/{metadata['id']}/content", "alice", content, binary=True, headers={"X-Application-Version": "1"})
    files.append(metadata["id"])
request("PUT", f"/applications/{app_id}", "alice", {"expectedVersion": 1, "title": app["title"], "payload": {
    "reason": "请核对这一轮提交的采购资料。", "account": "6222000000000000", "secret": "仅供财务内部核对", "proof": [files[0]], "items": [{"receipt": [files[1]]}]}})
key = str(uuid.uuid4())
submitted = request("POST", f"/applications/{app_id}/submit", "alice", {"expectedVersion": 2}, key=key)
assert request("POST", f"/applications/{app_id}/submit", "alice", {"expectedVersion": 2}, key=key) == submitted
copy_path = f"/copies/{app_id}/rounds/1"
view = request("GET", copy_path, "bob")
assert view["payload"]["account"] == "已脱敏"
assert "secret" not in view["payload"] and "items" not in view["payload"]
assert "6222000000000000" not in json.dumps(view)
request("GET", f"/applications/{app_id}", "bob", expected=404)
request("GET", copy_path, "admin", expected=404)
request("GET", f"/copies/{app_id}/rounds/2", "bob", expected=404)
request("GET", copy_path + f"/attachments/{files[0]}", "bob")
assert request("GET", copy_path + f"/attachments/{files[0]}/content", "bob") == content
request("GET", copy_path + f"/attachments/{files[1]}", "bob", expected=403)
request("GET", copy_path + f"/attachments/{files[1]}/content", "bob", expected=403)
inbox = request("GET", "/notifications", "bob")
assert len([item for item in inbox["items"] if item["applicationId"] == app_id and item["kind"] == "APPLICATION_COPIED"]) == 1
request("POST", f"/applications/{app_id}/withdraw", "alice", {"expectedVersion": 3, "comment": "补正其他内容"})
request("PUT", f"/applications/{app_id}", "alice", {"expectedVersion": 4, "title": "新草稿标题不可见", "payload": {"reason": "尚未提交的新草稿不可见"}})
assert request("GET", copy_path, "bob")["payload"] == view["payload"]
assert request("GET", copy_path + f"/attachments/{files[0]}/content", "bob") == content
print(json.dumps({"result": "PASS", "httpRequests": count, "applicationId": app_id, "definitionId": definition["id"],
                  "draftDefinitionId": draft["id"], "processKey": PREFIX, "roundNo": 1, "attachmentId": files[0],
                  "sha256": hashlib.sha256(content).hexdigest()}, ensure_ascii=False, indent=2))
