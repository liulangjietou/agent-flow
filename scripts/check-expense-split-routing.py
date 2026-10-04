#!/usr/bin/env python3
"""独立固定包验收：旧列保留、并发跨单路由、原轮次授权、强退接续和配套恢复。"""

import argparse
from concurrent.futures import ThreadPoolExecutor
import copy
import hashlib
from http.cookiejar import CookieJar
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import threading
from types import SimpleNamespace
from urllib.request import build_opener, HTTPCookieProcessor, ProxyHandler
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("risk_runtime", ROOT / "scripts/check-expense-risk.py")
risk = importlib.util.module_from_spec(spec)
spec.loader.exec_module(risk)
save, digest, instant, wait_for = risk.save, risk.digest, risk.instant, risk.wait_for
NEW_TABLES = {"EXPENSE_SPLIT_ROUTING", "EXPENSE_SPLIT_ROUTING_SOURCE"}
NEW_COLUMNS = {"CURRENT_ROUND_NO", "CURRENT_SUBMITTED_AT", "CURRENT_LEGAL_ENTITY_ID", "CURRENT_BASE_CURRENCY"}


def columns_snapshot(runtime, h2, label, baseline=None):
    """停服后按原列顺序核对所有旧值；新增投影不能掩盖旧正文被修改。"""
    assert runtime.process.poll() is not None
    source = runtime.directory / "SplitSnapshot.java"
    source.write_text(r'''import java.sql.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
/**
 * 离线保留列核对，元数据和全量行摘要分别记录。
 * @author owlzhangfq@gmail.com
 */
class SplitSnapshot {
    static String quote(String name) { return "\"" + name.replace("\"", "\"\"") + "\""; }
    static String hash(List<String> rows) throws Exception {
        Collections.sort(rows); var digest = MessageDigest.getInstance("SHA-256");
        for (var row : rows) digest.update((row + "\n").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }
    public static void main(String[] args) throws Exception {
        var prior = new HashMap<String,List<String>>();
        if (args.length == 3) for (var line : Files.readAllLines(Path.of(args[2]))) {
            var parts = line.split("\t", -1); prior.put(parts[0], List.of(parts[1].split(",")));
        }
        try (var connection = DriverManager.getConnection("jdbc:h2:file:" + args[0]
                + ";IFEXISTS=TRUE;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0;REUSE_SPACE=FALSE", "sa", "")) {
            var columns = new ArrayList<String>(); var summaries = new ArrayList<String>();
            var metadata = new ArrayList<String>();
            try (var names = connection.getMetaData().getTables(null, "PUBLIC", "%", new String[]{"BASE TABLE"})) {
                while (names.next()) {
                    String table = names.getString("TABLE_NAME"); var rows = new ArrayList<String>(); var oldRows = new ArrayList<String>();
                    try (var statement = connection.createStatement(); var data = statement.executeQuery("SELECT * FROM " + quote(table))) {
                        var info = data.getMetaData(); var current = new ArrayList<String>();
                        for (int i = 1; i <= info.getColumnCount(); i++) {
                            String column = info.getColumnName(i);
                            if (!column.matches("[A-Za-z0-9_$]+")) throw new IllegalStateException("Unsupported column identity");
                            current.add(column);
                            metadata.add(table + "\t" + column + "\t" + info.getColumnTypeName(i) + "\t" + info.getPrecision(i) + "\t" + info.getScale(i) + "\t" + info.isNullable(i));
                        }
                        var selected = prior.getOrDefault(table, current);
                        if (!current.containsAll(selected)) throw new IllegalStateException("Original column removed: " + table);
                        columns.add(table + "\t" + String.join(",", current));
                        while (data.next()) {
                            var values = new HashMap<String,String>();
                            for (int i = 1; i <= info.getColumnCount(); i++) {
                                int type = info.getColumnType(i);
                                byte[] bytes = type == Types.BINARY || type == Types.VARBINARY || type == Types.LONGVARBINARY || type == Types.BLOB
                                    ? data.getBytes(i) : data.getString(i) == null ? null : data.getString(i).getBytes(StandardCharsets.UTF_8);
                                values.put(current.get(i - 1), bytes == null ? "-" : Base64.getEncoder().encodeToString(bytes));
                            }
                            rows.add(String.join(";", current.stream().map(values::get).toList()));
                            oldRows.add(String.join(";", selected.stream().map(values::get).toList()));
                        }
                    }
                    summaries.add(table + "\t" + rows.size() + "\t" + hash(rows) + "\t" + hash(oldRows));
                }
            }
            Collections.sort(columns); Collections.sort(summaries); Collections.sort(metadata);
            Files.write(Path.of(args[1] + "-columns.tsv"), columns);
            Files.write(Path.of(args[1] + "-tables.tsv"), summaries);
            Files.write(Path.of(args[1] + "-metadata.tsv"), metadata);
            try (var statement = connection.createStatement(); var data = statement.executeQuery("SCRIPT NODATA NOPASSWORDS")) {
                var schema = new ArrayList<String>(); while (data.next()) schema.add(data.getString(1));
                Files.write(Path.of(args[1] + "-schema.sql"), schema);
            }
        }
    }
}
''')
    prefix = runtime.directory / label
    command = [runtime.java, "-Djava.io.tmpdir=/fyoung/tmp", "--class-path", str(h2), str(source),
               str(runtime.directory / "data/agentflow"), str(prefix)]
    if baseline:
        command.append(str(baseline))
    with (runtime.directory / (label + "-snapshot.log")).open("x") as log:
        subprocess.run(command, check=True, stdout=log, stderr=subprocess.STDOUT, timeout=60)
    columns = dict(line.split("\t") for line in Path(str(prefix) + "-columns.tsv").read_text().splitlines())
    metadata = {}
    for line in Path(str(prefix) + "-metadata.tsv").read_text().splitlines():
        table, column, *values = line.split("\t")
        metadata.setdefault(table, {})[column] = values
    tables = {}
    for line in Path(str(prefix) + "-tables.tsv").read_text().splitlines():
        table, count, full, retained = line.split("\t")
        tables[table] = {"rows": int(count), "sha256": full, "originalColumnsSha256": retained}
    result = {"tables": tables, "columns": {key: value.split(",") for key, value in columns.items()}, "metadata": metadata}
    save(Path(str(prefix) + ".json"), result)
    return result


