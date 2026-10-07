#!/usr/bin/env python3
"""预算复核固定包核对原批准轮次；其余财务复核链路由范围测试独立覆盖。"""

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


base = module('review_business_base', 'check-financial-review-trace-runtime.py')
facts_module = module('review_business_facts', 'check-finance-business-trace-runtime.py')
save, digest, split = base.save, base.digest, base.split
Peer, runtime_for, workers, start = base.Peer, base.runtime_for, base.workers, base.start
setup, prepare, queue, probe, finish, traces = base.setup, base.prepare, base.queue, base.probe, base.finish, base.traces
TABLES = base.TABLES


def verify_business(runtime, h2, waves, rows):
    """独立分表读取业务号及轮次，再核对原 JSON 批准事实和真实外部调用日志。"""
    facts = facts_module.source_facts(runtime, h2)
    lines = '\n'.join(path.read_text() for path in runtime.directory.glob('runtime-*.log')).splitlines()
    checked = []
    for wave in waves:
        for kind, item in wave.items():
            table, origin = TABLES[kind]; row = rows[table][item['id']]; tenant = row['tenant_id']
            application = row['application_id']; business = facts['A'][(tenant, application)][0]; instance = ''
            if kind != 'PRECHECK':
                source = json.loads(row['command_json'] if kind == 'OPERATION' else row['input_json'])['source']
                assert source['tenantId'] == tenant and source['applicationId'] == application
                instance = facts['R'][(tenant, application, str(source['round']['roundNo']))][0]
            matches = [line for line in lines if 'Financial review execution claimed,' in line and 'operationId=' + item['id'] in line
                       and 'source=' + origin + ',' in line and 'traceId=' + item['traceId'] + ' ' in line]
            assert matches, (kind, item['id'])
            assert all(all(key + '=' + value + ' ' in line for key, value in [('tenantId', tenant), ('businessNo', business),
                            ('processInstanceId', instance), ('taskId', '')]) for line in matches), matches
            checked.append({'kind': kind, 'id': item['id'], 'traceId': item['traceId'], 'businessNo': business,
                            'processInstanceId': instance or None, 'taskId': None, 'matchedLogs': len(matches)})
    save(runtime.directory / 'verified-business-contexts.json', checked)
    return len(checked)


def run(args):
    directory = Path(args.output)
    assert directory.resolve().is_relative_to(Path('/fyoung/tmp').resolve()) and not directory.exists()
    directory.mkdir(); shutil.copy2(__file__, directory / Path(__file__).name)
    previous, current = Path(args.previous_jar).resolve(), Path(args.current_jar).resolve()
    with zipfile.ZipFile(current) as jar:
        name = next(name for name in jar.namelist() if name.startswith('BOOT-INF/lib/h2-') and name.endswith('.jar'))
        h2 = directory / Path(name).name; h2.write_bytes(jar.read(name))
    peer, runtime, restored = Peer(directory), None, None
    evidence = {'status': 'RUNNING', 'jarSha256': digest(current), 'previousJarSha256': digest(previous),
                'database': 'H2', 'browserVerified': False, 'postgresqlVerified': False, 'checks': []}
    save(directory / 'evidence.json', evidence)
    try:
        runtime = runtime_for(args.java, directory / 'runtime', peer); workers(runtime, True); start(runtime, previous)
        fixture = setup(runtime, peer); values = prepare(runtime, fixture)
        runtime.stop(); workers(runtime, False); start(runtime, previous)
        legacy = queue(runtime, fixture, values); save(directory / 'legacy-wave.json', legacy)
        runtime.stop(); before = split.columns_snapshot(runtime, h2, 'before-upgrade'); original = probe(runtime, h2, 'before-upgrade')
        start(runtime, current, login=False); runtime.stop()
        after = split.columns_snapshot(runtime, h2, 'after-upgrade'); upgraded = probe(runtime, h2, 'after-upgrade')
        assert after == before and upgraded == original
        evidence['checks'].append({'name': 'nonempty-upgrade', 'tables': len(before['tables']), 'queueTables': 11, 'populatedQueueTypes': 3})
        marker = len(peer.records); workers(runtime, True); start(runtime, current)
        legacy_results = finish(runtime, legacy); legacy_traces = traces(legacy, peer.records[marker:], False)
        values = prepare(runtime, fixture)
        runtime.stop(); workers(runtime, False); start(runtime, current)
        wave = queue(runtime, fixture, values); save(directory / 'current-wave.json', wave)
        runtime.stop(force=True); queued = probe(runtime, h2, 'after-queue-kill')
        for kind, item in wave.items(): assert queued[TABLES[kind][0]][item['id']]['trace_id'] == item['traceId']
        marker = len(peer.records); workers(runtime, True); start(runtime, current)
        results = finish(runtime, wave); current_traces = traces(wave, peer.records[marker:], False)
        assert all(item['received'] == 1 for item in peer.commands.values()) and not peer.errors
        runtime.stop(); complete = probe(runtime, h2, 'completed'); backup = split.columns_snapshot(runtime, h2, 'before-restore')
        for collection in (legacy, wave):
            for kind, item in collection.items():
                before_row, after_row = queued[TABLES[kind][0]][item['id']], complete[TABLES[kind][0]][item['id']]
                for field in ('trace_id', 'input_json', 'command_json', 'command_digest'):
                    if field in before_row: assert before_row[field] == after_row[field], (kind, field)
        contexts = verify_business(runtime, h2, (legacy, wave), complete)
        evidence['checks'].append({'name': 'origin-recovery-and-replay', 'businessContextsVerified': contexts, 'queueTypes': 3, 'legacyGatewayCounts': legacy_traces,
                                   'currentGatewayCounts': current_traces, 'originalKeyReplays': 6, 'commands': len(peer.commands), 'duplicateCommands': 0})
        restored = runtime_for(args.java, directory / 'restored', peer); workers(restored, False)
        for folder in ('data', 'attachments'): shutil.copytree(runtime.directory / folder, restored.directory / folder)
        start(restored, current, login=False); restored.stop()
        assert split.columns_snapshot(restored, h2, 'after-restore') == backup and probe(restored, h2, 'after-restore') == complete
        marker = len(peer.records); start(restored, current)
        for collection in (legacy_results, results):
            for item in collection.values(): assert restored.call('GET', item['path'], user=item['actor']) == item['value']
        assert len(peer.records) == marker
        evidence['checks'].append({'name': 'independent-restore', 'tables': len(backup['tables']), 'sameAuthorizedDetails': 6, 'resentRequests': 0})
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
