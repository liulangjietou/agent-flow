#!/usr/bin/env python3
"""固定包验证图形保存/发布、旧在审接续、原 XML 保留与独立恢复。"""

import argparse
import base64
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
from types import SimpleNamespace
from uuid import uuid4
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("diagram_runtime", ROOT / "scripts/check-expense-split-routing.py")
split = importlib.util.module_from_spec(spec); spec.loader.exec_module(split)
common = split.risk.common
save, digest = common.save, common.digest
NS = {"b": "http://www.omg.org/spec/BPMN/20100524/MODEL", "bpmndi": "http://www.omg.org/spec/BPMN/20100524/DI",
      "dc": "http://www.omg.org/spec/DD/20100524/DC", "di": "http://www.omg.org/spec/DD/20100524/DI"}


def runtime_for(java, directory):
    directory.mkdir()
    runtime = common.Runtime(java, directory, SimpleNamespace(server=SimpleNamespace(server_port=1)))
    runtime.settings.update({"agentflow.finance-gateway.enabled": False, "agentflow.assist.enabled": False,
                            "agentflow.expenses.precheck-worker-enabled": False, "agentflow.auth.oidc.enabled": False,
                            "agentflow.auth.session.jdbc-enabled": False})
    return runtime


def start(runtime, jar, login=True):
    runtime.start(jar, login=login)
    if login:
        runtime.tokens["manager"] = runtime.call("POST", "/auth/login", {"tenantId": "demo", "username": "manager", "password": "demo"}, user=None)["token"]


def schema():
    return {"schemaVersion": 2, "fields": [{"key": "amount", "label": "金额", "type": "NUMBER", "required": True}]}


def graph(branches=False):
    def node(identity, kind, x, y, actor=None):
        properties = {"x": str(x), "y": str(y)}
        if actor: properties["assigneeRule"] = "user:" + actor
        return {"id": identity, "type": kind, "name": identity, "properties": properties}
    nodes = [node("start", "START", 84.5, 90), node("review", "USER_TASK", 300, 140, "finance"), node("end", "END", 580, 90)]
    edges = [("first", "start", "review", "", False), ("last", "review", "end", "", False)]
    if branches:
        nodes = [nodes[0], node("gate", "EXCLUSIVE_GATEWAY", 230.25, 140), node("low", "USER_TASK", 356.25, 140, "finance"),
                 node("high", "USER_TASK", 440, 330.5, "manager"), node("end", "END", 750, 140)]
        edges = [("enter", "start", "gate", "", False), ("high-path", "gate", "high", "amount >= 100", False),
                 ("fallback", "gate", "low", "", True), ("low-end", "low", "end", "", False), ("high-end", "high", "end", "", False)]
    return {"conditionLanguageVersion": 2, "nodes": nodes,
            "edges": [dict(zip(("id", "source", "target", "condition", "defaultBranch"), values)) for values in edges]}


def create(runtime, key, value):
    return runtime.call("POST", "/process-definitions", {"key": key, "name": "图形固定包验收", "graph": value, "formSchema": schema()}, "admin")


def publish(runtime, draft, key=None):
    path = "/process-definitions/" + draft["id"] + "/publish?expectedRevision=" + str(draft["revision"])
    body = {"changeNote": "核对实际图形与原审批语义"}
    return runtime.call("POST", path, body, "admin", key=key), path, body


def submit(runtime, definition, amount, actor):
    application = runtime.call("POST", "/applications", {"businessNo": "DI-" + uuid4().hex, "title": "合成图形审批",
        "processKey": definition["key"], "definitionVersion": definition["version"], "payload": {"amount": str(amount)}}, expected=201)
    application = runtime.call("POST", "/applications/" + application["id"] + "/submit", {"expectedVersion": application["version"]})
    tasks = [item for item in runtime.call("GET", "/tasks", user=actor) if item["applicationId"] == application["id"]]
    assert len(tasks) == 1
    return {"application": application, "actor": actor, "taskId": tasks[0]["taskId"]}