def definition(runtime, fixture, mode, key=None, threshold="5000", currency="CNY", hidden=False):
    people = fixture["people"]
    properties = {} if mode == "UNCONFIGURED" else {"expenseSplitRisk": mode}
    if mode == "ENABLED":
        properties.update(expenseSplitWindowDays="7", expenseSplitThreshold=threshold, expenseSplitCurrency=currency)
    names = ["start", "business", "gate", "higher", "receipt", "finance", "financialGate", "recheck", "end"]
    nodes = []
    for name in names:
        kind = "START" if name == "start" else "END" if name == "end" else "EXCLUSIVE_GATEWAY" if name in ("gate", "financialGate") else "USER_TASK"
        props = copy.deepcopy(properties) if name == "start" else {}
        if kind == "USER_TASK":
            props["assigneeRule"] = "role:ORG_PERSON_" + people["manager" if name in ("business", "higher") else "finance"]
            if name in ("receipt", "finance", "recheck"):
                props["expenseStage"] = {"receipt": "RECEIPT", "finance": "FINANCE_REVIEW", "recheck": "FINANCE_RECHECK"}[name]
        if name == "gate" and mode != "UNCONFIGURED":
            props["expenseSplitRouting"] = "AGGREGATE_AMOUNT"
        nodes.append({"id": name, "name": name, "type": kind, "properties": props})
    edges = [("a", "start", "business", "", False), ("b", "business", "gate", "", False),
             ("high", "gate", "higher", "amount > " + threshold, False), ("low", "gate", "receipt", "", True),
             ("c", "higher", "receipt", "", False), ("d", "receipt", "finance", "", False),
             ("e", "finance", "financialGate", "", False), ("review", "financialGate", "recheck", "amount > 3500", False),
             ("skip", "financialGate", "end", "", True), ("done", "recheck", "end", "", False)]
    graph = {"nodes": nodes, "edges": [dict(zip(("id", "source", "target", "condition", "defaultBranch"), edge)) for edge in edges]}
    fields = [{"key": key, "label": key, "type": kind, "required": True} for key, kind in
              (("amount", "NUMBER"), ("currency", "TEXT"), ("overPolicy", "BOOLEAN"))]
    fields.insert(0, {"key": "expenseDetails", "label": "费用明细", "type": "TEXT", "required": True, "sensitive": True,
                     "nodeAccess": {name: "HIDDEN" if hidden and name in ("business", "higher") else "READ_ONLY"
                                    for name in ("business", "higher", "receipt", "finance", "recheck")}})
    created = runtime.call("POST", "/process-definitions", {"key": key or "split-" + uuid4().hex, "name": "合成跨单验收",
                           "graph": graph, "formSchema": {"schemaVersion": 2, "fields": fields}}, "admin")
    return runtime.call("POST", "/process-definitions/" + created["id"] + "/publish?expectedRevision=" + str(created["revision"]),
                        {"changeNote": "固定包本地验收"}, "admin")


