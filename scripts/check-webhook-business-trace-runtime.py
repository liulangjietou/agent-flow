#!/usr/bin/env python3
"""用真实签名接收方核验旧轮次 Webhook、强退重试及独立数据库恢复。"""

import argparse
import base64
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def module(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / file)
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value)
    return value


base = module('webhook_trace_base', 'check-operation-trace-runtime.py')
approval = module('webhook_approval_fixture', 'check-approval-business-trace-runtime.py')
save, digest = base.save, base.digest


def queued(runtime, h2, label):
    """停服后读取原 outbox 字节；不重建或改写任何事件。"""
    assert runtime.process.poll() is not None
    source = runtime.directory / 'WebhookBodyProbe.java'
    source.write_text(r'''import java.sql.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
class WebhookBodyProbe {
 public static void main(String[] args) throws Exception {
  var lines = new ArrayList<String>();
  try(var connection=DriverManager.getConnection("jdbc:h2:file:"+args[0]+";IFEXISTS=TRUE;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0;REUSE_SPACE=FALSE","sa","")) {
   try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT id,tenant_id,application_id,payload_json FROM webhook_delivery ORDER BY id")) {
    while(rows.next()) lines.add(rows.getString(1)+"\t"+rows.getString(2)+"\t"+rows.getString(3)+"\t"+Base64.getEncoder().encodeToString(rows.getString(4).getBytes(StandardCharsets.UTF_8)));
   }
  } Files.write(Path.of(args[1]),lines,StandardCharsets.UTF_8);
 }
}''')
    output = runtime.directory / (label + '-outbox.tsv')
    with (runtime.directory / (label + '-probe.log')).open('x') as log:
        subprocess.run([runtime.java, '-Djava.io.tmpdir=/fyoung/tmp', '--class-path', str(h2), str(source),
                        str(runtime.directory / 'data/agentflow'), str(output)], stdout=log, stderr=subprocess.STDOUT, check=True, timeout=40)
    rows = {}
    for line in output.read_text().splitlines():
        identity, tenant, application, body = line.split('\t')
        rows[identity] = {'tenant': tenant, 'application': application, 'body': base64.b64decode(body).decode()}
    save(runtime.directory / (label + '-outbox.json'), rows)
    return rows


def wave(runtime):
    submitted = base.submit(runtime)
    result = {'application': submitted['receipt'], 'initial': submitted}
    approval.return_and_resubmit(runtime, result)
    result['deliveries'] = runtime.call('GET', '/integrations/webhooks/deliveries?applicationId=' + submitted['id'], user='admin')['items']
    assert len(result['deliveries']) == 4
    return result


def verify(runtime, receiver, waves, rows):
    lines = '\n'.join(p.read_text() for p in runtime.directory.glob('runtime-*.log')).splitlines()
    assert all(base.SENTRY not in line and approval.SENTRY not in line for line in lines)
    verified = []
    for item in waves:
        app = item['application']
        for delivery in item['deliveries']:
            row = rows[delivery['id']]; body = json.loads(row['body']); payload = body['payload']
            assert row['tenant'] == body['tenantId'] == 'demo'
            assert row['application'] == payload['applicationId'] == app['id']
            instance = item['originalInstance'] if payload['roundNo'] == 1 else item['currentInstance']
            assert payload['roundNo'] in (1, 2)
            task = payload.get('taskId', '')
            if task: assert task == item['originalTask'] and payload['roundNo'] == 1
            matched = [line for line in lines if 'Webhook attempt completed,' in line and 'deliveryId=' + delivery['id'] + ',' in line]
            assert matched, delivery['id']
            expected = [('traceId', body['traceId']), ('tenantId', 'demo'), ('businessNo', app['businessNo']),
                        ('processInstanceId', instance), ('taskId', task)]
            assert all(all(key + '=' + value + ' ' in line for key, value in expected) for line in matched), matched
            receives = [record for record in receiver.records if record['eventId'] == body['eventId']]
            assert len(receives) == (2 if body['eventId'] == receiver.block_event else 1)
            expected_hash = base.hashlib.sha256(row['body'].encode()).hexdigest()
            assert all(record['bodySha256'] == expected_hash and record['body'] == body for record in receives)
            verified.append({'deliveryId': delivery['id'], 'round': payload['roundNo'], 'businessNo': app['businessNo'],
                             'processInstanceId': instance, 'taskId': task or None, 'receives': len(receives), 'matchedLogs': len(matched)})
    assert len(verified) == 8 and not receiver.errors
    save(runtime.directory / 'verified-business-contexts.json', verified)
    return verified


