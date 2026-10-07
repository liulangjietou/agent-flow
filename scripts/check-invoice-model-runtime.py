#!/usr/bin/env python3
"""固定包验证五种原件、模型失败、真实租约重启与权限/来源/目的地变化。"""

import argparse
import base64
import datetime
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import threading
import time
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("ofd_runtime", ROOT / "scripts/check-invoice-ofd-extraction.py")
ofd = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ofd)
risk, save, digest, wait_for = ofd.risk, ofd.save, ofd.digest, ofd.wait_for
MODEL_TIMEOUT = 4


def instant(value):
    """Python 3.10 只接受毫秒/微秒精度；保留时区并截取 Java Instant 的纳秒尾数。"""
    normalized = re.sub(r"\.(\d+)", lambda match: "." + match.group(1)[:6].ljust(6, "0"), value)
    return datetime.datetime.fromisoformat(normalized.replace("Z", "+00:00"))


class Model:
    """逐份核对来源；可由验收线程控制回执时机，所有候选均为合成协议值。"""

    def __init__(self, directory, cases):
        self.directory, self.requests, self.errors, self.behaviors = directory, [], [], {}
        self.lock = threading.Lock()
        expected = {digest(Path(case["file"])): case for case in cases}
        model = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                behavior = None
                try:
                    length = int(self.headers["Content-Length"])
                    assert 0 < length < 32 * 1024 * 1024
                    body = json.loads(self.rfile.read(length))
                    assert body["stream"] is False and body["store"] is False
                    parts = body["messages"][1]["content"]
                    source = json.loads(parts[0]["text"])["source"]
                    case = expected[source["originalDigest"]]
                    assert source["format"] == case["format"] and source["pageCount"] == case["pages"]
                    behavior = model.behaviors.get(source["originalId"])
                    assert behavior is not None
                    raw = Path(case["file"]).read_bytes()
                    if case["format"] in ("PNG", "JPEG"):
                        assert len(parts) == 2 and parts[1]["type"] == "image_url"
                        value = parts[1]["image_url"]; assert value["detail"] == "high"
                        assert value["url"].startswith("data:image/" + case["format"].lower() + ";base64,")
                        assert base64.b64decode(value["url"].split(",", 1)[1], validate=True) == raw
                    elif case["format"] == "PDF":
                        assert len(parts) == 2 and parts[1]["type"] == "file"
                        value = parts[1]["file"]; assert value["filename"] == "invoice.pdf"
                        assert value["file_data"].startswith("data:application/pdf;base64,")
                        assert base64.b64decode(value["file_data"].split(",", 1)[1], validate=True) == raw
                    elif case["format"] == "XML":
                        assert not case.get("local") and len(parts) == 2
                        assert parts[1] == {"type": "text", "text": raw.decode()}
                    else:
                        assert len(parts) == 1 + source["pageCount"] * 2
                        for index, image in enumerate(case["images"], 1):
                            assert parts[index * 2 - 1] == {"type": "text", "text": f"OFD page {index} of {source['pageCount']}"}
                            value = parts[index * 2]["image_url"]
                            assert value["detail"] == "high" and value["url"].startswith("data:image/png;base64,")
                            assert hashlib.sha256(base64.b64decode(value["url"].split(",", 1)[1], validate=True)).hexdigest() == image["sha256"]
                    assert "private-source" not in json.dumps(body)
                    record = {"source": source, "request": body, "receivedAt": risk.instant(), "mode": behavior["mode"]}
                    with model.lock:
                        model.requests.append(record); model.persist()
                    behavior["received"].set()
                    assert behavior["release"].wait(150), "Controlled response was never released"
                    page = source["pageCount"] + (1 if behavior["mode"] == "BAD_PAGE" else 0)
                    suggestion = {"proposals": [] if behavior["mode"] == "EMPTY" else [{"field": "INVOICE_NUMBER", "value": "0000123", "confidence": "LOW",
                        "evidence": [{"originalId": source["originalId"], "originalDigest": source["originalDigest"], "page": page, "quote": "0000123"}]}]}
                    result = {"model": "synthetic-invoice-v01", "choices": [{"finish_reason": "stop", "message": {
                        "role": "assistant", "content": json.dumps(suggestion)}}]}
                    data = json.dumps(result).encode()
                    try:
                        self.send_response(200); self.send_header("Content-Type", "application/json")
                        self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)
                        record["responseWritten"] = True
                    except (BrokenPipeError, ConnectionResetError):
                        record["responseWritten"] = False
                    with model.lock:
                        record["responseAttemptedAt"] = risk.instant(); model.persist()
                except Exception as error:
                    with model.lock:
                        model.errors.append(repr(error)); save(directory / "model-errors.json", model.errors)
                    try:
                        self.send_error(500)
                    except (BrokenPipeError, ConnectionResetError):
                        pass
                finally:
                    if behavior is not None:
                        behavior["finished"].set()

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True); self.thread.start()

    def persist(self):
        save(self.directory / "model-requests.json", self.requests)

    def arm(self, original, mode="NORMAL"):
        value = {"mode": mode, "received": threading.Event(), "release": threading.Event(), "finished": threading.Event()}
        if mode != "HELD":
            value["release"].set()
        self.behaviors[original] = value
        return value

    def close(self):
        for value in self.behaviors.values():
            value["release"].set()
        self.server.shutdown(); self.server.server_close(); self.thread.join(timeout=5)


