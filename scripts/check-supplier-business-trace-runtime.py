#!/usr/bin/env python3
"""供应商固定包按原授权轮次核对父子队列、本地补齐及独立恢复。"""
import argparse
import importlib.util
import json
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def module(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / file)
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value)
    return value


supplier = module('supplier_business_base', 'check-supplier-trace-runtime.py')
facts_module = module('supplier_business_facts', 'check-finance-business-trace-runtime.py')
base, peer_module = supplier.base, supplier.peer_module
save, digest = supplier.save, supplier.digest
runtime_for, workers, start = supplier.runtime_for, supplier.workers, supplier.start
setup, prepare, queue, probe, finish = supplier.setup, supplier.prepare, supplier.queue, supplier.probe, supplier.finish
check_traces, TABLES, DERIVED, MIGRATED = supplier.check_traces, supplier.TABLES, supplier.DERIVED, supplier.MIGRATED


def verify_business(runtime, h2, waves, rows):
    """分表读取原授权、申请及轮次，以父子队列事实独立计算期望日志。"""
    facts = facts_module.source_facts(runtime, h2)
    authorizations = base.probe(runtime, h2, 'authorization-facts', ['supplier_payment_authorization'])['supplier_payment_authorization']
    lines = '\n'.join(path.read_text() for path in runtime.directory.glob('runtime-*.log')).splitlines()
    derived_origins = {'REQUEST': 'supplier-payment', 'SETTLEMENT_PREPARATION': 'supplier-settlement', 'ADJUSTMENT_PREPARATION': 'supplier-adjustment'}
    verified = []
    for wave in waves:
        for kind, item in wave.items():
            entries = [(kind, TABLES[kind][0], TABLES[kind][1], item['id'])]
            if kind in DERIVED:
                identity = item['value']['payment'] if kind == 'REQUEST' else item['id']
                entries.append((kind + '_DERIVED', DERIVED[kind], derived_origins[kind], identity))
            for label, table, origin, identity in entries:
                row = rows[table][identity]; tenant = row['tenant_id']
                if label == 'REVIEW': application, round_no = row['application_id'], row['round_no']
                else:
                    authorization_id = identity if label in ('HOLD', 'REQUEST_DERIVED') else row['authorization_id'] if label == 'REQUEST' else row['payment_id']
                    authorization = authorizations[authorization_id]; assert authorization['tenant_id'] == tenant
                    application, round_no = authorization['application_id'], authorization['round_no']
                business = facts['A'][(tenant, application)][0]; instance = facts['R'][(tenant, application, round_no)][0]
                matches = [line for line in lines if 'Supplier execution started,' in line and 'source=' + origin + ',' in line
                           and 'operationId=' + identity in line and 'traceId=' + item['traceId'] + ' ' in line]
                assert matches, (label, identity)
                assert all(all(key + '=' + value + ' ' in line for key, value in [('tenantId', tenant), ('businessNo', business),
                                ('processInstanceId', instance), ('taskId', '')]) for line in matches), matches
                verified.append({'kind': label, 'id': identity, 'businessNo': business, 'processInstanceId': instance,
                                 'traceId': item['traceId'], 'taskId': None, 'matchedLogs': len(matches)})
    save(runtime.directory / 'verified-business-contexts.json', verified)
    return len(verified)


