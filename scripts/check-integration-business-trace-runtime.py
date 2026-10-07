#!/usr/bin/env python3
"""固定包核对集成原业务、原轮次、实际 HTTP/SMTP 及独立恢复，不改变协议正文。"""

import argparse
import importlib.util
import json
from pathlib import Path
import shutil
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('integration_business_base', ROOT / 'scripts/check-integration-trace-runtime.py')
base = importlib.util.module_from_spec(spec); spec.loader.exec_module(base)
facts_module = base.module('integration_business_facts', 'scripts/check-finance-business-trace-runtime.py')
finance, fixtures, risk, reporting, split = base.finance, base.fixtures, base.risk, base.reporting, base.split
save, digest, TABLES, CORE_TABLES = base.save, base.digest, base.TABLES, base.CORE_TABLES
runtime_for, workers, finish, prepare, queue, family = base.runtime_for, base.workers, base.finish, base.prepare, base.queue, base.family
definition, draft, preferences, java_instant = base.definition, base.draft, base.preferences, base.java_instant


def verify_business(runtime, waves, rows, idp):
    """独立逐表读取业务事实，不使用候选 JOIN 或从日志反推预期。"""
    facts = facts_module.source_facts(runtime, idp.h2)
    messages = finance.probe(runtime, idp, 'business-messages', {'INBOX': ('notification_inbox', '')})['notification_inbox']
    lines = '\n'.join(p.read_text() for p in sorted(runtime.directory.glob('runtime-*.log'))).splitlines()
    verified = []
    for wave in waves:
        for kind, item in wave['queues'].items():
            category = family(kind); row = rows[TABLES[category][0]][item['id']]; tenant = row['tenant_id']
            matched = [line for line in lines if 'Integration execution started,' in line
                       and 'traceId=' + item['sourceTrace'] + ' ' in line
                       and 'source=' + TABLES[category][1] + ',' in line and 'operationId=' + item['id'] in line]
            assert matched, (kind, item['id'])
            business = instance = task = ''
            if category != 'ORGANIZATION':
                if category == 'NOTIFICATION':
                    message = messages[row['inbox_id']]
                    assert message['tenant_id'] == tenant and message['recipient_id'] == row['recipient_id']
                    application, round_no = message['application_id'], message['round_no']; task = message['task_id'] or ''
                elif category == 'CALLBACK':
                    assert row['payment_kind'] == 'EMPLOYEE', 'Supplier callback is covered by scoped SQL tests, not this fixture'
                    application, round_no = facts['P'][(tenant, row['employee_payment_id'])]
                elif category == 'EVENT':
                    signal = json.loads(row['input_json'])['signal']
                    assert signal['tenantId'] == tenant and signal['applicationId'] == row['application_id']
                    application, round_no = signal['applicationId'], str(signal['roundNo'])
                else: application, round_no = row['application_id'], row['round_no']
                business = facts['A'][(tenant, application)][0]
                instance = row['process_instance_id'] if category == 'SERVICE' else facts['R'][(tenant, application, round_no)][0]
            for line in matched:
                for key, value in [('tenantId', tenant), ('businessNo', business), ('processInstanceId', instance), ('taskId', task)]:
                    assert key + '=' + value + ' ' in line, (kind, key, value, line)
            verified.append({'kind': kind, 'id': item['id'], 'traceId': item['sourceTrace'], 'businessNo': business or None,
                             'processInstanceId': instance or None, 'taskId': task or None, 'matchedLogs': len(matched)})
    save(runtime.directory / 'verified-business-contexts.json', verified)
    return len(verified)


