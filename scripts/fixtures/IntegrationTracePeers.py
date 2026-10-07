"""集成追踪的回环协议接收方，只使用合成签署、服务任务、组织和通知数据。"""

import base64
from email.parser import BytesParser
from email.policy import default
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import socketserver
import struct
import subprocess
import threading
from urllib.parse import parse_qs, urlparse


def sha(data):
    return hashlib.sha256(data).hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


class Peers:
    """所有目标绑定 127.0.0.1，协议原身份和追踪头独立核对。"""
    def __init__(self, directory, node, now):
        self.directory, self.node, self.now = directory, node, now
        self.calls, self.errors, self.effects, self.signatures, self.mail = [], [], {}, {}, []
        self.lock = threading.RLock(); self.contract_digest = None
        self.key = directory / "synthetic-receipt-key.der"
        self.crypto = directory / "synthetic-signature.mjs"
        self.crypto.write_text("""import { generateKeyPairSync, createPrivateKey, sign } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
const file = process.argv[3];
if (process.argv[2] === 'generate') {
 const pair = generateKeyPairSync('ed25519');
 writeFileSync(file, pair.privateKey.export({type:'pkcs8',format:'der'}), {mode:0o600});
 process.stdout.write(pair.publicKey.export({type:'spki',format:'der'}).toString('base64'));
} else {
 const key = createPrivateKey({key:readFileSync(file),format:'der',type:'pkcs8'});
 process.stdout.write(sign(null,Buffer.concat([Buffer.from('agentflow-signature-receipt-1\\n'),readFileSync(0)]),key).toString('base64'));
}
""")
        self.public_key = subprocess.check_output([node, str(self.crypto), "generate", str(self.key)], text=True)
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                self.dispatch(b"")

            def do_POST(self):
                self.dispatch(self.rfile.read(int(self.headers["Content-Length"])))

            def dispatch(self, raw):
                try:
                    path = urlparse(self.path).path
                    record = {"path": path, "method": self.command, "traceId": self.headers.get("X-Trace-Id"),
                              "bodySha256": sha(raw), "idempotencyKey": self.headers.get("Idempotency-Key")}
                    headers = {}
                    if path.startswith("/service/"):
                        assert self.headers.get("Authorization") == "Bearer synthetic-service-token"
                        body = json.loads(raw); execute = path.endswith("/execute")
                        identity = body["command"]["id"] if execute else body["operationId"]
                        record["operationId"] = identity
                        with owner.lock:
                            if execute:
                                assert record["idempotencyKey"] == identity
                                assert identity not in owner.effects, "Duplicate service effect"
                                owner.effects[identity] = {"operationId": identity, "commandDigest": body["commandDigest"],
                                    "status": "APPLIED", "reference": "synthetic-" + identity, "completedAt": owner.now()}
                            else:
                                assert not record["idempotencyKey"] and "inputs" not in body and "binding" not in body
                            result = {"protocolVersion": 1, "tenantId": "demo", "operationKey": "trace.register", "operationVersion": 1,
                                      "contractDigest": owner.contract_digest, "observation": owner.effects[identity]}
                        media = "application/json"
                    elif path.startswith("/signature/"):
                        assert self.headers.get("Authorization") == "Bearer synthetic-signature-token"
                        result, media, headers, identity = owner.signature(path, self.headers, raw)
                        record["operationId"] = identity
                    elif path == "/hr/changes":
                        query = parse_qs(urlparse(self.path).query)
                        assert self.command == "GET" and not raw and query["tenantId"] == ["demo"] and query["sourceKey"] == ["hr"]
                        after = int(query["afterRevision"][0])
                        result = {"contractVersion": 1, "tenantId": "demo", "sourceKey": "hr", "afterRevision": after, "revision": after + 1,
                                  "units": [], "people": [{"externalId": "synthetic-source-person", "subject": "unapplied-person",
                                  "displayName": "合成来源人员", "active": True, "approvalEligible": False}], "appointments": []}
                        media = "application/json"
                    elif path == "/cgi-bin/gettoken":
                        result, media = {"errcode": 0, "access_token": "synthetic-trace-token", "expires_in": 7200}, "application/json"
                    elif path == "/cgi-bin/message/send":
                        body = json.loads(raw)
                        assert body["touser"] == "TraceManager" and body["msgtype"] == "text"
                        assert not any(word in raw.decode() for word in ("traceId", "PRIVATE", "敏感正文"))
                        result, media = {"errcode": 0, "msgid": "synthetic-receipt"}, "application/json"
                    else:
                        raise AssertionError(path)
                    with owner.lock:
                        owner.calls.append(record); owner.persist()
                    data = json.dumps(result, ensure_ascii=False, separators=(",", ":")).encode() if media == "application/json" else result
                    self.send_response(200); self.send_header("Content-Type", media); self.send_header("Content-Length", str(len(data)))
                    for key, value in headers.items(): self.send_header(key, value)
                    self.end_headers(); self.wfile.write(data)
                except (BrokenPipeError, ConnectionResetError):
                    pass
                except Exception as failure:
                    with owner.lock:
                        owner.errors.append(repr(failure)); owner.persist()
                    self.send_error(500)

        class Smtp(socketserver.StreamRequestHandler):
            def handle(self):
                self.connection.settimeout(15); self.wfile.write(b"220 synthetic.local ESMTP\r\n")
                while True:
                    line = self.rfile.readline(4096)
                    if not line: return
                    command = line.split(b" ", 1)[0].strip().upper()
                    if command in (b"EHLO", b"HELO"):
                        self.wfile.write(b"250 synthetic.local\r\n")
                    elif command in (b"MAIL", b"RCPT", b"RSET", b"NOOP"):
                        self.wfile.write(b"250 OK\r\n")
                    elif command == b"DATA":
                        self.wfile.write(b"354 End with dot\r\n"); data = bytearray()
                        while True:
                            part = self.rfile.readline(65536)
                            assert part, "Incomplete SMTP DATA"
                            if part == b".\r\n": break
                            data.extend(part[1:] if part.startswith(b"..") else part)
                            assert len(data) <= 32768
                        message = BytesParser(policy=default).parsebytes(bytes(data))
                        assert "PRIVATE" not in message.get_body().get_content()
                        with owner.lock:
                            owner.mail.append({"traceId": message.get("X-Trace-Id"), "messageId": message["Message-ID"], "sha256": sha(data)})
                            owner.persist()
                        self.wfile.write(b"250 accepted\r\n")
                    elif command == b"QUIT":
                        self.wfile.write(b"221 bye\r\n"); return
                    else:
                        raise AssertionError(command)

        self.http = ThreadingHTTPServer(("127.0.0.1", 0), Handler); self.http.daemon_threads = True
        self.smtp = socketserver.ThreadingTCPServer(("127.0.0.1", 0), Smtp); self.smtp.daemon_threads = True
        self.threads = [threading.Thread(target=server.serve_forever, daemon=True) for server in (self.http, self.smtp)]
        for thread in self.threads: thread.start()
        self.base = "http://127.0.0.1:" + str(self.http.server_port)

    def signature(self, path, headers, raw):
        suffix = path.rsplit("/", 1)[1]
        if suffix == "submit":
            message = BytesParser(policy=default).parsebytes(("Content-Type: " + headers["Content-Type"] + "\r\nMIME-Version: 1.0\r\n\r\n").encode() + raw)
            parts = list(message.iter_parts()); value = json.loads(parts[0].get_payload(decode=True)); request = value["request"]; identity = request["id"]
            assert value["protocol"] == "agentflow-signature-http-1" and headers["Idempotency-Key"] == identity
            assert len(parts) == len(request["documents"]) + 1 and identity not in self.signatures
            originals = {part.get_param("name", header="content-disposition"): part.get_payload(decode=True) for part in parts[1:]}
            for document in request["documents"]:
                original = originals["file-" + document["attachmentId"]]
                assert sha(original) == document["sha256"] and len(original) == document["size"]
            results = {doc["attachmentId"]: ("%PDF-1.7\nsynthetic signed " + identity + " " + doc["attachmentId"] + "\n%%EOF").encode() for doc in request["documents"]}
            stamp = self.now(); authorization = request["authorization"]
            receipt = {"operationId": identity, "requestDigest": value["requestDigest"], "revision": 1, "status": "SIGNED", "recordedAt": stamp,
                "providerReference": "synthetic-" + identity, "completedAt": stamp, "artifacts": [
                    {"documentId": doc["attachmentId"], "size": len(results[doc["attachmentId"]]), "sha256": sha(results[doc["attachmentId"]]),
                     "mediaType": "application/pdf", "signatures": [{"signerKey": signer["key"], "certificateSha256": "f" * 64,
                     "signedAt": stamp, "timestampReference": None} for signer in request["signers"]]} for doc in request["documents"]]}
            envelope = {"protocol": "agentflow-signature-receipt-1", "tenantId": "demo", "profileKey": authorization["profileKey"],
                        "profileVersion": authorization["profileVersion"], "profileDigest": authorization["profileDigest"],
                        "targetDigest": value["targetDigest"], "receipt": receipt}
            self.signatures[identity] = {"input": value, "results": results, "envelope": envelope}
        else:
            value = json.loads(raw); query = value["operation"] if suffix == "artifact" else value; identity = query["operationId"]
            original = self.signatures[identity]["input"]; authorization = original["request"]["authorization"]
            assert query == {"protocol": "agentflow-signature-http-1", "tenantId": "demo", "operationId": identity,
                             "requestDigest": original["requestDigest"], "targetDigest": original["targetDigest"],
                             **{key: authorization[key] for key in ("profileKey", "profileVersion", "profileDigest")}}
            assert not headers.get("Idempotency-Key")
        operation = self.signatures[identity]; envelope = operation["envelope"]
        if suffix == "artifact":
            receipt = envelope["receipt"]; data = operation["results"][value["documentId"]]
            assert value["revision"] == receipt["revision"] and value["receiptDigest"] == self.receipt_digest(receipt)
            assert value["size"] == len(data) and value["sha256"] == sha(data)
            return data, "application/pdf", {}, identity
        data = json.dumps(envelope, ensure_ascii=False, separators=(",", ":")).encode()
        signature = subprocess.check_output([self.node, str(self.crypto), "sign", str(self.key)], input=data).decode()
        # 使用已经签名的原始字节，避免中间序列化改变签名正文。
        return data, "application/json; charset=utf-8", {"X-Agentflow-Receipt-Signature": signature}, identity

    @staticmethod
    def receipt_digest(receipt):
        values = ["agentflow-signature-receipt-1", receipt["operationId"], receipt["requestDigest"], str(receipt["revision"]), receipt["status"],
                  receipt["recordedAt"], receipt["providerReference"] or "", receipt["completedAt"] or "", str(len(receipt["artifacts"]))]
        for artifact in receipt["artifacts"]:
            values += [artifact["documentId"], str(artifact["size"]), artifact["sha256"], artifact["mediaType"], str(len(artifact["signatures"]))]
            for proof in artifact["signatures"]:
                values += [proof["signerKey"], proof["certificateSha256"], proof["signedAt"], proof["timestampReference"] or ""]
        return sha(b"".join(struct.pack(">I", len(value.encode())) + value.encode() for value in values))

    def persist(self):
        save(self.directory / "integration-peer.json", {"calls": self.calls, "mail": self.mail, "errors": self.errors,
                                                       "serviceEffects": self.effects, "signatureIds": list(self.signatures)})

    def close(self):
        for server in (self.http, self.smtp): server.shutdown(); server.server_close()
        for thread in self.threads: thread.join(timeout=5)