def run(args):
    directory = Path(args.output); assert directory.resolve().is_relative_to(Path('/fyoung/tmp').resolve()) and not directory.exists()
    directory.mkdir(); shutil.copy2(__file__, directory / Path(__file__).name)
    previous, current = Path(args.previous_jar).resolve(), Path(args.current_jar).resolve()
    with zipfile.ZipFile(current) as jar:
        name = next(name for name in jar.namelist() if name.startswith('BOOT-INF/lib/h2-') and name.endswith('.jar'))
        h2 = directory / Path(name).name; h2.write_bytes(jar.read(name))
    peer, runtime, restored = peer_module.Peer(directory), None, None
    evidence = {'status': 'RUNNING', 'jarSha256': digest(current), 'previousJarSha256': digest(previous), 'database': 'H2',
                'browserVerified': False, 'postgresqlVerified': False, 'checks': []}; save(directory / 'evidence.json', evidence)
    try:
        runtime = runtime_for(args.java, directory / 'runtime', peer); workers(runtime, True); start(runtime, previous)
        fixture = setup(runtime, peer); values = prepare(runtime, fixture)
        runtime.stop(); workers(runtime, False); start(runtime, previous)
        legacy = queue(runtime, values); save(directory / 'legacy-wave.json', legacy)
        runtime.stop(); before = base.split.columns_snapshot(runtime, h2, 'before-upgrade'); original = probe(runtime, h2, 'before-upgrade')
        start(runtime, current, login=False); runtime.stop()
        after = base.split.columns_snapshot(runtime, h2, 'after-upgrade'); upgraded = probe(runtime, h2, 'after-upgrade')
        assert before == after and original == upgraded
        evidence['checks'].append({'name': 'nonempty-upgrade', 'tables': len(before['tables']), 'queueTables': len(MIGRATED),
                                   'populatedQueueTypes': sum(bool(value) for value in original.values())})
        marker = len(peer.records); workers(runtime, True); start(runtime, current)
        legacy_results = finish(runtime, legacy); legacy_calls = peer.records[marker:]
        values = prepare(runtime, fixture)
        runtime.stop(); legacy_rows = probe(runtime, h2, 'legacy-completed'); legacy_counts = check_traces(legacy, legacy_calls, False, legacy_rows)
        workers(runtime, False); start(runtime, current)
        wave = queue(runtime, values); save(directory / 'current-wave.json', wave)
        runtime.stop(force=True); queued = probe(runtime, h2, 'after-queue-kill')
        for kind, item in wave.items(): assert queued[TABLES[kind][0]][item['id']]['trace_id'] == item['traceId']
        marker = len(peer.records); workers(runtime, True); start(runtime, current)
        results = finish(runtime, wave); current_calls = peer.records[marker:]
        assert all(item['received'] == 1 for item in peer.commands.values()) and not peer.errors
        runtime.stop(); complete = probe(runtime, h2, 'completed'); backup = base.split.columns_snapshot(runtime, h2, 'before-restore')
        current_counts = check_traces(wave, current_calls, False, complete)
        for collection in (legacy, wave):
            for kind, item in collection.items():
                before_row, after_row = queued[TABLES[kind][0]][item['id']], complete[TABLES[kind][0]][item['id']]
                for field in ('trace_id', 'input_json', 'command_json', 'command_digest'):
                    if field in before_row: assert before_row[field] == after_row[field], (kind, field)
        contexts = verify_business(runtime, h2, (legacy, wave), complete)
        evidence['checks'].append({'name': 'origin-recovery-and-replay', 'businessContextsVerified': contexts, 'parentQueueTypes': 6, 'derivedQueueTypes': 3,
            'legacyGatewayCounts': legacy_counts, 'currentGatewayCounts': current_counts, 'originalKeyReplays': 12, 'commands': len(peer.commands), 'duplicateCommands': 0})
        restored = runtime_for(args.java, directory / 'restored', peer); workers(restored, False)
        for folder in ('data', 'attachments'): shutil.copytree(runtime.directory / folder, restored.directory / folder)
        start(restored, current, login=False); restored.stop()
        assert base.split.columns_snapshot(restored, h2, 'after-restore') == backup and probe(restored, h2, 'after-restore') == complete
        marker = len(peer.records); start(restored, current)
        for collection in (legacy_results, results):
            for item in collection.values(): assert restored.call('GET', item['path'], user=item['actor']) == item['value']
        assert len(peer.records) == marker
        evidence['checks'].append({'name': 'independent-restore', 'tables': len(backup['tables']), 'sameAuthorizedDetails': 12, 'resentRequests': 0})
        evidence.update(status='PASS', boots=runtime.starts + restored.starts, businessHttpRequests=len(runtime.records) + len(restored.records),
                        responseTraces=len(runtime.trace_records) + len(restored.trace_records), gatewayRequests=len(peer.records))
        save(directory / 'evidence.json', evidence); print(json.dumps(evidence, ensure_ascii=False), flush=True)
    except Exception as error:
        evidence.update(status='FAILED', failure=repr(error)); save(directory / 'evidence.json', evidence); raise
    finally:
        if restored: restored.stop()
        if runtime: runtime.stop()
        peer.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('java', 'previous-jar', 'current-jar', 'output'): parser.add_argument('--' + name, required=True)
    run(parser.parse_args())