def probe(runtime, h2, label):
    assert runtime.process.poll() is not None
    output = runtime.directory / (label + "-definitions.tsv")
    with (runtime.directory / (label + "-probe.log")).open("x") as log:
        subprocess.run([runtime.java, "-Djava.io.tmpdir=/fyoung/tmp", "--class-path", str(h2),
                        str(ROOT / "scripts/fixtures/DefinitionDiagramProbe.java"), str(runtime.directory / "data/agentflow"), str(output)],
                       stdout=log, stderr=subprocess.STDOUT, check=True, timeout=40)
    definitions, processes = {}, {}
    for line in output.read_text().splitlines():
        parts = line.split("\t"); raw = base64.b64decode(parts[-1]); sha = hashlib.sha256(raw).hexdigest()
        if parts[0] == "D": definitions[parts[1]] = {"key": parts[2], "version": int(parts[3]), "status": parts[4], "sha256": sha, "graph": json.loads(raw)}
        else:
            identity = parts[2] + ":" + parts[3]
            path = runtime.directory / (label + "-" + sha + ".bpmn20.xml"); path.write_bytes(raw)
            processes[identity] = {"id": parts[1], "key": parts[2], "version": int(parts[3]), "sha256": sha, "xml": str(path)}
    result = {"definitions": definitions, "processes": processes}; save(runtime.directory / (label + "-definitions.json"), result)
    return result


def verify_xml(process, expected, boxes):
    document = ET.parse(process["xml"]).getroot()
    shapes = {shape.attrib["bpmnElement"]: shape.find("dc:Bounds", NS) for shape in document.findall(".//bpmndi:BPMNShape", NS)}
    assert set(shapes) == set(boxes)
    for identity, box in boxes.items(): assert {key: float(value) for key, value in shapes[identity].attrib.items()} == box, identity
    edges = {edge.attrib["bpmnElement"]: edge for edge in document.findall(".//bpmndi:BPMNEdge", NS)}
    assert set(edges) == {edge["id"] for edge in expected["edges"]}
    for edge in expected["edges"]:
        actual = [{key: float(value) for key, value in point.attrib.items()} for point in edges[edge["id"]].findall("di:waypoint", NS)]
        assert actual == edge["waypoints"], (edge["id"], actual, edge["waypoints"])
    flows = document.findall("b:process/b:sequenceFlow", NS)
    assert [flow.attrib["id"] for flow in flows] == [edge["id"] for edge in expected["edges"]]
    gateway = document.find("b:process/b:exclusiveGateway", NS)
    assert gateway.attrib["default"] == "fallback"
    ids = [element.attrib["id"] for element in document.iter() if "id" in element.attrib]
    assert len(ids) == len(set(ids))