def probe(runtime, directory, label):
    """仅在停服后读取本次独立库，完整保留状态、租约与转换历史。"""
    assert runtime.process.poll() is not None
    output = runtime.directory / (label + "-runs.json")
    classpath = str(directory / "classes") + ":" + str(directory / "libraries/*")
    source = ROOT / "scripts/fixtures/InvoiceExtractionRuntimeProbe.java"
    with (runtime.directory / (label + "-probe.log")).open("x") as log:
        subprocess.run([runtime.java, "-Djava.io.tmpdir=/fyoung/tmp", "-cp", classpath, str(source),
                        str(runtime.directory / "data/agentflow"), str(output)], stdout=log, stderr=subprocess.STDOUT, check=True, timeout=30)
    return json.loads(output.read_text())


def person(runtime, active):
    value = next(p for p in runtime.call("GET", "/organization/people?limit=100", user="admin")["items"] if p["subject"] == "alice")
    return runtime.call("PUT", "/organization/people/" + value["id"], {"displayName": value["displayName"],
        "active": active, "approvalEligible": value["approvalEligible"], "expectedRevision": value["revision"]}, user="admin")


def terminal(runtime, invoice, run):
    return wait_for(lambda: runtime.call("GET", ofd.path(invoice) + "/" + run["id"]),
                    lambda value: value["status"] not in ("QUEUED", "RUNNING"), 90)