def draft(runtime, fixture, published, amount="4000"):
    money = {"value": amount, "currency": "CNY"}
    content = {"legalEntityId": fixture["entityId"], "type": "DAILY", "title": "合成跨单报销", "advanceOffsets": [],
               "lines": [{"lineNo": 1, "categoryCode": "OFFICE", "incurredOn": instant()[:10], "cityCode": "SH",
                          "quantity": 1, "unit": "ITEM", "claimedGross": money, "claimedTax": {"value": "0", "currency": "CNY"},
                          "invoiceIds": [], "allocations": [{"costCenter": "IT", "amount": money}], "description": "合成费用"}]}
    return runtime.call("POST", "/expense-reports", {"businessNo": "SPLIT-" + uuid4().hex, "processKey": published["key"],
                        "definitionVersion": published["version"], "content": content}, expected=201)


def detail(runtime, report):
    return runtime.call("GET", "/expense-reports/" + report["id"])


def split(runtime, report, round_no=1, actor="alice", expected=200):
    return runtime.call("GET", "/expense-reports/" + report["id"] + "/split-routing?roundNo=" + str(round_no), user=actor, expected=expected)


def prepare(runtime, fixture, report):
    checked = risk.common.precheck(runtime, {"report": report, "appointmentId": fixture["appointments"]["alice"]})
    assert checked["job"]["status"] == "READY", checked
    current = detail(runtime, report)
    return {"applicationVersion": current["applicationVersion"], "financialVersion": current["financialVersion"], "precheckId": checked["job"]["id"]}


def submit(runtime, fixture, report):
    body = prepare(runtime, fixture, report)
    runtime.call("POST", "/expense-reports/" + report["id"] + "/submit", body)
    budget(runtime, report)
    return detail(runtime, report)


def budget(runtime, report, timeout=30):
    return wait_for(lambda: runtime.call("GET", "/expense-reports/" + report["id"] + "/workflow"),
                    lambda value: value["budget"]["confirmedCurrent"], timeout)


def task(runtime, report, actor):
    matches = [value for value in runtime.call("GET", "/tasks", user=actor) if value["applicationId"] == report["applicationId"]]
    assert len(matches) == 1, (report["id"], actor, matches)
    return matches[0]


def action(runtime, report, actor="manager", choice="APPROVE", key=None):
    current = task(runtime, report, actor)
    body = {"action": choice, "expectedVersion": current["version"], "comment": "合成运行验收"}
    path = "/tasks/" + current["taskId"] + "/actions"
    result = runtime.call("POST", path, body, actor, key=key)
    return {"path": path, "body": body, "result": result, "key": key}


