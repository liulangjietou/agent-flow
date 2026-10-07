#!/usr/bin/env python3
"""固定安装包验证 OFD 全页外发、原请求恢复及包含字体的独立配套还原。"""

import argparse
import base64
import copy
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
from pathlib import Path
import shutil
import threading
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("split_runtime", ROOT / "scripts/check-expense-split-routing.py")
split = importlib.util.module_from_spec(spec)
spec.loader.exec_module(split)
risk = split.risk
save, digest, wait_for = risk.save, risk.digest, risk.wait_for


class Model:
    """仅接收当前回环请求并核对全部 PNG，候选值为合成协议响应，不证明 OCR 准确率。"""

    def __init__(self, directory, cases):
        self.requests, self.errors = [], []
        expected = {digest(Path(case["source"])): case for case in cases}
        model = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                try:
                    length = int(self.headers["Content-Length"])
                    assert 0 < length < 32 * 1024 * 1024
                    body = json.loads(self.rfile.read(length))
                    parts = body["messages"][1]["content"]
                    source = json.loads(parts[0]["text"])["source"]
                    case = expected[source["originalDigest"]]
                    assert source["format"] == "OFD" and source["pageCount"] == len(case["images"])
                    assert len(parts) == 1 + 2 * source["pageCount"]
                    actual = []
                    for index, image in enumerate(case["images"], 1):
                        assert parts[index * 2 - 1] == {"type": "text", "text": f"OFD page {index} of {source['pageCount']}"}
                        part = parts[index * 2]
                        assert part["type"] == "image_url" and part["image_url"]["detail"] == "high"
                        url = part["image_url"]["url"]
                        assert url.startswith("data:image/png;base64,")
                        actual.append(hashlib.sha256(base64.b64decode(url.split(",", 1)[1], validate=True)).hexdigest())
                        assert actual[-1] == image["sha256"]
                    serialized = json.dumps(body)
                    assert "file_data" not in serialized and "private-source.ofd" not in serialized
                    record = {"source": source, "imageSha256": actual, "request": body}
                    model.requests.append(record); save(directory / "model-requests.json", model.requests)
                    suggestion = {"proposals": [{"field": "INVOICE_NUMBER", "value": "0000123", "confidence": "LOW",
                        "evidence": [{"originalId": source["originalId"], "originalDigest": source["originalDigest"],
                                      "page": source["pageCount"], "quote": "合成协议候选，须本人对照原件"}]}]}
                    result = {"model": "synthetic-ofd-v1", "choices": [{"finish_reason": "stop", "message": {
                        "role": "assistant", "content": json.dumps(suggestion, ensure_ascii=False)}}]}
                    raw = json.dumps(result, ensure_ascii=False).encode()
                    self.send_response(200); self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(raw))); self.end_headers(); self.wfile.write(raw)
                except Exception as error:
                    model.errors.append(repr(error)); save(directory / "model-errors.json", model.errors); self.send_error(500)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True); self.thread.start()

    def close(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join(timeout=5)


def runtime_for(java, directory, sources, idp, model, catalog):
    runtime = risk.Runtime(java, directory, sources, idp)
    runtime.settings.update({"agentflow.assist.endpoint": f"http://127.0.0.1:{model.server.server_port}/model",
        "agentflow.assist.model": "synthetic-ofd-v1", "agentflow.assist.timeout-seconds": 10,
        "agentflow.invoices.ofd-font-catalog": str(catalog), "agentflow.invoices.extraction-worker-enabled": False,
        "agentflow.invoices.extraction-poll-delay-ms": 200})
    return runtime


def upload(runtime, data, format="OFD"):
    result = runtime.call("POST", "/invoices", {"filename": "private-source." + format.lower(), "format": format,
        "size": len(data), "sha256": hashlib.sha256(data).hexdigest()}, expected=201)
    runtime.call("PUT", "/invoices/" + result["id"] + "/content", raw=data)
    return result["id"]


def path(invoice):
    return "/invoices/" + invoice + "/extraction-runs"


def queue_body(options):
    return {"expectedOriginalId": options["input"]["originalId"], "expectedOriginalDigest": options["input"]["originalDigest"],
            "method": options["method"], "targetDigest": options["targetDigest"], "externalSendConfirmed": options["method"] == "MODEL"}


def result(runtime, invoice, run):
    return wait_for(lambda: runtime.call("GET", path(invoice) + "/" + run["id"]),
                    lambda value: value["status"] not in ("QUEUED", "RUNNING"), 60)


def copy_fonts(catalog, destination):
    """完整保留可信配置引用的字体；还原清单使用独立路径，内容摘要必须保持。"""
    destination.mkdir()
    value = json.loads(catalog.read_text())
    for font in value["fonts"]:
        source = Path(font["file"])
        assert digest(source) == font["sha256"]
        target = destination / (font["sha256"] + source.suffix)
        if not target.exists():
            # 部署字体按内容还原，不继承系统字体受保护的 macOS 文件标志。
            shutil.copyfile(source, target)
        assert digest(target) == font["sha256"]
        font["file"] = str(target)
    output = destination / "fonts.json"; save(output, value)
    return output


def run(args):
    directory = Path(args.output)
    assert str(directory).startswith("/fyoung/tmp/") and directory.resolve().is_relative_to(Path("/fyoung/tmp").resolve()) and not directory.exists()
    directory.mkdir()
    before_jar, after_jar, catalog = Path(args.before).resolve(), Path(args.after).resolve(), Path(args.font_catalog).resolve()
    cases = json.loads(Path(args.cases).read_text())
    assert len(cases) >= 2 and len(cases[0]["images"]) == 1 and len(cases[1]["images"]) > 1
    for case in cases:
        assert digest(Path(case["source"])) == case["sourceSha256"]
        for image in case["images"]:
            assert digest(Path(image["file"])) == image["sha256"]
    evidence = {"status": "RUNNING", "database": "H2", "beforeSha256": digest(before_jar), "afterSha256": digest(after_jar),
                "browserVerified": False, "postgresqlVerified": False, "realEnterpriseModelVerified": False, "checks": []}
    save(directory / "evidence.json", evidence)
    sources = risk.Sources(directory); model = Model(directory, cases)
    idp = runtime = restored = None
    try:
        idp = risk.IdentityProvider(args.java, directory, after_jar)
        runtime = runtime_for(args.java, directory / "original", sources, idp, model, catalog)
        runtime.start(before_jar); fixture = risk.setup(runtime, sources)
        legacy = risk.business_state(runtime, fixture)
        xml = b'<EInvoice><Header><Version>0.31</Version></Header><TaxSupervisionInfo><InvoiceNumber>000077</InvoiceNumber></TaxSupervisionInfo></EInvoice>'
        local = upload(runtime, xml, "XML")
        local_body = queue_body(runtime.call("GET", path(local) + "/input"))
        local_run = runtime.call("POST", path(local), local_body, expected=202)
        runtime.stop(); baseline = split.columns_snapshot(runtime, idp.h2, "before-upgrade")
        runtime.start(after_jar); runtime.stop(); upgraded = split.columns_snapshot(runtime, idp.h2, "after-upgrade")
        assert baseline == upgraded
        evidence["checks"].append({"name": "nonempty-upgrade", "tables": len(baseline["tables"]),
            "rows": sum(value["rows"] for value in baseline["tables"].values()), "allRowsAndColumnsUnchanged": True})
        runtime.start(after_jar)
        assert risk.business_state(runtime, fixture) == legacy
        invoice = upload(runtime, Path(cases[0]["source"]).read_bytes())
        finance = runtime.call("GET", "/invoices/" + invoice)
        options = runtime.call("GET", path(invoice) + "/input")
        assert options["transmission"] == "RENDERED_PAGES" and options["input"]["pageCount"] == 1
        body = queue_body(options)
        for user in ("admin", "bob"):
            runtime.call("GET", path(invoice) + "/input", user=user, expected=404)
        runtime.call("POST", path(invoice), {**body, "externalSendConfirmed": False}, expected=422)
        request_key = str(uuid4())
        queued = runtime.client.lose_response(path(invoice), body, request_key, 202, user="alice")
        runtime.stop(force=True)
        runtime.settings["agentflow.invoices.extraction-worker-enabled"] = True
        runtime.start(after_jar)
        assert runtime.call("POST", path(invoice), body, key=request_key, expected=202) == queued
        assert runtime.client.records[-1]["replayed"] == "true"
        extracted = result(runtime, invoice, queued); assert extracted["status"] == "COMPLETED", extracted
        assert result(runtime, local, local_run)["status"] == "COMPLETED"
        assert len(model.requests) == 1
        review = {"expectedRunVersion": 3, "action": "CONFIRM", "selected": [{"field": "INVOICE_NUMBER", "value": "0000456"}], "comment": "合成协议复核"}
        review_key = str(uuid4()); review_path = path(invoice) + "/" + queued["id"] + "/review"
        reviewed = runtime.client.lose_response(review_path, review, review_key, 200, user="alice")
        runtime.stop(force=True); runtime.start(after_jar)
        assert runtime.call("POST", review_path, review, key=review_key) == reviewed
        assert runtime.client.records[-1]["replayed"] == "true"
        assert runtime.call("GET", "/invoices/" + invoice) == finance
        confirmed = runtime.call("GET", path(invoice) + "/" + queued["id"])
        assert confirmed["status"] == "CONFIRMED" and confirmed["review"]["selected"] == review["selected"]
        assert runtime.call("GET", path(invoice))["total"] == 1
        assert len(model.requests) == 1
        ordered = upload(runtime, Path(cases[1]["source"]).read_bytes())
        ordered_options = runtime.call("GET", path(ordered) + "/input")
        assert ordered_options["input"]["pageCount"] == len(cases[1]["images"])
        ordered_run = runtime.call("POST", path(ordered), queue_body(ordered_options), expected=202)
        ordered_result = result(runtime, ordered, ordered_run); assert ordered_result["status"] == "COMPLETED"
        assert ordered_result["suggestion"]["proposals"][0]["evidence"][0]["page"] == len(cases[1]["images"])
        assert len(model.requests) == 2 and risk.business_state(runtime, fixture) == legacy
        evidence["checks"].append({"name": "all-pages-and-two-lost-receipts", "modelCalls": 2, "queueReplay": True,
            "reviewReplay": True, "oldXmlQueuedRunContinued": True, "invoiceFinanceUnchanged": True})
        runtime.stop(); snapshot = split.columns_snapshot(runtime, idp.h2, "before-restore")
        backup = directory / "backup"; backup.mkdir()
        for name in ("data", "attachments"):
            shutil.copytree(runtime.directory / name, backup / name)
        backup_catalog = copy_fonts(catalog, backup / "fonts")
        save(backup / "synthetic-peer-state.json", {"commands": sources.commands, "invoices": sources.invoices, "entity": sources.base.entity})
        save(backup / "manifest.json", {"jarSha256": digest(after_jar), "files": {str(p.relative_to(backup)): digest(p) for p in backup.rglob('*') if p.is_file()}})
        original_sha = digest(runtime.directory / "data/agentflow.mv.db")
        restored_catalog = copy_fonts(backup_catalog, directory / "restored-fonts")
        restored = runtime_for(args.java, directory / "restored", sources, idp, model, restored_catalog)
        for name in ("data", "attachments"):
            shutil.copytree(backup / name, restored.directory / name)
        restored.process = runtime.process
        assert split.columns_snapshot(restored, idp.h2, "restored-before-start") == snapshot
        restored.start(after_jar); restored.client.sessions = runtime.client.sessions; restored.client.identities = copy.deepcopy(runtime.client.identities)
        for item, source in ((invoice, Path(cases[0]["source"])), (ordered, Path(cases[1]["source"]))):
            assert restored.call("GET", "/invoices/" + item + "/content") == source.read_bytes()
            assert restored.call("GET", path(item) + "/input")["transmission"] == "RENDERED_PAGES"
        assert restored.call("POST", review_path, review, key=review_key) == reviewed
        assert restored.call("GET", path(invoice) + "/" + queued["id"]) == confirmed
        assert restored.call("GET", path(invoice))["total"] == 1
        assert risk.business_state(restored, fixture) == legacy and len(model.requests) == 2
        pending = risk.tasks(restored, fixture["reports"][0]["applicationId"])[0]
        restored.call("POST", "/tasks/" + pending["taskId"] + "/actions", {"action": "APPROVE", "expectedVersion": pending["version"], "comment": "还原后继续"}, user="manager")
        restored.stop(); assert digest(runtime.directory / "data/agentflow.mv.db") == original_sha
        assert not model.errors and not sources.errors and not sources.base.errors and not sources.model_calls
        evidence["checks"].append({"name": "independent-paired-restore", "tables": len(snapshot["tables"]),
            "rows": sum(value["rows"] for value in snapshot["tables"].values()),
            "originalDatabaseUnchanged": True, "originalTaskContinued": True, "fontFilesIncluded": True})
        evidence.update(status="PASSED", httpRecords=len(runtime.client.records)+len(restored.client.records), modelCalls=len(model.requests), boots=runtime.starts+restored.starts)
        save(directory / "all-http-records.json", runtime.client.records + restored.client.records)
    finally:
        for app in (restored, runtime):
            if app is not None:
                app.stop()
        if idp is not None:
            idp.close()
        model.close(); sources.close()
        evidence["ownedProcessesStopped"] = all(app is None or app.process is None or app.process.poll() is not None for app in (runtime, restored))
        save(directory / "evidence.json", evidence)
    print(json.dumps(evidence, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    for name in ("before", "after", "java", "font-catalog", "cases", "output"):
        parser.add_argument("--" + name, required=True)
    run(parser.parse_args())