def run(args):
    directory = Path(args.output); assert directory.resolve().is_relative_to(Path('/fyoung/tmp').resolve()) and not directory.exists()
    directory.mkdir(); shutil.copy2(__file__, directory / Path(__file__).name)
    previous, current = Path(args.previous_jar).resolve(), Path(args.current_jar).resolve()
    with zipfile.ZipFile(current) as jar:
        name = next(name for name in jar.namelist() if name.startswith('BOOT-INF/lib/h2-') and name.endswith('.jar'))
        h2 = directory / Path(name).name; h2.write_bytes(jar.read(name))
    receiver = base.Receiver(directory); runtime = base.Runtime(args.java, directory, receiver); restored = None
    runtime.tracing = True
    evidence = {'status': 'RUNNING', 'database': 'H2', 'jarSha256': digest(current), 'previousJarSha256': digest(previous),
                'browserVerified': False, 'postgresqlVerified': False, 'realExternalSystemsVerified': False, 'checks': []}
    save(directory / 'evidence.json', evidence)
    try:
        runtime.start(previous); old = wave(runtime); runtime.stop()
        before = base.split.columns_snapshot(runtime, h2, 'before-upgrade'); original_rows = queued(runtime, h2, 'before-upgrade')
        runtime.start(current); runtime.stop()
        assert base.split.columns_snapshot(runtime, h2, 'after-upgrade') == before
        assert queued(runtime, h2, 'after-upgrade') == original_rows
        evidence['checks'].append({'name': 'nonempty-upgrade', 'tables': len(before['tables']), 'unchangedBodies': len(original_rows)})
        runtime.start(current); new = wave(runtime); waves = [old, new]; save(directory / 'waves.json', waves)
        states = [runtime.call('GET', '/applications/' + item['application']['id']) for item in waves]
        assert all(app['roundNo'] == 2 for app in states)
        receiver.block_event = next(d['eventId'] for d in new['deliveries'] if d['eventType'] == 'TaskActionAccepted')
        runtime.stop(); all_rows = queued(runtime, h2, 'before-delivery')
        runtime.settings['agentflow.webhooks.worker-enabled'] = True; runtime.start(current)
        assert receiver.entered.wait(15), 'Controlled event did not reach signed receiver'
        runtime.stop(force=True); receiver.release.set()
        runtime.start(current)
        details = {}
        for item in waves:
            for delivery in item['deliveries']:
                path = '/integrations/webhooks/deliveries/' + delivery['id']
                details[path] = base.wait_for(lambda: runtime.call('GET', path, user='admin'),
                    lambda value: value['delivery']['status'] == 'DELIVERED', seconds=55)
                if delivery['eventId'] == receiver.block_event:
                    assert [a['result'] for a in details[path]['attempts']] == ['DELIVERED', 'OUTCOME_UNKNOWN']
            for request in (item['returned'], item['resubmitted']):
                assert runtime.call('POST', request['path'], request['body'], request['actor'], key=request['key']) == request['result']
                assert runtime.records[-1]['replayed'] == 'true'
        assert [runtime.call('GET', '/applications/' + item['application']['id']) for item in waves] == states
        runtime.stop(); assert queued(runtime, h2, 'completed') == all_rows
        contexts = verify(runtime, receiver, waves, all_rows)
        evidence['checks'].append({'name': 'cross-round-kill-and-retry', 'applications': 2, 'rounds': 4, 'businessContextsVerified': len(contexts),
                                  'originalKeyReplays': 6, 'unchangedSignedBodies': len(all_rows), 'receivedRequests': len(receiver.records)})
        backup = base.split.columns_snapshot(runtime, h2, 'before-restore'); restore_dir = directory / 'restored'; restore_dir.mkdir()
        shutil.copytree(directory / 'data', restore_dir / 'data')
        restored = base.Runtime(args.java, restore_dir, receiver); restored.tracing = True; restored.settings['agentflow.webhooks.worker-enabled'] = True
        count = len(receiver.records); restored.start(current)
        for path, detail in details.items(): assert restored.call('GET', path, user='admin') == detail
        assert [restored.call('GET', '/applications/' + item['application']['id']) for item in waves] == states
        restored.stop(); assert base.split.columns_snapshot(restored, h2, 'after-restore') == backup
        assert queued(restored, h2, 'after-restore') == all_rows and len(receiver.records) == count and not receiver.errors
        evidence['checks'].append({'name': 'independent-restore', 'tables': len(backup['tables']), 'sameAuthorizedDetails': len(details) + len(states), 'resentRequests': 0})
        evidence.update(status='PASS', boots=runtime.starts + restored.starts, businessHttpRequests=len(runtime.records) + len(restored.records))
        save(directory / 'evidence.json', evidence); print(json.dumps(evidence), flush=True)
    except Exception as error:
        evidence.update(status='FAILED', failure=repr(error)); save(directory / 'evidence.json', evidence); raise
    finally:
        runtime.stop()
        if restored: restored.stop()
        receiver.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    for field in ('java', 'previous-jar', 'current-jar', 'output'): parser.add_argument('--' + field, required=True)
    run(parser.parse_args())