def notification_run(args, directory, previous, current, sources, idp, peers):
    runtime = restored = None
    tables = {"NOTIFICATION": TABLES["NOTIFICATION"]}
    def login(value):
        for actor in ("alice", "manager", "admin"): value.client.login(actor)
    def create_wave(value):
        preferences(value, True)
        approval = {"id": "review", "name": "审批", "type": "USER_TASK", "properties": {"assigneeRule": "user:manager"}}
        application = draft(value, definition(value, "trace-notification", [approval])); body = {"expectedVersion": application["version"]}
        path = "/applications/" + application["id"] + "/submit"; key = str(uuid4())
        receipt = value.call("POST", path, body, key=key); trace = value.last_trace
        items = value.call("GET", "/notifications/deliveries?status=PENDING", user="manager")["items"]; assert len(items) == 2, items
        queues = {"NOTIFICATION_" + item["channel"]: {"id": item["id"], "sourceTrace": trace,
                  "path": "/notifications/deliveries/" + item["id"], "user": "manager"} for item in items}
        return {"queues": queues, "requests": [{"path": path, "body": body, "key": key, "user": "alice", "expected": 200, "receipt": receipt}]}
    try:
        runtime = runtime_for(args.java, directory / "notification", sources, idp, peers, notifications=True)
        finance.workers(runtime, False); runtime.start(previous); login(runtime); legacy = create_wave(runtime)
        save(directory / "notification-legacy-wave.json", legacy)
        runtime.stop(); before = split.columns_snapshot(runtime, idp.h2, "before-upgrade")
        old = finance.probe(runtime, idp, "before-upgrade", tables)
        runtime.start(current); runtime.stop()
        assert split.columns_snapshot(runtime, idp.h2, "after-upgrade") == before
        assert finance.probe(runtime, idp, "after-upgrade", tables) == old
        runtime.settings["agentflow.notifications.delivery-worker-enabled"] = True
        runtime.start(current); login(runtime); legacy_results = finish(runtime, legacy, peers, False)
        runtime.stop(); runtime.settings["agentflow.notifications.delivery-worker-enabled"] = False
        runtime.start(current); login(runtime); wave = create_wave(runtime); save(directory / "notification-current-wave.json", wave)
        runtime.stop(force=True); queued = finance.probe(runtime, idp, "after-queued-kill", tables)
        for item in wave["queues"].values(): assert queued["notification_dispatch"][item["id"]]["trace_id"] == item["sourceTrace"]
        runtime.settings["agentflow.notifications.delivery-worker-enabled"] = True
        runtime.start(current); login(runtime); results = finish(runtime, wave, peers, False)
        runtime.stop(); final = finance.probe(runtime, idp, "completed", tables); backup = split.columns_snapshot(runtime, idp.h2, "before-restore")
        contexts = verify_business(runtime, (legacy, wave), final, idp)
        restored = runtime_for(args.java, directory / "notification-restored", sources, idp, peers, notifications=True); finance.workers(restored, False)
        for folder in ("data", "attachments"): shutil.copytree(runtime.directory / folder, restored.directory / folder)
        restored.start(current); restored.stop()
        assert split.columns_snapshot(restored, idp.h2, "after-restore") == backup
        assert finance.probe(restored, idp, "after-restore", tables) == final
        count = len(peers.calls), len(peers.mail)
        restored.start(current); login(restored)
        for collection, expected in ((legacy, legacy_results), (wave, results)):
            for kind, item in collection["queues"].items(): assert restored.call("GET", item["path"], user=item["user"]) == expected[kind]
        assert count == (len(peers.calls), len(peers.mail)) and not peers.errors
        return {"status": "PASS", "boots": runtime.starts + restored.starts, "queueTables": 1, "nonemptyUpgradeTables": len(before["tables"]),
                "legacyItems": 2, "currentItems": 2, "sameAuthorizedDetails": 4, "resentRequests": 0, "businessContextsVerified": contexts,
                "responseTraces": len(runtime.trace_records) + len(restored.trace_records), "businessHttpRequests": len(runtime.client.records) + len(restored.client.records)}
    finally:
        if restored: restored.stop()
        if runtime: runtime.stop()

