#!/usr/bin/env python3
"""报销恢复固定包核对原轮次、排队重启、原键重放及独立恢复。"""
import argparse
import importlib.util
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]


def module(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / file)
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value)
    return value


recovery = module('recovery_business_base', 'check-expense-recovery-trace-runtime.py')
facts_module = module('recovery_business_facts', 'check-finance-business-trace-runtime.py')
review_module = module('recovery_business_probe', 'check-financial-review-trace-runtime.py')
base, peer_module = recovery.base, recovery.peer_module
risk, split, reporting = recovery.risk, recovery.split, recovery.reporting
save, digest = recovery.save, recovery.digest
workers, prepare, queue, finish = recovery.workers, recovery.prepare, recovery.queue, recovery.finish
probe, row, traces, detail = recovery.probe, recovery.row, recovery.traces, recovery.detail
MIGRATED, POPULATED = recovery.MIGRATED, recovery.POPULATED


def verify_business(runtime, h2, waves, rows):
    """从原凭证、结算、预算复核及原轮次独立计算日志标识，不从当前申请猜测。"""
    facts = facts_module.source_facts(runtime, h2)
    originals = review_module.probe(runtime, h2, 'voucher-business-source', ['voucher_operation'])['voucher_operation']
    lines = '\n'.join(path.read_text() for path in runtime.directory.glob('runtime-*.log')).splitlines()
    verified, synchronous_budget_reviews = [], []
    for wave in waves:
        for kind, item in wave['items'].items():
            record = row(rows, item); tenant = record['tenant_id']
            if kind == 'BUDGET':
                # 该固定流程没有预算审批节点，结果在预算回执事务中已确认，不声称后台恢复曾执行。
                assert record['status'] == 'CONFIRMED' and record['budget_node_id'] is None
                assert record['authorized_operation_id'] is None and record['automatic_audit_id'] is None
                synchronous_budget_reviews.append(item['id'])
                continue
            if kind in ('PREPARATION', 'OPERATION'):
                source = originals[record['operation_id']]; assert source['tenant_id'] == tenant
            else: source = record
            application, round_no = source['application_id'], source['round_no']
            business = facts['A'][(tenant, application)][0]; instance = facts['R'][(tenant, application, round_no)][0]
            scopes = [item['scope']] if kind in ('PREPARATION', 'OPERATION') else ['expense-settlement', 'expense-archive']
            for scope in scopes:
                matches = [line for line in lines if 'Expense recovery execution started,' in line and 'source=' + scope + ',' in line
                           and 'operationId=' + item['id'] in line and 'traceId=' + item['traceId'] + ' ' in line]
                assert matches, (kind, scope, item['id'])
                assert all(all(key + '=' + value + ' ' in line for key, value in [('tenantId', tenant), ('businessNo', business),
                               ('processInstanceId', instance), ('taskId', '')]) for line in matches), matches
                verified.append({'kind': kind, 'scope': scope, 'id': item['id'], 'businessNo': business, 'processInstanceId': instance,
                                 'traceId': item['traceId'], 'taskId': None, 'matchedLogs': len(matches)})
    save(runtime.directory / 'verified-business-contexts.json', {'workers': verified, 'synchronousBudgetReviews': synchronous_budget_reviews})
    return len(verified)