def run(args):
    directory = Path(args.output)
    assert str(directory).startswith("/fyoung/tmp/") and directory.resolve().is_relative_to(Path("/fyoung/tmp").resolve()) and not directory.exists()
    directory.mkdir()
    jar, catalog = Path(args.jar).resolve(), Path(args.font_catalog).resolve()
    cases = json.loads(Path(args.cases).read_text())
    for case in cases:
        assert digest(Path(case["file"])) == case["sha256"]
    evidence = {"status": "RUNNING", "jarSha256": digest(jar), "database": "H2", "checks": [],
                "browserVerified": False, "postgresqlVerified": False, "realEnterpriseModelVerified": False}
    save(directory / "evidence.json", evidence)
    sources = risk.Sources(directory); model = Model(directory, cases)
    idp = runtime = None
    try:
        idp = risk.IdentityProvider(args.java, directory, jar)
        runtime = ofd.runtime_for(args.java, directory / "runtime", sources, idp, model, catalog)
        runtime.settings.update({"agentflow.assist.model": "synthetic-invoice-v01", "agentflow.assist.timeout-seconds": MODEL_TIMEOUT})
        runtime.start(jar); fixture = risk.setup(runtime, sources); legacy = risk.business_state(runtime, fixture)
        invoices, runs, options, bodies, keys, financial = {}, {}, {}, {}, {}, {}
        for case in cases:
            name = case["name"]
            invoice = ofd.upload(runtime, Path(case["file"]).read_bytes(), case["format"]); invoices[name] = invoice
            financial[name] = runtime.call("GET", "/invoices/" + invoice)
            option = runtime.call("GET", ofd.path(invoice) + "/input"); options[name] = option
            assert option["input"]["pageCount"] == case["pages"]
            assert option["method"] == ("STRUCTURED_XML" if case.get("local") else "MODEL")
            expected_transmission = "NONE" if case.get("local") else "XML_TEXT" if case["format"] == "XML" else "RENDERED_PAGES" if case["format"] == "OFD" else "ORIGINAL_BYTES"
            assert option["transmission"] == expected_transmission
            body = ofd.queue_body(option); bodies[name] = body; key = str(uuid4()); keys[name] = key
            if not case.get("local"):
                runtime.call("POST", ofd.path(invoice), {**body, "externalSendConfirmed": False}, expected=422)
                model.arm(option["input"]["originalId"])
            runs[name] = runtime.call("POST", ofd.path(invoice), body, key=key, expected=202)
        assert not model.requests
        runtime.stop(force=True); runtime.settings["agentflow.invoices.extraction-worker-enabled"] = True; runtime.start(jar)
        completed = {}
        for name, invoice in invoices.items():
            assert runtime.call("POST", ofd.path(invoice), bodies[name], key=keys[name], expected=202) == runs[name]
            assert runtime.client.records[-1]["replayed"] == "true"
            result = terminal(runtime, invoice, runs[name]); assert result["status"] == "COMPLETED", result
            completed[name] = result
            assert runtime.call("GET", ofd.path(invoice))["total"] == 1
        assert len(model.requests) == 5
        primary = invoices["PNG"]; primary_run = runs["PNG"]
        for actor in ("bob", "admin"):
            runtime.call("GET", ofd.path(primary) + "/input", user=actor, expected=404)
            runtime.call("GET", ofd.path(primary) + "/" + primary_run["id"], user=actor, expected=404)
            runtime.call("POST", ofd.path(primary) + "/" + primary_run["id"] + "/review",
                         {"expectedRunVersion": 3, "action": "DISMISS"}, user=actor, expected=404)
        review = {"expectedRunVersion": 3, "action": "CONFIRM", "selected": [{"field": "INVOICE_NUMBER", "value": "0000456"}], "comment": "合成模型人工复核"}
        review_key = str(uuid4()); review_path = ofd.path(primary) + "/" + primary_run["id"] + "/review"
        reviewed = runtime.call("POST", review_path, review, key=review_key)
        assert reviewed["status"] == "CONFIRMED"
        evidence["checks"].append({"name": "all-formats-and-owner-boundaries", "modelFormats": ["PNG", "JPEG", "PDF", "XML", "OFD"],
                                   "structuredXmlModelCalls": 0, "queuedRestartOriginalKeys": 6, "deniedForeignReadsAndReviews": 6})
        save(directory / "evidence.json", evidence)

        def again(name, mode):
            option = runtime.call("GET", ofd.path(invoices[name]) + "/input")
            behavior = model.arm(option["input"]["originalId"], mode)
            body = ofd.queue_body(option); key = str(uuid4())
            receipt = runtime.call("POST", ofd.path(invoices[name]), body, key=key, expected=202)
            return receipt, behavior, key, body

        bad, _, _, _ = again("PDF", "BAD_PAGE")
        assert terminal(runtime, invoices["PDF"], bad)["failure"] == "INVALID_RESULT"
        empty, _, _, _ = again("JPEG", "EMPTY")
        empty_result = terminal(runtime, invoices["JPEG"], empty)
        assert empty_result["status"] == "COMPLETED" and empty_result["suggestion"]["proposals"] == []
        runtime.call("POST", ofd.path(invoices["JPEG"]) + "/" + empty["id"] + "/review", {"expectedRunVersion": 3, "action": "DISMISS"})

        delayed, gate, _, _ = again("XML", "HELD")
        assert gate["received"].wait(10)
        failed = terminal(runtime, invoices["XML"], delayed); assert failed["failure"] == "EXECUTION_TIMEOUT"
        gate["release"].set(); assert gate["finished"].wait(10)
        assert runtime.call("GET", ofd.path(invoices["XML"]) + "/" + delayed["id"]) == failed
        before_crash = len(model.requests)
        interrupted, gate, interrupted_key, interrupted_body = again("XML", "HELD")
        assert gate["received"].wait(10)
        running = runtime.call("GET", ofd.path(invoices["XML"]) + "/" + interrupted["id"])
        assert running["status"] == "RUNNING", running
        runtime.stop(force=True)
        interrupted_state = probe(runtime, directory, "interrupted")[interrupted["id"]]
        assert interrupted_state["status"] == "RUNNING" and interrupted_state["leaseUntil"]
        runtime.start(jar)
        assert runtime.call("POST", ofd.path(invoices["XML"]), interrupted_body, key=interrupted_key, expected=202) == interrupted
        assert runtime.client.records[-1]["replayed"] == "true"
        gate["release"].set(); assert gate["finished"].wait(10)
        recovered = terminal(runtime, invoices["XML"], interrupted)
        assert recovered["failure"] == "EXECUTION_TIMEOUT" and len(model.requests) == before_crash + 1
        elapsed = (instant(recovered["completedAt"]) - instant(recovered["startedAt"])).total_seconds()
        assert elapsed >= 60 + MODEL_TIMEOUT, elapsed
        evidence["checks"].append({"name": "invalid-empty-timeout-and-running-crash", "lateReplyDidNotOverwrite": True,
            "expiredOriginalLeaseSeconds": elapsed, "interruptedModelRequests": 1, "originalQueueReceiptReplayed": True})
        save(directory / "evidence.json", evidence)

        runtime.stop(); runtime.settings["agentflow.invoices.extraction-worker-enabled"] = False; runtime.start(jar)
        altered, _, _, _ = again("PNG", "NORMAL")
        source_file = runtime.directory / "attachments" / (options["PNG"]["input"]["originalId"] + ".bin")
        original_bytes = source_file.read_bytes(); source_file.write_bytes(original_bytes[:-1] + bytes([original_bytes[-1] ^ 1]))
        calls = len(model.requests)
        runtime.stop(); runtime.settings["agentflow.invoices.extraction-worker-enabled"] = True; runtime.start(jar)
        assert terminal(runtime, primary, altered)["failure"] == "INPUT_UNAVAILABLE"
        assert len(model.requests) == calls
        source_file.write_bytes(original_bytes)

        runtime.stop(); runtime.settings["agentflow.invoices.extraction-worker-enabled"] = False; runtime.start(jar)
        revoked, _, _, _ = again("XML", "NORMAL"); person(runtime, False)
        runtime.call("GET", ofd.path(invoices["XML"]) + "/" + revoked["id"], expected=403)
        runtime.stop(); runtime.settings["agentflow.invoices.extraction-worker-enabled"] = True; runtime.start(jar)
        time.sleep(1)
        runtime.stop(); revoked_state = probe(runtime, directory, "revoked")[revoked["id"]]
        assert revoked_state["status"] == "FAILED" and revoked_state["state"]["failure"] == "INPUT_UNAVAILABLE"
        assert len(model.requests) == calls
        runtime.settings["agentflow.invoices.extraction-worker-enabled"] = False; runtime.start(jar); person(runtime, True)
        assert terminal(runtime, invoices["XML"], revoked)["failure"] == "INPUT_UNAVAILABLE"
        changed, _, _, _ = again("XML", "NORMAL")
        runtime.stop(); runtime.settings.update({"agentflow.assist.model": "changed-destination-model", "agentflow.invoices.extraction-worker-enabled": True}); runtime.start(jar)
        assert terminal(runtime, invoices["XML"], changed)["failure"] == "MODEL_UNAVAILABLE"
        assert len(model.requests) == calls
        evidence["checks"].append({"name": "source-owner-and-target-changes", "additionalModelRequests": 0,
            "changedOriginalFailed": True, "revokedOwnerFailed": True, "changedTargetFailed": True})
        save(directory / "evidence.json", evidence)

        runtime.stop(); final_before = probe(runtime, directory, "before-final-restart")
        runtime.settings["agentflow.assist.model"] = "synthetic-invoice-v01"; runtime.start(jar)
        assert runtime.call("POST", review_path, review, key=review_key) == reviewed
        assert runtime.client.records[-1]["replayed"] == "true"
        for name, invoice in invoices.items():
            assert runtime.call("GET", "/invoices/" + invoice) == financial[name]
            assert runtime.call("GET", "/invoices/" + invoice + "/content") == Path(next(c for c in cases if c["name"] == name)["file"]).read_bytes()
        assert risk.business_state(runtime, fixture) == legacy and len(model.requests) == calls
        runtime.stop(); final_after = probe(runtime, directory, "after-final-restart"); assert final_before == final_after
        for run in final_after.values():
            states = [item["status"] for item in run["transitions"]]
            assert states[:2] == ["QUEUED", "RUNNING"] and len(states) in (3, 4)
            assert [item["version"] for item in run["transitions"]] == list(range(1, len(states) + 1))
            assert run["leaseUntil"] is None
        assert not model.errors and not sources.errors and not sources.base.errors and not sources.model_calls
        evidence["checks"].append({"name": "final-restart", "runs": len(final_after), "allStatesAndTransitionsUnchanged": True,
                                   "originalReviewReceiptReplayed": True, "financeAndOriginalTasksUnchanged": True})
        evidence.update(status="PASSED", boots=runtime.starts, httpRecords=len(runtime.client.records), modelCalls=len(model.requests))
        save(directory / "all-http-records.json", runtime.client.records)
    finally:
        if runtime is not None:
            runtime.stop()
        if idp is not None:
            idp.close()
        model.close(); sources.close()
        evidence["ownedProcessesStopped"] = runtime is None or runtime.process is None or runtime.process.poll() is not None
        save(directory / "evidence.json", evidence)
    print(json.dumps(evidence, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    for name in ("jar", "java", "font-catalog", "cases", "output"):
        parser.add_argument("--" + name, required=True)
    run(parser.parse_args())
