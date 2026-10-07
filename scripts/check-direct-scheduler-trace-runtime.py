#!/usr/bin/env python3
"""固定包验证原生任务与代理提醒来源、旧库升级、强退接续及独立恢复。"""

import argparse
import base64
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import time
from uuid import UUID, uuid4
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def module(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / file)
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value)
    return value


diagram = module('direct_diagram', 'check-definition-diagram-runtime.py')
trace = module('direct_trace', 'check-agent-trace-runtime.py')
common, split = diagram.common, diagram.split
save, digest = common.save, common.digest
MIGRATED = ['organization_approval_proxy', 'employee_advance_order', 'expense_budget_retention']


def instant(seconds=0):
    return (datetime.now(timezone.utc) + timedelta(seconds=seconds)).isoformat().replace('+00:00', 'Z')


def runtime_for(java, directory):
    runtime = diagram.runtime_for(java, directory)
    runtime.last_trace, runtime.trace_records = None, []
    runtime.http.add_handler(trace.TraceResponse(runtime))
    for flag in ('agentflow.sla.reminder-delay-ms', 'agentflow.notifications.proxy-reminder-delay-ms', 'agentflow.timers.delay-ms'):
        runtime.settings[flag] = 200
    return runtime


def enable(runtime, enabled):
    for flag in ('agentflow.sla.reminders-enabled', 'agentflow.notifications.proxy-reminders-enabled', 'agentflow.timers.enabled'):
        runtime.settings[flag] = enabled


def wait_until(read, seconds):
    until = time.monotonic() + seconds
    while not read():
        assert time.monotonic() < until, 'Synthetic scheduler did not reach its expected state'
        time.sleep(1)


