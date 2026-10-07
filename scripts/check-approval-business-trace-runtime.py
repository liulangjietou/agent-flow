#!/usr/bin/env python3
"""固定包核验授权后的审批标识、旧轮次接续、强退重放和独立恢复。"""

import argparse
import base64
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
from uuid import uuid4
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('business_direct', ROOT / 'scripts/check-direct-scheduler-trace-runtime.py')
direct = importlib.util.module_from_spec(spec); spec.loader.exec_module(direct)
diagram, split, save, digest = direct.diagram, direct.split, direct.save, direct.digest
SENTRY = 'private-business-trace-sentinel'


def probe(runtime, h2, label):
    """仅在停服后读取原审计正文和轮次；不改写业务或引擎表。"""
    assert runtime.process.poll() is not None
    source = runtime.directory / 'BusinessTraceProbe.java'
    source.write_text(r'''import java.sql.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
class BusinessTraceProbe {
 public static void main(String[] args) throws Exception {
  var lines = new ArrayList<String>();
  try(var connection=DriverManager.getConnection("jdbc:h2:file:"+args[0]+";IFEXISTS=TRUE;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0;REUSE_SPACE=FALSE","sa","")) {
   try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT id,aggregate_type,aggregate_id,payload_json FROM audit_event ORDER BY id")) {
    while(rows.next()) lines.add(rows.getString(1)+"\t"+rows.getString(2)+"\t"+rows.getString(3)+"\t"+Base64.getEncoder().encodeToString(rows.getString(4).getBytes(StandardCharsets.UTF_8)));
   }
  }
  Files.write(Path.of(args[1]),lines,StandardCharsets.UTF_8);
 }
}''')
    output = runtime.directory / (label + '-audit.tsv')
    with (runtime.directory / (label + '-probe.log')).open('x') as log:
        subprocess.run([runtime.java, '-Djava.io.tmpdir=/fyoung/tmp', '--class-path', str(h2), str(source),
                        str(runtime.directory / 'data/agentflow'), str(output)], stdout=log, stderr=subprocess.STDOUT, check=True, timeout=40)
    result = {}
    for line in output.read_text().splitlines():
        identity, kind, aggregate, encoded = line.split('\t')
        raw = base64.b64decode(encoded).decode()
        result[identity] = {'kind': kind, 'aggregate': aggregate, 'raw': raw, 'payload': json.loads(raw)}
    save(runtime.directory / (label + '-audit.json'), result)
    return result


def write(runtime, path, body, actor='alice'):
    key = str(uuid4()); result = runtime.call('POST', path, body, actor, key=key)
    return {'path': path, 'body': body, 'actor': actor, 'key': key, 'result': result, 'traceId': runtime.last_trace}


def prepared(runtime, definition):
    app = runtime.call('POST', '/applications', {'businessNo': 'TRACE-' + uuid4().hex, 'title': SENTRY,
        'processKey': definition['key'], 'definitionVersion': definition['version'], 'payload': {'amount': '10'}}, expected=201)
    submitted = write(runtime, '/applications/' + app['id'] + '/submit', {'expectedVersion': app['version']})
    return {'application': submitted['result'], 'submission': submitted}


def task(runtime, app):
    values = [item for item in runtime.call('GET', '/tasks', user='finance') if item['applicationId'] == app['id']]
    assert len(values) == 1
    return values[0]


def round_instance(runtime, app, round_no):
    values = runtime.call('GET', '/applications/' + app['id'] + '/rounds')
    return next(item['processInstanceId'] for item in values if item['roundNo'] == round_no)


def return_and_resubmit(runtime, wave):
    app = wave['application']; original_task = task(runtime, app)
    first = round_instance(runtime, app, 1)
    runtime.call('GET', '/applications/' + app['id']); wave['readTrace'] = runtime.last_trace
    runtime.call('GET', '/applications/' + app['id'], user='bob', expected=404); wave['deniedTrace'] = runtime.last_trace
    returned = write(runtime, '/tasks/' + original_task['taskId'] + '/actions',
                     {'action': 'RETURN', 'expectedVersion': app['version'], 'comment': SENTRY}, 'finance')
    current = runtime.call('GET', '/applications/' + app['id'])
    submitted = write(runtime, '/applications/' + app['id'] + '/submit', {'expectedVersion': current['version']})
    second = round_instance(runtime, app, 2); current_task = task(runtime, app)
    assert second != first and current_task['taskId'] != original_task['taskId']
    wave.update(originalTask=original_task['taskId'], currentTask=current_task['taskId'], originalInstance=first,
                currentInstance=second, returned=returned, resubmitted=submitted)


def logs(runtime):
    return '\n'.join(path.read_text() for path in sorted(runtime.directory.glob('runtime-*.log')))


def associated(log, trace, business, instance=None, task_id=None):
    selected = [line for line in log.splitlines() if 'traceId=' + trace + ' ' in line]
    assert selected, ('missing trace', trace)
    assert any('businessNo=' + business + ' ' in line
               and (instance is None or 'processInstanceId=' + instance + ' ' in line)
               and (task_id is None or 'taskId=' + task_id + ' ' in line) for line in selected), ('missing business association', trace)
    assert all(SENTRY not in line for line in selected)