def concurrent_submit(runtime, idp, fixture, reports):
    """两个独立 HTTP 客户端共享原登录身份；记录文件分开写，避免测试自身串行化提交。"""
    bodies = [prepare(runtime, fixture, report) for report in reports]
    barrier = threading.Barrier(2)
    clients = []
    for index in range(2):
        directory = runtime.directory / ("concurrent-" + str(index)); directory.mkdir()
        clone = risk.Client(SimpleNamespace(base=runtime.base, starts=runtime.starts, directory=directory), idp)
        cookies = CookieJar()
        for cookie in runtime.client.sessions["alice"][1]:
            cookies.set_cookie(copy.copy(cookie))
        clone.sessions["alice"] = (build_opener(ProxyHandler({}), HTTPCookieProcessor(cookies), risk.NoRedirect()), cookies)
        clients.append(clone)
    keys = [str(uuid4()), str(uuid4())]

    def send(index):
        barrier.wait(timeout=10)
        return clients[index].call("POST", "/expense-reports/" + reports[index]["id"] + "/submit", bodies[index], key=keys[index])

    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(send, index) for index in range(2)]
        receipts = [future.result(timeout=45) for future in futures]
    for client in clients:
        runtime.client.records.extend(client.records)
    save(runtime.directory / "http-records.json", runtime.client.records)
    for report in reports:
        budget(runtime, report)
    return [{"path": "/expense-reports/" + report["id"] + "/submit", "body": body, "key": key, "result": receipt}
            for report, body, key, receipt in zip(reports, bodies, keys, receipts)]