def run(args):
    directory = Path(args.output); assert str(directory).startswith('/fyoung/tmp/') and not directory.exists(); directory.mkdir()
    shutil.copy2(__file__, directory / Path(__file__).name)
    previous, current = Path(args.previous_jar).resolve(), Path(args.current_jar).resolve()
    sources, finance_peer = reporting.sources_for(directory)
    commands = peer_module.install(sources, directory, save, risk.instant)
    records = base.observe_gateway(sources, directory)
    risk.ROLES['admin'].add('FINANCE_CONFIG_ADMIN'); risk.ROLES['bob'].add('CASHIER')
    idp = runtime = restored = None
    evidence = {'status': 'RUNNING', 'database': 'H2', 'previousJarSha256': digest(previous), 'jarSha256': digest(current),
        'browserVerified': False, 'postgresqlVerified': False, 'realFinancialSystemsVerified': False, 'checks': []}
    save(directory / 'evidence.json', evidence)
    try:
        idp = risk.IdentityProvider(args.java, directory, current, financial_reporting=True)
        runtime = base.runtime_for(args.java, directory / 'runtime', sources, idp); workers(runtime, True); runtime.start(previous)
        fixture = risk.setup(runtime, sources); fixture['entityId'] = sources.base.entity; reporting.configure_accounts(runtime, fixture)
        values = prepare(runtime, fixture)
        runtime.stop(); workers(runtime, False); runtime.start(previous)
        legacy = queue(runtime, values); save(directory / 'legacy-wave.json', legacy)
        runtime.stop(); before = split.columns_snapshot(runtime, idp.h2, 'before-upgrade'); original = probe(runtime, idp.h2, 'before-upgrade')
        assert all(original[table] for table in POPULATED)
        workers(runtime, False, parents=False); runtime.start(current); runtime.stop()
        after = split.columns_snapshot(runtime, idp.h2, 'after-upgrade')
        upgraded = probe(runtime, idp.h2, 'after-upgrade')
        assert before == after and original == upgraded
        evidence['checks'].append({'name': 'nonempty-upgrade', 'tables': len(before['tables']), 'queueTables': len(MIGRATED), 'populatedQueueTypes': len(POPULATED)})
        marker = len(records); workers(runtime, True); runtime.start(current)
        legacy_views = finish(runtime, legacy); legacy_traces = traces(legacy, records[marker:], False)
        risk.stage('EXPENSE_LEGACY_RECOVERY_VERIFIED', populatedQueueTypes=len(POPULATED))
        values = prepare(runtime, fixture)
        runtime.stop(); workers(runtime, False); runtime.start(current)
        wave = queue(runtime, values); save(directory / 'current-wave.json', wave)
        runtime.stop(force=True); queued = probe(runtime, idp.h2, 'after-queue-kill')
        for item in wave['items'].values(): assert row(queued, item)['trace_id'] == item['traceId'], item['table']
        marker = len(records); workers(runtime, True); runtime.start(current)
        views = finish(runtime, wave); current_traces = traces(wave, records[marker:], False)
        assert all(value['received'] == 1 for value in commands.values())
        assert all(value['receivedCommands'] == 1 for value in finance_peer['receipts'].values())
        assert all(base.tracing.TRACE.fullmatch(value['traceId'] or '') for value in records)
        runtime.stop(); complete = probe(runtime, idp.h2, 'completed'); backup = split.columns_snapshot(runtime, idp.h2, 'before-restore')
        for collection in (legacy, wave):
            for item in collection['items'].values():
                old, new = row(queued, item), row(complete, item)
                for field in ('trace_id', 'input_json', 'command_digest'):
                    if field in old: assert old[field] == new[field], (item['table'], field)
        contexts = verify_business(runtime, idp.h2, (legacy, wave), complete)
        evidence['checks'].append({'name': 'origin-recovery-and-replay', 'businessContextsVerified': contexts,
            'workerEntryPoints': ['voucher-reversal-preparation', 'voucher-reversal-operation', 'expense-settlement', 'expense-archive'],
            'synchronouslyConfirmedBudgetReviews': 2, 'legacyGatewayCounts': legacy_traces, 'currentGatewayCounts': current_traces,
            'originalKeyReplays': len(legacy['requests']) + len(wave['requests']), 'commands': len(commands) + len(finance_peer['receipts']), 'duplicateCommands': 0})
        restored = base.runtime_for(args.java, directory / 'restored', sources, idp); workers(restored, False, parents=False)
        for folder in ('data', 'attachments'): shutil.copytree(runtime.directory / folder, restored.directory / folder)
        restored.start(current); restored.stop()
        assert split.columns_snapshot(restored, idp.h2, 'after-restore') == backup and probe(restored, idp.h2, 'after-restore') == complete
        marker = len(records); restored.start(current)
        for user in ('alice', 'finance'): restored.client.login(user)
        for collection in (legacy_views, views):
            for item in collection.values(): assert detail(restored, item['path'], item.get('user', 'finance')) == item['value']
        assert len(records) == marker and not sources.errors and not sources.base.errors
        evidence['checks'].append({'name': 'independent-restore', 'tables': len(backup['tables']), 'sameAuthorizedDetails': 10, 'resentRequests': 0})
        evidence.update(status='PASS', boots=runtime.starts + restored.starts, gatewayRequests=len(records),
            responseTraces=len(runtime.trace_records) + len(restored.trace_records), businessHttpRequests=len(runtime.client.records) + len(restored.client.records))
        save(directory / 'evidence.json', evidence); print(json.dumps(evidence, ensure_ascii=False), flush=True)
    except Exception as error:
        evidence.update(status='FAILED', failure=repr(error)); save(directory / 'evidence.json', evidence); raise
    finally:
        if restored: restored.stop()
        if runtime: runtime.stop()
        if idp: idp.close()
        sources.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('java', 'previous-jar', 'current-jar', 'output'): parser.add_argument('--' + name, required=True)
    run(parser.parse_args())