def verify(runtime, waves, before, after):
    for identity, original in before.items(): assert after[identity] == original, identity
    log = logs(runtime); checked = 0
    for wave in waves:
        app = wave['application']; business = app['businessNo']
        associated(log, wave['readTrace'], business)
        denied = [line for line in log.splitlines() if 'traceId=' + wave['deniedTrace'] + ' ' in line]
        assert denied and all(business not in line and wave['currentInstance'] not in line and wave['originalTask'] not in line for line in denied)
        associated(log, wave['returned']['traceId'], business, wave['originalInstance'], wave['originalTask'])
        associated(log, wave['resubmitted']['traceId'], business, wave['currentInstance'], wave['currentTask'])
        associated(log, wave['approved']['traceId'], business, wave['currentInstance'], wave['currentTask'])
        for record in (wave['returned'], wave['resubmitted'], wave['approved']):
            rows = [value for value in after.values() if value['payload'].get('traceId') == record['traceId']]
            assert len(rows) == 1, (record['path'], rows)
            payload = rows[0]['payload']; assert payload['businessNo'] == business
            assert payload['processInstanceId'] == (wave['originalInstance'] if record is wave['returned'] else wave['currentInstance'])
            if rows[0]['kind'] == 'Task': assert payload['taskId'] == rows[0]['aggregate']
            checked += 1
        resubmitted = [line for line in log.splitlines() if 'traceId=' + wave['resubmitted']['traceId'] + ' ' in line]
        assert all(wave['originalInstance'] not in line and wave['originalTask'] not in line for line in resubmitted)
    return checked


def run(args):
    directory = Path(args.output); assert directory.resolve().is_relative_to(Path('/fyoung/tmp').resolve()) and not directory.exists()
    directory.mkdir(); shutil.copy2(__file__, directory / Path(__file__).name)
    previous, current = Path(args.previous_jar).resolve(), Path(args.current_jar).resolve()
    with zipfile.ZipFile(current) as jar:
        name = next(name for name in jar.namelist() if name.startswith('BOOT-INF/lib/h2-') and name.endswith('.jar'))
        h2 = directory / Path(name).name; h2.write_bytes(jar.read(name))
    runtime = restored = None
    evidence = {'status': 'RUNNING', 'jarSha256': digest(current), 'previousJarSha256': digest(previous),
                'database': 'H2', 'browserVerified': False, 'postgresqlVerified': False, 'checks': []}
    save(directory / 'evidence.json', evidence)
    try:
        runtime = direct.runtime_for(args.java, directory / 'runtime'); diagram.start(runtime, previous)
        draft = diagram.create(runtime, 'business-' + uuid4().hex, diagram.graph())
        definition, _, _ = diagram.publish(runtime, draft); legacy = prepared(runtime, definition)
        runtime.stop(force=True); before = split.columns_snapshot(runtime, h2, 'before-upgrade'); old_audits = probe(runtime, h2, 'before-upgrade')
        diagram.start(runtime, current, login=False); runtime.stop()
        assert split.columns_snapshot(runtime, h2, 'after-upgrade') == before
        assert probe(runtime, h2, 'after-upgrade') == old_audits
        evidence['checks'].append({'name': 'nonempty-upgrade', 'unchangedTables': len(before['tables']), 'unchangedLegacyAudits': len(old_audits)})
        diagram.start(runtime, current); fresh = prepared(runtime, definition); waves = [legacy, fresh]
        for wave in waves: return_and_resubmit(runtime, wave)
        runtime.stop(force=True); save(directory / 'waves.json', waves)
        diagram.start(runtime, current)
        for wave in waves:
            app = runtime.call('GET', '/applications/' + wave['application']['id'])
            assert task(runtime, app)['taskId'] == wave['currentTask']
            wave['approved'] = write(runtime, '/tasks/' + wave['currentTask'] + '/actions',
                                     {'action': 'APPROVE', 'expectedVersion': app['version'], 'comment': SENTRY}, 'finance')
            for request in (wave['resubmitted'], wave['approved']):
                assert runtime.call('POST', request['path'], request['body'], request['actor'], key=request['key']) == request['result']
                assert runtime.records[-1]['replayed'] == 'true'
        details = [runtime.call('GET', '/applications/' + wave['application']['id']) for wave in waves]
        assert all(app['status'] == 'APPROVED' and app['roundNo'] == 2 for app in details)
        runtime.stop(); final = probe(runtime, h2, 'completed'); checked = verify(runtime, waves, old_audits, final)
        backup = split.columns_snapshot(runtime, h2, 'before-restore'); save(directory / 'waves.json', waves)
        evidence['checks'].append({'name': 'authorized-business-association', 'applications': 2, 'rounds': 4,
            'matchedNewAudits': checked, 'deniedReadsWithoutBusinessIdentifiers': 2, 'replayedOriginalKeys': 4, 'crossRoundMislabels': 0})
        restored = direct.runtime_for(args.java, directory / 'restored')
        for folder in ('data', 'attachments'): shutil.copytree(runtime.directory / folder, restored.directory / folder)
        diagram.start(restored, current, login=False); restored.stop()
        assert split.columns_snapshot(restored, h2, 'after-restore') == backup
        assert probe(restored, h2, 'after-restore') == final
        diagram.start(restored, current)
        for app in details: assert restored.call('GET', '/applications/' + app['id']) == app
        restored.stop(); assert probe(restored, h2, 'after-restart') == final
        evidence['checks'].append({'name': 'independent-restore', 'tables': len(backup['tables']), 'sameAuthorizedDetails': len(details), 'duplicateAudits': 0})
        evidence.update(status='PASS', boots=runtime.starts + restored.starts, businessHttpRequests=len(runtime.records) + len(restored.records),
                        traceHeaders=len(runtime.trace_records) + len(restored.trace_records))
        save(directory / 'evidence.json', evidence); print(json.dumps(evidence, ensure_ascii=False), flush=True)
    except Exception as error:
        evidence.update(status='FAILED', failure=repr(error)); save(directory / 'evidence.json', evidence); raise
    finally:
        if restored: restored.stop()
        if runtime: runtime.stop()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    for field in ('java', 'previous-jar', 'current-jar', 'output'): parser.add_argument('--' + field, required=True)
    run(parser.parse_args())