def run(args):
    directory = Path(args.output)
    assert str(directory).startswith("/fyoung/tmp/") and not directory.exists(), "Use a fresh owned temporary directory"
    directory.mkdir(parents=True)
    before_jar, after_jar = Path(args.before).resolve(), Path(args.after).resolve()
    sources = risk.Sources(directory)
    idp, runtime, restored = None, None, None
    hold = {"enabled": False, "entered": threading.Event(), "release": threading.Event()}
    original_finance = sources.finance

    def finance(operation, request):
        result = original_finance(operation, request)
        if operation == "budget-command" and hold["enabled"]:
            save(directory / "held-peer-receipts.json", sources.commands)
            hold["entered"].set()
            assert hold["release"].wait(45), "Held synthetic receipt was not released"
        return result

    sources.finance = finance
    evidence = {"status": "RUNNING", "startedAt": instant(), "beforeSha256": digest(before_jar), "afterSha256": digest(after_jar),
                "database": "H2", "identity": "loopback OIDC with JDBC sessions", "browserVerified": False, "postgresqlVerified": False, "checks": []}
    save(directory / "evidence.json", evidence)
    for name in ("scripts/check-expense-split-routing.py", "scripts/check-expense-risk.py", "scripts/check-precheck-explanation.py",
                 "scripts/fixtures/ExpenseRiskOidcFixture.java", "agentflow-server/src/test/java/io/agentflow/auth/OidcTestProvider.java"):
        target = directory / "harness-source" / name; target.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(ROOT / name, target)
    try:
        idp = risk.IdentityProvider(args.java, directory, after_jar)
        runtime = risk.Runtime(args.java, directory / "original", sources, idp)
        runtime.start(before_jar)
        fixture = risk.setup(runtime, sources); fixture["entityId"] = sources.base.entity
        legacy_draft = draft(runtime, fixture, {"key": "risk-runtime", "version": 1}, "1")
        legacy_reports = copy.deepcopy(fixture["reports"])
        save(directory / "fixture.json", fixture)
        runtime.stop()
        before = columns_snapshot(runtime, idp.h2, "before-upgrade")
        originals = {str(p.relative_to(runtime.directory / "attachments")): digest(p)
                     for p in (runtime.directory / "attachments").rglob("*") if p.is_file()}
        assert len(originals) == 3 and not NEW_TABLES.intersection(before["tables"])
        runtime.start(after_jar); runtime.stop()
        after = columns_snapshot(runtime, idp.h2, "after-upgrade", runtime.directory / "before-upgrade-columns.tsv")
        assert set(after["tables"]) - set(before["tables"]) == NEW_TABLES
        changed = []
        for name, prior in before["tables"].items():
            current = after["tables"][name]
            if (prior["rows"], prior["sha256"]) != (current["rows"], current["originalColumnsSha256"]):
                changed.append(name)
            assert set(before["columns"][name]).issubset(after["columns"][name])
            for column, metadata in before["metadata"][name].items():
                assert metadata == after["metadata"][name][column], (name, column)
        assert changed == ["flyway_schema_history"], changed
        assert set(after["columns"]["EXPENSE_REPORT"]) - set(before["columns"]["EXPENSE_REPORT"]) == NEW_COLUMNS
        assert all(after["tables"][table]["rows"] == 0 for table in NEW_TABLES)
        assert originals == {str(p.relative_to(runtime.directory / "attachments")): digest(p)
                             for p in (runtime.directory / "attachments").rglob("*") if p.is_file()}
        evidence["checks"].append({"name": "nonempty-upgrade", "oldTables": len(before["tables"]), "oldReports": before["tables"]["EXPENSE_REPORT"]["rows"],
                                   "changedOldTables": changed, "addedTables": sorted(NEW_TABLES), "originalFiles": len(originals)})
        risk.stage("UPGRADE_VERIFIED", oldTables=len(before["tables"]))

        runtime.start(after_jar)
        for report in legacy_reports:
            assert detail(runtime, report) == report
            assert split(runtime, report)["status"] == "NOT_RECORDED"
        published = definition(runtime, fixture, "ENABLED")
        reports = [draft(runtime, fixture, published) for _ in range(2)]
        receipts = concurrent_submit(runtime, idp, fixture, reports)
        views = {report["id"]: split(runtime, report) for report in reports}
        assert {view["status"] for view in views.values()} == {"CLEAR", "SPLIT_SUSPECTED"}, views
        escalated = next(report for report in reports if views[report["id"]]["status"] == "SPLIT_SUSPECTED")
        ordinary = next(report for report in reports if report != escalated)
        frozen = views[escalated["id"]]
        assert frozen["details"]["assessment"]["routingAmount"] == {"value": "8300.00", "currency": "CNY"}
        assert len(frozen["details"]["assessment"]["sources"]) == 4
        assert views[ordinary["id"]]["details"]["assessment"]["routingAmount"]["value"] == "4000.00"
        newer = definition(runtime, fixture, "ENABLED", published["key"], "15000")
        third = submit(runtime, fixture, draft(runtime, fixture, newer))
        assert split(runtime, third)["details"]["definitionVersion"] == 2
        assert split(runtime, third)["status"] == "CLEAR"
        assert split(runtime, escalated) == frozen
        cookies = copy.deepcopy(runtime.client.identities)
        pending_tasks = {report["id"]: task(runtime, report, "manager") for report in reports}
        runtime.stop(force=True); runtime.start(after_jar)
        assert runtime.client.identities == cookies
        assert runtime.call("GET", "/auth/me", user="alice")["actor"]["userId"] == "alice"
        for report, receipt in zip(reports, receipts):
            assert task(runtime, report, "manager") == pending_tasks[report["id"]]
            assert runtime.call("POST", receipt["path"], receipt["body"], key=receipt["key"]) == receipt["result"]
            assert runtime.client.records[-1]["replayed"] == "true"
            assert split(runtime, report) == views[report["id"]]
        action(runtime, ordinary); action(runtime, escalated); action(runtime, third)
        assert task(runtime, ordinary, "finance")["taskName"] == "receipt"
        assert task(runtime, escalated, "manager")["taskName"] == "higher"
        assert task(runtime, third, "finance")["taskName"] == "receipt"
        evidence["checks"].append({"name": "concurrent-submit-version-binding-and-crash", "routes": ["receipt", "higher"], "routingAmount": "8300.00",
                                   "sources": 4, "replayedSubmissions": 2, "newDefinitionVersion": newer["version"]})
        risk.stage("CONCURRENT_AND_VERSION_BINDING_VERIFIED")

        current = detail(runtime, ordinary)
        runtime.call("POST", "/expense-reports/" + ordinary["id"] + "/withdraw",
                     {key: current[key] for key in ("applicationVersion", "financialVersion")} | {"comment": "合成撤回"})
        probe = submit(runtime, fixture, draft(runtime, fixture, newer, "1"))
        assert ordinary["id"] not in {item["reportId"] for item in split(runtime, probe)["details"]["assessment"]["sources"]}
        submit(runtime, fixture, ordinary)
        second_round = split(runtime, ordinary, 2)
        assert second_round["details"]["definitionVersion"] == 1
        assert sum(item["reportId"] == ordinary["id"] for item in second_round["details"]["assessment"]["sources"]) == 1
        action(runtime, ordinary, choice="RETURN")
        assert detail(runtime, ordinary)["applicationStatus"] == "RETURNED"
        submit(runtime, fixture, ordinary)
        assert detail(runtime, ordinary)["roundNo"] == 3
        assert split(runtime, ordinary, 1) == views[ordinary["id"]] and split(runtime, ordinary, 2) == second_round
        action(runtime, ordinary)
        assert task(runtime, ordinary, "manager")["taskName"] == "higher"
        evidence["checks"].append({"name": "withdraw-return-resubmit", "rounds": 3, "oldEvidenceUnchanged": True, "ownOldRoundCounted": False})

        action(runtime, escalated)
        assert task(runtime, escalated, "finance")["taskName"] == "receipt"
        action(runtime, escalated, "finance")
        assert task(runtime, escalated, "finance")["taskName"] == "finance"
        current = detail(runtime, escalated); current_task = task(runtime, escalated, "finance")
        hold["enabled"] = True
        runtime.call("POST", "/expense-reports/" + escalated["id"] + "/tasks/" + current_task["taskId"] + "/reduce",
                     {key: current[key] for key in ("applicationVersion", "financialVersion")} | {
                     "lines": [{"lineNo": 1, "approvedGross": "3000.00", "approvedTax": "0.00"}], "reasonCode": "INELIGIBLE_COST", "comment": "合成核减"}, "finance")
        assert hold["entered"].wait(10), "Budget receiver was not reached"
        command_count = len(sources.commands)
        runtime.stop(force=True); hold["enabled"] = False; hold["release"].set()
        runtime.start(after_jar)
        # 强退后的原执行租约默认 90 秒，等待真实过期后查回执，不能在测试中缩短生产租约。
        budget(runtime, escalated, timeout=120)
        assert len(sources.commands) == command_count
        assert split(runtime, escalated) == frozen
        action(runtime, escalated, "finance")
        assert detail(runtime, escalated)["applicationStatus"] == "APPROVED"
        assert detail(runtime, escalated)["financialRound"]["approvedGross"]["value"] == "3000.00"
        assert split(runtime, escalated) == frozen
        evidence["checks"].append({"name": "reduction-receipt-crash-and-financial-route", "frozenRoutingAmount": "8300.00", "approvedOwnAmount": "3000.00",
                                   "newBudgetCommandAfterRestart": False, "financialRecheckEntered": False})
        risk.stage("REDUCTION_AND_RECEIPT_CRASH_VERIFIED")

        submit(runtime, fixture, legacy_draft)
        assert split(runtime, legacy_draft)["status"] == "UNCONFIGURED"
        disabled = submit(runtime, fixture, draft(runtime, fixture, definition(runtime, fixture, "DISABLED"), "1"))
        assert split(runtime, disabled)["status"] == "DISABLED"
        hidden = submit(runtime, fixture, draft(runtime, fixture, definition(runtime, fixture, "DISABLED", hidden=True), "1"))
        guarded = submit(runtime, fixture, draft(runtime, fixture, newer, "1"))
        assert split(runtime, guarded)["sourcesReadable"]
        assert split(runtime, guarded, actor="manager") == {"reportId": guarded["id"], "applicationId": guarded["applicationId"],
                                                          "roundNo": 1, "status": "RESTRICTED", "sourcesReadable": False, "details": None}
        split(runtime, guarded, actor="admin", expected=403)
        split(runtime, guarded, actor="bob", expected=404)
        split(runtime, hidden, actor="manager", expected=403)
        runtime.call("GET", "/expense-reports/" + guarded["id"] + "/split-routing?roundNo=1&amount=1", expected=400)
        bad = draft(runtime, fixture, definition(runtime, fixture, "ENABLED", currency="USD"), "1")
        bad_input = prepare(runtime, fixture, bad); bad_before = detail(runtime, bad)
        failure = runtime.call("POST", "/expense-reports/" + bad["id"] + "/submit", bad_input, expected=422)
        assert failure["code"] == "EXPENSE_SPLIT_CURRENCY_MISMATCH"
        assert detail(runtime, bad) == bad_before
        split(runtime, bad, expected=404)
        evidence["checks"].append({"name": "six-read-states-original-permissions-and-rollback", "states": ["NOT_RECORDED", "UNCONFIGURED", "DISABLED", "CLEAR", "SPLIT_SUSPECTED", "RESTRICTED"],
                                   "failedSubmission": bad["id"], "rollbackCode": failure["code"]})

        original_task = task(runtime, ordinary, "manager")
        third_round = split(runtime, ordinary, 3)
        runtime.stop()
        preserved = columns_snapshot(runtime, idp.h2, "before-restore")
        backup = directory / "backup"; backup.mkdir()
        for name in ("data", "attachments"):
            shutil.copytree(runtime.directory / name, backup / name)
        save(backup / "synthetic-peer-state.json", {"commands": sources.commands, "invoices": sources.invoices, "entity": sources.base.entity})
        save(backup / "manifest.json", {"jarSha256": digest(after_jar), "files": {str(p.relative_to(backup)): digest(p) for p in backup.rglob("*") if p.is_file()}})
        source_database = digest(runtime.directory / "data/agentflow.mv.db")
        restored = risk.Runtime(args.java, directory / "restored", sources, idp)
        for name in ("data", "attachments"):
            shutil.copytree(backup / name, restored.directory / name)
        restored.process = runtime.process
        assert columns_snapshot(restored, idp.h2, "restored-before-start") == preserved
        assert (runtime.directory / "before-restore-schema.sql").read_bytes() == (restored.directory / "restored-before-start-schema.sql").read_bytes()
        peer = json.loads((backup / "synthetic-peer-state.json").read_text())
        sources.commands, sources.invoices, sources.base.entity = peer["commands"], peer["invoices"], peer["entity"]
        restored.start(after_jar)
        restored.client.sessions = runtime.client.sessions
        restored.client.identities = copy.deepcopy(runtime.client.identities)
        assert task(restored, ordinary, "manager") == original_task
        assert split(restored, ordinary, 3) == third_round
        assert split(restored, escalated) == frozen
        for index, invoice in enumerate(fixture["invoices"]):
            original = ("%PDF-1.7\nsynthetic invoice " + str(index) + "\n%%EOF").encode()
            assert restored.call("GET", "/invoices/" + invoice + "/content") == original
        approve_key = str(uuid4())
        approval = action(restored, ordinary, key=approve_key)
        assert task(restored, ordinary, "finance")["taskName"] == "receipt"
        assert restored.call("POST", approval["path"], approval["body"], "manager", key=approve_key) == approval["result"]
        assert restored.client.records[-1]["replayed"] == "true"
        assert split(restored, ordinary, 3) == third_round
        restored.stop()
        assert digest(runtime.directory / "data/agentflow.mv.db") == source_database
        evidence["checks"].append({"name": "paired-restore-and-original-approval", "tables": len(preserved["tables"]),
                                   "rows": sum(item["rows"] for item in preserved["tables"].values()), "originalFiles": len(originals),
                                   "routingRows": preserved["tables"]["EXPENSE_SPLIT_ROUTING"]["rows"],
                                   "sourceRows": preserved["tables"]["EXPENSE_SPLIT_ROUTING_SOURCE"]["rows"], "originalDatabaseUnchanged": True})
        assert not sources.model_calls and not sources.base.model_calls
        assert not sources.errors and not sources.base.errors, (sources.errors, sources.base.errors)
        evidence.update(status="PASSED", finishedAt=instant(), httpRecords=len(runtime.client.records) + len(restored.client.records),
                        distinctBudgetCommands=len(sources.commands), modelCalls=0)
        risk.stage("RESTORE_VERIFIED", tables=len(preserved["tables"]))
    except BaseException as error:
        evidence.update(status="FAILED", finishedAt=instant(), failure=repr(error))
        raise
    finally:
        hold["enabled"] = False; hold["release"].set()
        for owned in (restored, runtime):
            if owned is not None:
                owned.stop()
        if idp is not None:
            idp.close()
        sources.close()
        evidence["ownedProcessesStopped"] = all(owned is None or owned.process is None or owned.process.poll() is not None for owned in (runtime, restored, idp))
        save(directory / "evidence.json", evidence)
        risk.stage("FINISHED", status=evidence["status"], output=str(directory))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", required=True, help="Preserved V115 installation JAR")
    parser.add_argument("--after", required=True, help="Fixed V116 installation JAR")
    parser.add_argument("--java", required=True, help="Absolute Java 17 executable")
    parser.add_argument("--output", required=True, help="Fresh directory under /fyoung/tmp")
    run(parser.parse_args())