def run(args):
    directory = Path(args.output)
    assert directory.resolve().is_relative_to(Path('/fyoung/tmp').resolve()) and not directory.exists()
    directory.mkdir(); shutil.copy2(__file__, directory / Path(__file__).name)
    previous, current = Path(args.previous_jar).resolve(), Path(args.current_jar).resolve()
    with zipfile.ZipFile(current) as jar:
        name = next(name for name in jar.namelist() if name.startswith("BOOT-INF/lib/h2-") and name.endswith(".jar"))
        h2 = directory / Path(name).name; h2.write_bytes(jar.read(name))
    runtime = restored = None
    evidence = {"status": "RUNNING", "jarSha256": digest(current), "previousJarSha256": digest(previous),
                "database": "H2", "browserVerified": False, "postgresqlVerified": False, "checks": []}
    save(directory / "evidence.json", evidence)
    try:
        runtime = runtime_for(args.java, directory / "runtime"); start(runtime, previous)
        key = "diagram-" + uuid4().hex
        legacy, _, _ = publish(runtime, create(runtime, key, graph()))
        pending = submit(runtime, legacy, 150, "finance")
        editable = create(runtime, key, graph())
        runtime.stop(); before = split.columns_snapshot(runtime, h2, "before-upgrade"); original = probe(runtime, h2, "before-upgrade")
        old_process = original["processes"][key + ":1"]
        assert not ET.parse(old_process["xml"]).findall(".//bpmndi:BPMNShape", NS)
        start(runtime, current, login=False); runtime.stop()
        assert split.columns_snapshot(runtime, h2, "after-upgrade") == before
        after = probe(runtime, h2, "after-upgrade")
        assert after["definitions"] == original["definitions"]
        assert after["processes"][key + ":1"]["sha256"] == old_process["sha256"]
        evidence["checks"].append({"name": "nonempty-upgrade", "tables": len(before["tables"]), "legacyDraftAndPublishedGraphUnchanged": True})
        start(runtime, current)
        value = legacy
        for enabled in (False, True):
            value = runtime.call("POST", "/process-definitions/" + legacy["id"] + "/availability",
                {"expectedRevision": value["revision"], "startEnabled": enabled, "reason": "验证旧图保持"}, "admin")
        save(directory / "input-graph.json", graph(True))
        subprocess.run([args.node, str(ROOT / "agentflow-web/scripts/create-diagram-fixture.mjs"), str(directory), str(directory / "input-graph.json")], check=True)
        snapshot = json.loads((directory / "frontend-graph.json").read_text()); boxes = json.loads((directory / "frontend-boxes.json").read_text())
        assert next(edge for edge in snapshot["edges"] if edge["id"] == "fallback")["waypoints"] == [{"x": 356.25, "y": 172}, {"x": 356.25, "y": 172}]
        invalid = copy.deepcopy(snapshot); invalid["nodes"][0]["properties"]["x"] = "NaN"
        issues = runtime.call("POST", "/process-definitions/validate", {"graph": invalid, "formSchema": schema()}, "admin")
        assert "DIAGRAM_NODE_POSITION_INVALID:start" in issues["errors"]
        runtime.call("POST", "/process-definitions", {"key": key + "-invalid", "name": "无效布局", "graph": invalid, "formSchema": schema()}, "admin", 422)
        editable = runtime.call("PUT", "/process-definitions/" + editable["id"], {"expectedRevision": editable["revision"],
            "name": editable["name"], "graph": snapshot, "formSchema": schema()}, "admin")
        assert editable["graph"] == snapshot
        replay_key = str(uuid4()); published, path, body = publish(runtime, editable, replay_key)
        assert published["version"] == 2
        pending_new = [submit(runtime, published, 50, "finance"), submit(runtime, published, 150, "manager")]
        runtime.stop(force=True); written = probe(runtime, h2, "after-publish-kill")
        verify_xml(written["processes"][key + ":2"], snapshot, boxes)
        assert written["definitions"][legacy["id"]] == original["definitions"][legacy["id"]]
        assert written["processes"][key + ":1"]["sha256"] == old_process["sha256"]
        start(runtime, current)
        assert runtime.call("POST", path, body, "admin", key=replay_key) == published and runtime.records[-1]["replayed"] == "true"
        applications = []
        for item in [pending, *pending_new]:
            application = runtime.call("GET", "/applications/" + item["application"]["id"])
            tasks = [task for task in runtime.call("GET", "/tasks", user=item["actor"]) if task["applicationId"] == application["id"]]
            assert [task["taskId"] for task in tasks] == [item["taskId"]]
            runtime.call("POST", "/tasks/" + item["taskId"] + "/actions", {"expectedVersion": application["version"], "action": "APPROVE", "comment": "核对原轮次"}, item["actor"])
            application = runtime.call("GET", "/applications/" + application["id"]); assert application["status"] == "APPROVED"
            applications.append(application)
        definitions = [runtime.call("GET", "/process-definitions/" + identity, user="admin") for identity in (legacy["id"], published["id"])]
        runtime.stop(); backup = split.columns_snapshot(runtime, h2, "before-restore")
        evidence["checks"].append({"name": "publish-kill-replay-and-original-approval", "preservedOldXml": True, "sameOriginalTasks": 3,
                                   "exactShapes": len(boxes), "exactEdges": len(snapshot["edges"]), "retainedSequenceOrder": True})
        restored = runtime_for(args.java, directory / "restored")
        for folder in ("data", "attachments"): shutil.copytree(runtime.directory / folder, restored.directory / folder)
        start(restored, current, login=False); restored.stop()
        assert split.columns_snapshot(restored, h2, "after-restore") == backup
        recovered = probe(restored, h2, "after-restore"); verify_xml(recovered["processes"][key + ":2"], snapshot, boxes)
        assert recovered["processes"][key + ":1"]["sha256"] == old_process["sha256"]
        start(restored, current)
        for value in definitions: assert restored.call("GET", "/process-definitions/" + value["id"], user="admin") == value
        for value in applications: assert restored.call("GET", "/applications/" + value["id"]) == value
        evidence["checks"].append({"name": "independent-restore", "tables": len(backup["tables"]), "sameAuthorizedDetails": 5})
        evidence.update(status="PASS", boots=runtime.starts + restored.starts, businessHttpRequests=len(runtime.records) + len(restored.records))
        save(directory / "evidence.json", evidence); print(json.dumps(evidence, ensure_ascii=False), flush=True)
    except Exception as error:
        evidence.update(status="FAILED", failure=repr(error)); save(directory / "evidence.json", evidence); raise
    finally:
        if restored: restored.stop()
        if runtime: runtime.stop()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("java", "node", "previous-jar", "current-jar", "output"): parser.add_argument("--" + name, required=True)
    run(parser.parse_args())