def setup(runtime):
    call = runtime.call; call('POST', '/organization/initialize', {}, 'admin', 201)
    people = {}
    for actor in ('alice', 'manager', 'finance'):
        person = call('POST', '/organization/people', {'subject': actor, 'displayName': '合成' + actor, 'active': True, 'approvalEligible': actor != 'alice'}, 'admin', 201)
        people[actor] = person['id']
    for actor in ('manager', 'finance'):
        current = call('GET', '/notifications/preferences', user=actor)
        call('PUT', '/notifications/preferences', {'expectedVersion': current['version'], 'emailEnabled': True, 'enterpriseImEnabled': False}, actor)
    rules = {'zoneId': 'UTC', 'weeklyHours': {day: [{'start': '00:00', 'end': '24:00'}] for day in
        ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY')}, 'overrides': []}
    calendar = call('POST', '/business-calendars', {'key': 'DIRECT_TRACE', 'name': '合成连续日历', 'rules': rules}, 'admin', 201)
    return people, calendar


def definition(runtime, people, calendar, timer=False):
    nodes = [{'id': 'start', 'type': 'START', 'name': '开始', 'properties': {}}]
    if timer: nodes.append({'id': 'wait', 'type': 'TIMER_WAIT', 'name': '等待', 'properties': {'timerDelaySeconds': '1'}})
    properties = {'assigneeRule': 'role:ORG_PERSON_' + people['manager']}
    if not timer:
        properties.update(deadlineCalendarId=calendar['id'], deadlineCalendarRevision=str(calendar['revision']),
                          deadlineWorkingMinutes='1', escalationWorkingMinutes='1', escalationRecipientRule='role:ORG_PERSON_' + people['finance'])
    nodes.extend([{'id': 'review', 'type': 'USER_TASK', 'name': '原人工审批', 'properties': properties},
                  {'id': 'end', 'type': 'END', 'name': '结束', 'properties': {}}])
    graph = {'nodes': nodes, 'edges': [{'id': 'e' + str(i), 'source': one['id'], 'target': two['id'], 'condition': '', 'defaultBranch': False}
             for i, (one, two) in enumerate(zip(nodes, nodes[1:]))]}
    value = runtime.call('POST', '/process-definitions', {'key': 'direct-' + uuid4().hex, 'name': '合成定时来源', 'graph': graph,
                         'formSchema': {'schemaVersion': 2, 'fields': []}}, 'admin')
    return runtime.call('POST', '/process-definitions/' + value['id'] + '/publish?expectedRevision=' + str(value['revision']), {'changeNote': '合成定时来源验收'}, 'admin')


def submit(runtime, definition):
    value = runtime.call('POST', '/applications', {'businessNo': 'DIRECT-' + uuid4().hex, 'title': '合成来源验证',
        'processKey': definition['key'], 'definitionVersion': definition['version'], 'payload': {}}, expected=201)
    path, body, key = '/applications/' + value['id'] + '/submit', {'expectedVersion': value['version']}, str(uuid4())
    result = runtime.call('POST', path, body, key=key)
    return {'application': result, 'trace': runtime.last_trace, 'replay': {'path': path, 'body': body, 'key': key, 'result': result, 'actor': 'alice', 'status': 200}}


def prepare(runtime, people, calendar):
    task_definition = definition(runtime, people, calendar)
    timer_definition = definition(runtime, people, calendar, True)
    grant_path, grant_key = '/organization/approval-proxies', str(uuid4())
    grant_body = {'definitionId': task_definition['id'], 'principalId': people['manager'], 'substituteId': people['finance'],
                  'startsAt': instant(30), 'endsAt': instant(3600), 'reason': '未来合成代理'}
    grant = runtime.call('POST', grant_path, grant_body, 'admin', 201, key=grant_key); grant_trace = runtime.last_trace
    task = submit(runtime, task_definition); timer = submit(runtime, timer_definition)
    task['taskId'] = next(item['taskId'] for item in runtime.call('GET', '/tasks', user='manager') if item['applicationId'] == task['application']['id'])
    timer['jobId'] = runtime.call('GET', '/applications/' + timer['application']['id'] + '/rounds/1/timers')['items'][0]['jobId']
    return {'task': task, 'timer': timer, 'proxyId': grant['proxyId'], 'proxyTrace': grant_trace,
            'proxyReplay': {'path': grant_path, 'body': grant_body, 'key': grant_key, 'result': grant, 'actor': 'admin', 'status': 201}}


def probe(runtime, h2, label):
    """停服后只读来源与实际外发意向；不修改到期、引擎状态或业务记录。"""
    assert runtime.process.poll() is not None
    source = runtime.directory / 'DirectTraceProbe.java'
    source.write_text(r'''import java.sql.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** 只读取固定合成库的来源和通知关联，不包含连接凭据。 */
class DirectTraceProbe {
 public static void main(String[] args) throws Exception {
  var lines=new ArrayList<String>();
  try(var connection=DriverManager.getConnection("jdbc:h2:file:"+args[0]+";IFEXISTS=TRUE;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0;REUSE_SPACE=FALSE","sa","")) {
   var queries=new LinkedHashMap<String,String>();
   try(var tables=connection.getMetaData().getTables(null,"PUBLIC","WORKFLOW_EXECUTION_ORIGIN",null)) {
    if(tables.next()) queries.put("origin","SELECT * FROM workflow_execution_origin");
   }
   queries.put("proxy","SELECT * FROM organization_approval_proxy");
   queries.put("notification","SELECT n.id,n.application_id,n.event_key,n.kind,n.recipient_id,n.actor_id,n.task_id,d.trace_id FROM notification_inbox n JOIN notification_dispatch d ON d.tenant_id=n.tenant_id AND d.inbox_id=n.id");
   for(var query:queries.entrySet()) try(var statement=connection.createStatement();var rows=statement.executeQuery(query.getValue())) {
    int index=0; while(rows.next()) {for(int i=1;i<=rows.getMetaData().getColumnCount();i++) {
     String value=rows.getString(i);lines.add(query.getKey()+"\t"+index+"\t"+rows.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT)+"\t"+(value==null?"-":Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))));
    } index++;}
   }
  } Files.write(Path.of(args[1]),lines);
 }
}
''')
    output = runtime.directory / (label + '-sources.tsv')
    with (runtime.directory / (label + '-probe.log')).open('x') as log:
        subprocess.run([runtime.java, '-Djava.io.tmpdir=/fyoung/tmp', '--class-path', str(h2), str(source),
            str(runtime.directory / 'data/agentflow'), str(output)], check=True, stdout=log, stderr=subprocess.STDOUT, timeout=40)
    result = {'origin': {}, 'proxy': {}, 'notification': {}}
    for line in output.read_text().splitlines():
        table, index, column, raw = line.split('\t'); result[table].setdefault(index, {})[column] = None if raw == '-' else base64.b64decode(raw).decode()
    result = {name: sorted(rows.values(), key=lambda row: json.dumps(row, sort_keys=True)) for name, rows in result.items()}
    save(runtime.directory / (label + '-sources.json'), result); return result


def legacy(kind, identity):
    raw = hashlib.md5((kind + '\0demo\0' + identity).encode()).digest()
    return str(UUID(bytes=raw, version=3))


def origins(data, wave, old=False):
    timer_trace = legacy('timer-wait', wave['timer']['jobId']) if old else wave['timer']['trace']
    task_trace = legacy('task-deadline-reminder', wave['task']['taskId']) if old else wave['task']['trace']
    escalation_trace = legacy('task-escalation', wave['task']['taskId']) if old else wave['task']['trace']
    proxy_trace = legacy('approval-proxy-notification', wave['proxyId'] + ':' + wave['task']['taskId']) if old else wave['proxyTrace']
    notices = data['notification']
    cases = [(wave['timer']['application']['id'], 'TASK_PENDING', 'manager', timer_trace),
             (wave['task']['application']['id'], 'TASK_OVERDUE', 'manager', task_trace),
             (wave['task']['application']['id'], 'TASK_ESCALATED', 'finance', escalation_trace),
             (wave['task']['application']['id'], 'TASK_PENDING', 'finance', proxy_trace)]
    for application, kind, actor, expected in cases:
        selected = [row for row in notices if row['application_id'] == application and row['kind'] == kind and row['recipient_id'] == actor]
        assert len(selected) == 1 and selected[0]['trace_id'] == expected, (kind, actor, selected, expected)
    return len(cases)


def finish(runtime, waves):
    def complete():
        finance = runtime.call('GET', '/notifications?limit=100', user='finance')['items']
        managers = runtime.call('GET', '/notifications?limit=100', user='manager')['items']
        return all(any(item['applicationId'] == wave['task']['application']['id'] and item['kind'] == 'TASK_ESCALATED' for item in finance)
            and any(item['applicationId'] == wave['timer']['application']['id'] and item['kind'] == 'TASK_PENDING' for item in managers) for wave in waves)
    wait_until(complete, 180)


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
        after = split.columns_snapshot(runtime, h2, 'after-upgrade', runtime.directory / 'before-upgrade-columns.tsv'); upgraded = probe(runtime, h2, 'after-upgrade')
        for table, facts in before['tables'].items():
            if table != 'flyway_schema_history':
                assert facts['rows'] == after['tables'][table]['rows'] and facts['sha256'] == after['tables'][table]['originalColumnsSha256'], table
        for table in MIGRATED: assert after['columns'][table.upper()] == before['columns'][table.upper()] + ['TRACE_ID']
        assert upgraded['origin'] == [] and len(upgraded['proxy']) == len(prior['proxy']) == 1
        assert upgraded['proxy'][0]['trace_id'] is None and {k:v for k,v in upgraded['proxy'][0].items() if k != 'trace_id'} == prior['proxy'][0]
        evidence['checks'].append({'name': 'nonempty-upgrade', 'originalTables': len(before['tables']), 'newNullableColumns': 3,
                                  'newOriginTableEmpty': True, 'populatedLegacySources': ['proxy', 'task', 'timer']})
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
        runtime.stop(); completed = probe(runtime, h2, 'completed'); matched = origins(completed, old, True) + origins(completed, new)
        backup = split.columns_snapshot(runtime, h2, 'before-restore')
        evidence['checks'].append({'name': 'restart-real-scheduler-and-replay', 'matchedNotificationOrigins': matched, 'replayedOriginalKeys': replayed,
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