def run(args):
    directory = Path(args.output); assert directory.resolve().is_relative_to(Path('/fyoung/tmp').resolve()) and not directory.exists()
    directory.mkdir(); shutil.copy2(__file__, directory / Path(__file__).name)
    shutil.copy2(ROOT / "scripts/fixtures/IntegrationTracePeers.py", directory / "IntegrationTracePeers.py")
    previous, current = Path(args.previous_jar).resolve(), Path(args.current_jar).resolve()
    sources, financial_peer = reporting.sources_for(directory)
    financial_traces = finance.observe_gateway(sources, directory)
    risk.ROLES["admin"].add("FINANCE_CONFIG_ADMIN"); risk.ROLES["bob"].add("CASHIER")
    peers = fixtures.Peers(directory, args.node, java_instant)
    idp = runtime = restored = None
    evidence = {"status": "RUNNING", "database": "H2", "jarSha256": digest(current), "previousJarSha256": digest(previous),
                "browserVerified": False, "postgresqlVerified": False, "realExternalSystemsVerified": False, "checks": []}
    save(directory / "evidence.json", evidence)
    try:
        idp = risk.IdentityProvider(args.java, directory, current, financial_reporting=True)
        runtime = runtime_for(args.java, directory / "runtime", sources, idp, peers); runtime.start(previous)
        fixture = risk.setup(runtime, sources); fixture["entityId"] = sources.base.entity; reporting.configure_accounts(runtime, fixture)
        runtime.initiator = fixture["appointments"]["alice"]
        legacy = queue(runtime, prepare(runtime, sources, financial_peer, fixture, peers)); save(directory / "legacy-wave.json", legacy)
        runtime.stop(); before = split.columns_snapshot(runtime, idp.h2, "before-upgrade"); old_rows = finance.probe(runtime, idp, "before-upgrade", CORE_TABLES)
        runtime.start(current); runtime.stop(); upgraded = finance.probe(runtime, idp, "after-upgrade", CORE_TABLES)
        assert split.columns_snapshot(runtime, idp.h2, "after-upgrade") == before
        assert upgraded == old_rows
        evidence["checks"].append({"name": "nonempty-upgrade", "tables": len(before["tables"]), "queueTables": 5})
        workers(runtime, True); runtime.start(current); legacy_results = finish(runtime, legacy, peers, False)
        risk.stage("INTEGRATION_LEGACY_RECOVERY_VERIFIED", queues=5)
        prepared = prepare(runtime, sources, financial_peer, fixture, peers)
        runtime.stop(); workers(runtime, False); runtime.start(current)
        wave = queue(runtime, prepared); save(directory / "current-wave.json", wave)
        runtime.stop(force=True); queued = finance.probe(runtime, idp, "after-queued-kill", CORE_TABLES)
        for kind, item in wave["queues"].items(): assert queued[TABLES[family(kind)][0]][item["id"]]["trace_id"] == item["sourceTrace"], kind
        workers(runtime, True); runtime.start(current); results = finish(runtime, wave, peers, False)
        runtime.stop(); final = finance.probe(runtime, idp, "completed", CORE_TABLES)
        for collection in (legacy, wave):
            for kind, item in collection["queues"].items():
                table = TABLES[family(kind)][0]; old, new = queued[table][item["id"]], final[table][item["id"]]
                for column in ("trace_id", "input_json", "context_json", "command_digest", "request_digest", "target_digest"):
                    if column in old: assert old[column] == new[column], (kind, column)
        contexts = verify_business(runtime, (legacy, wave), final, idp)
        assert all(receipt["receivedCommands"] == 1 for receipt in financial_peer["receipts"].values())
        for collection in (legacy, wave):
            request = next(item["signed"] for item in collection["requests"] if "signed" in item and item["signed"]["path"].endswith("/callbacks"))
            identity = request["body"]["authorizationId"]
            commands = [call for call in financial_traces if call["operation"] == "payment-command" and call["body"]["data"]["command"]["id"] == identity]
            queries = [call for call in financial_traces if call["operation"] == "payment-query" and call["body"]["data"]["authorizationId"] == identity]
            assert len(commands) == 1 and len(queries) == 1
            assert queries[0]["traceId"] == commands[0]["traceId"] != collection["queues"]["CALLBACK"]["sourceTrace"]
        evidence["checks"].append({"name": "queued-kill-and-restart", "queueTables": 5, "legacyItems": 5, "currentItems": 5, "replays": 10, "businessContextsVerified": contexts})
        backup = split.columns_snapshot(runtime, idp.h2, "before-restore")
        restored = runtime_for(args.java, directory / "restored", sources, idp, peers)
        for folder in ("data", "attachments"): shutil.copytree(runtime.directory / folder, restored.directory / folder)
        finance.workers(restored, False); restored.start(current); restored.stop()
        assert split.columns_snapshot(restored, idp.h2, "after-restore") == backup
        assert finance.probe(restored, idp, "after-restore", CORE_TABLES) == final
        count = len(peers.calls), len(peers.mail), len(sources.finance_calls)
        restored.start(current)
        for actor in ("alice", "admin", "manager"): restored.client.login(actor)
        for collection, expected in ((legacy, legacy_results), (wave, results)):
            for kind, item in collection["queues"].items(): assert restored.call("GET", item["path"], user=item["user"]) == expected[kind], kind
        assert count == (len(peers.calls), len(peers.mail), len(sources.finance_calls))
        assert not sources.errors and not sources.base.errors and not peers.errors
        restored.stop()
        evidence["checks"].append({"name": "independent-restore", "tables": len(backup["tables"]), "sameAuthorizedDetails": 10, "resentRequests": 0})
        evidence["notificationRuntime"] = notification_run(args, directory, previous, current, sources, idp, peers)
        evidence.update(status="PASS", boots=runtime.starts + restored.starts, integrationHttpRequests=len(peers.calls), smtpMessages=len(peers.mail),
                        responseTraces=len(runtime.trace_records) + len(restored.trace_records), businessHttpRequests=len(runtime.client.records) + len(restored.client.records), signedHttpRequests=8)
        save(directory / "evidence.json", evidence); print(json.dumps(evidence, ensure_ascii=False), flush=True)
    except Exception as error:
        evidence.update(status="FAILED", failure=repr(error)); save(directory / "evidence.json", evidence); raise
    finally:
        if restored: restored.stop()
        if runtime: runtime.stop()
        if idp: idp.close()
        peers.close(); sources.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("java", "node", "previous-jar", "current-jar", "output"): parser.add_argument("--" + name, required=True)
    run(parser.parse_args())
