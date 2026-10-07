#!/usr/bin/env python3
"""固定包核对代理、期限、升级及计时器的原业务身份和重启后来源。"""
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


direct = module('direct_business_base', 'check-direct-scheduler-trace-runtime.py')
facts_module = module('direct_business_facts', 'check-finance-business-trace-runtime.py')
diagram, split = direct.diagram, direct.split
save, digest, runtime_for = direct.save, direct.digest, direct.runtime_for
enable, setup, prepare, probe = direct.enable, direct.setup, direct.prepare, direct.probe
wait_until, finish, origins = direct.wait_until, direct.finish, direct.origins


def verify_business(runtime, h2, waves):
    """按持久申请及原轮次独立验证八组作用域；计时器不伪造人工任务。"""
    facts = facts_module.source_facts(runtime, h2)
    lines = '\n'.join(path.read_text() for path in runtime.directory.glob('runtime-*.log')).splitlines()
    checked = []
    for wave in waves:
        task, timer = wave['task'], wave['timer']
        entries = [('approval-proxy-notification', task, wave['proxyTrace'], wave['proxyId'] + ':' + task['taskId'], task['taskId']),
                   ('task-deadline-reminder', task, task['trace'], task['taskId'], task['taskId']),
                   ('task-escalation', task, task['trace'], task['taskId'], task['taskId']),
                   ('timer-wait', timer, timer['trace'], timer['jobId'], '')]
        for scope, entry, trace, identity, task_id in entries:
            application = entry['application']['id']; business = facts['A'][('demo', application)][0]
            instance = facts['R'][('demo', application, '1')][0]
            matches = [line for line in lines if 'Direct scheduler execution completed,' in line and 'source=' + scope + ',' in line
                       and 'objectId=' + identity in line and 'traceId=' + trace + ' ' in line]
            assert len(matches) == 1, (scope, identity, matches)
            assert all(key + '=' + value + ' ' in matches[0] for key, value in [('tenantId', 'demo'), ('businessNo', business),
                       ('processInstanceId', instance), ('taskId', task_id)]), matches[0]
            checked.append({'source': scope, 'id': identity, 'traceId': trace, 'businessNo': business,
                            'processInstanceId': instance, 'taskId': task_id or None})
    save(runtime.directory / 'verified-business-contexts.json', checked)
    return len(checked)


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
        runtime = runtime_for(args.java, directory / 'runtime'); diagram.start(runtime, previous)
        people, calendar = setup(runtime); old = prepare(runtime, people, calendar)
        runtime.stop(force=True); before = split.columns_snapshot(runtime, h2, 'before-upgrade'); prior = probe(runtime, h2, 'before-upgrade')
        diagram.start(runtime, current, login=False); runtime.stop()
        after = split.columns_snapshot(runtime, h2, 'after-upgrade'); upgraded = probe(runtime, h2, 'after-upgrade')
        assert before == after and prior == upgraded
        evidence['checks'].append({'name': 'nonempty-upgrade', 'unchangedTables': len(before['tables']),
                                  'populatedSources': ['proxy', 'task', 'timer']})
        diagram.start(runtime, current); new = prepare(runtime, people, calendar)
        save(directory / 'waves.json', {'old': old, 'new': new}); runtime.stop(force=True)
        pending = probe(runtime, h2, 'after-kill')
        assert next(row for row in pending['origin'] if row['object_id'] == new['task']['taskId'])['trace_id'] == new['task']['trace']
        assert next(row for row in pending['origin'] if row['object_id'] == new['timer']['jobId'])['trace_id'] == new['timer']['trace']
        assert next(row for row in pending['proxy'] if row['id'] == new['proxyId'])['trace_id'] == new['proxyTrace']
        # 先验收代理开始生效，再启动期限扫描，避免两种合法提醒竞争同一去重来源。
        runtime.settings['agentflow.notifications.proxy-reminders-enabled'] = True
        diagram.start(runtime, current)
        def proxies_ready():
            items = runtime.call('GET', '/notifications?limit=100', user='finance')['items']
            return all(any(item['applicationId'] == wave['task']['application']['id'] and item['kind'] == 'TASK_PENDING' for item in items) for wave in (old, new))
        wait_until(proxies_ready, 50)
        runtime.stop(); enable(runtime, True); diagram.start(runtime, current); finish(runtime, [old, new])
        replayed = 0
        for wave in (old, new):
            for request in (wave['task']['replay'], wave['timer']['replay'], wave['proxyReplay']):
                assert runtime.call('POST', request['path'], request['body'], request['actor'], request['status'], key=request['key']) == request['result']
                assert runtime.records[-1]['replayed'] == 'true'; replayed += 1
        applications = [runtime.call('GET', '/applications/' + wave[kind]['application']['id']) for wave in (old, new) for kind in ('task', 'timer')]
        assert all(value['status'] == 'IN_APPROVAL' for value in applications)
        runtime.stop(); completed = probe(runtime, h2, 'completed'); matched = origins(completed, old) + origins(completed, new)
        backup = split.columns_snapshot(runtime, h2, 'before-restore')
        contexts = verify_business(runtime, h2, (old, new))
        evidence['checks'].append({'name': 'restart-real-scheduler-and-replay', 'businessContextsVerified': contexts, 'matchedNotificationOrigins': matched, 'replayedOriginalKeys': replayed,
                                  'timersAdvancedOnce': 2, 'sourceKinds': ['proxy', 'deadline', 'escalation', 'timer']})
        restored = runtime_for(args.java, directory / 'restored')
        for folder in ('data', 'attachments'): shutil.copytree(runtime.directory / folder, restored.directory / folder)
        diagram.start(restored, current, login=False); restored.stop()
        assert split.columns_snapshot(restored, h2, 'after-restore') == backup and probe(restored, h2, 'after-restore') == completed
        enable(restored, True); diagram.start(restored, current); finish(restored, [old, new])
        for value in applications: assert restored.call('GET', '/applications/' + value['id']) == value
        restored.stop(); assert probe(restored, h2, 'after-restart') == completed
        evidence['checks'].append({'name': 'independent-restore', 'tables': len(backup['tables']), 'sameAuthorizedDetails': len(applications), 'duplicateNotifications': 0})
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
