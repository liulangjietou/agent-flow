#!/usr/bin/env python3
"""固定包核对三类预算队列的非空升级、来源恢复、原键重放及独立恢复。"""

import argparse
import base64
from datetime import datetime, timedelta, timezone
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import threading
from uuid import UUID, uuid4
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / path)
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value)
    return value


diagram = module('review_diagram_runtime', 'check-definition-diagram-runtime.py')
trace = module('review_headers', 'check-agent-trace-runtime.py')
common, split = diagram.common, diagram.split
save, digest = common.save, common.digest
TABLES = {'PRECHECK': ('budget_adjustment_check_job', 'budget-adjustment-check'),
          'REVIEW': ('budget_adjustment_review', 'budget-adjustment-review'),
          'OPERATION': ('budget_adjustment_operation', 'budget-adjustment-operation')}
MIGRATED = [item[0] for item in TABLES.values()] + ['disbursement_return_check', 'repayment_review_check',
    'advance_repayment_check', 'advance_request_check_job', 'expense_payment_return_check',
    'expense_plan_check_job', 'voucher_reversal_check', 'procurement_payment_check_job']


def instant(seconds=0): return (datetime.now(timezone.utc) + timedelta(seconds=seconds)).isoformat().replace('+00:00', 'Z')
def money(value): return {'value': str(value), 'currency': 'CNY'}


class Peer:
    """仅处理合成预算台账与原子指令，接收账本独立于待测应用。"""
    def __init__(self, directory):
        self.directory, self.entity, self.records, self.commands, self.errors = directory, None, [], {}, []
        self.lock = threading.RLock(); peer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_): pass
            def do_POST(self):
                try:
                    body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                    operation = self.path.rsplit('/', 1)[1]
                    with peer.lock:
                        peer.records.append({'operation': operation, 'traceId': self.headers.get('X-Trace-Id'),
                                             'key': self.headers.get('Idempotency-Key'), 'body': body})
                        value = peer.finance(operation, body)
                        save(directory / 'peer-records.json', peer.records); save(directory / 'peer-commands.json', peer.commands)
                    raw = json.dumps({'contractVersion': 1, 'tenantId': 'demo', 'requestId': body['requestId'], 'outcome': 'SUCCESS', 'data': value}).encode()
                    self.send_response(200); self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(raw)))
                    self.end_headers(); self.wfile.write(raw)
                except Exception as error:
                    peer.errors.append(repr(error)); save(directory / 'peer-errors.json', peer.errors)
                    self.send_error(500)

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True); self.thread.start()

    def finance(self, operation, request):
        data = request['data']
        if operation == 'catalog':
            return {'employeeId': 'alice', 'sourceVersion': 'synthetic-budget-v1', 'validUntil': instant(3600),
                    'legalEntities': [{'id': self.entity, 'name': '合成法人', 'baseCurrency': 'CNY', 'paperReceiptRequired': False, 'sourceVersion': 'entity-v1', 'timeZone': 'Asia/Shanghai'}],
                    'categories': [], 'costCenters': [], 'projects': [], 'cities': []}
        if operation == 'budget-ledger':
            return {'request': data, 'sourceVersion': 'ledger-v1', 'observedAt': instant(), 'validUntil': instant(600),
                    'positions': [{'legalEntityId': self.entity, 'reference': reference, 'name': '合成预算', 'version': 'v1',
                                   'periodReference': '2026', 'periodStart': '2026-01-01', 'periodEnd': '2026-12-31', 'periodStatus': 'OPEN',
                                   'limit': money(1000), 'committed': money(300), 'consumed': money(450)} for reference in data['budgetReferences']]}
        if operation == 'budget-adjustment-command':
            command, fingerprint = data['command'], data['commandDigest']; identity = command['id']
            assert self.records[-1]['key'] == identity
            if identity not in self.commands:
                positions = {item['reference']: item for item in command['ledger']['positions']}
                changes = [{'budgetReference': change['budgetReference'], 'beforeVersion': change['expectedVersion'], 'afterVersion': 'v2',
                            'periodReference': positions[change['budgetReference']]['periodReference'], 'accountingDate': command['source']['round']['content']['accountingDate'],
                            'beforeLimit': change['beforeLimit'], 'afterLimit': change['afterLimit'],
                            'committed': positions[change['budgetReference']]['committed'], 'consumed': positions[change['budgetReference']]['consumed']} for change in command['changes']]
                self.commands[identity] = {'command': command, 'received': 0, 'observation': {'operationId': identity, 'commandDigest': fingerprint,
                    'status': 'APPLIED', 'revision': 1, 'observedAt': instant(), 'reference': 'synthetic-' + identity,
                    'appliedAt': command['authorizedAt'], 'changes': changes}}
            stored = self.commands[identity]; assert stored['command'] == command and stored['observation']['commandDigest'] == fingerprint
            stored['received'] += 1
            return stored['observation']
        if operation == 'budget-adjustment-query': return self.commands[data['operationId']]['observation']
        raise AssertionError('Unexpected synthetic operation: ' + operation)

    def close(self): self.server.shutdown(); self.server.server_close(); self.thread.join(timeout=5)


def runtime_for(java, directory, peer):
    directory.mkdir(); runtime = common.Runtime(java, directory, peer)
    runtime.settings.update({'agentflow.assist.enabled': False, 'agentflow.expenses.precheck-worker-enabled': False,
        'agentflow.auth.oidc.enabled': False, 'agentflow.auth.session.jdbc-enabled': False})
    runtime.last_trace, runtime.trace_records = None, []; runtime.http.add_handler(trace.TraceResponse(runtime))
    return runtime


def workers(runtime, enabled):
    for kind in ('precheck', 'review', 'execution'):
        runtime.settings['agentflow.budget-adjustments.' + kind + '-worker-enabled'] = enabled
        runtime.settings['agentflow.budget-adjustments.' + kind + '-poll-delay-ms'] = 200


def start(runtime, jar, login=True):
    runtime.start(jar, login=login)
    if login:
        runtime.tokens['manager'] = runtime.call('POST', '/auth/login', {'tenantId': 'demo', 'username': 'manager', 'password': 'demo'}, user=None)['token']


def setup(runtime, peer):
    call = runtime.call; call('POST', '/organization/initialize', {}, 'admin', 201)
    entity = call('POST', '/organization/units', {'kind': 'LEGAL_ENTITY', 'name': '预算追踪法人', 'active': True}, 'admin', 201)
    peer.entity = entity['id']
    department = call('POST', '/organization/units', {'kind': 'DEPARTMENT', 'name': '预算部门', 'legalEntityId': peer.entity, 'active': True}, 'admin', 201)
    position = call('POST', '/organization/units', {'kind': 'POSITION', 'name': '预算岗位', 'legalEntityId': peer.entity, 'active': True}, 'admin', 201)
    people, appointments = {}, {}
    for actor in ('alice', 'finance', 'manager'):
        person = call('POST', '/organization/people', {'subject': actor, 'displayName': '合成' + actor, 'active': True, 'approvalEligible': actor != 'alice'}, 'admin', 201)
        people[actor] = person['id']
        appointments[actor] = call('POST', '/organization/appointments', {'personId': person['id'], 'departmentId': department['id'], 'positionId': position['id'], 'active': True}, 'admin', 201)['id']
    ids = ['start', 'finance', 'manager', 'end']
    graph = {'nodes': [{'id': name, 'name': name, 'type': 'START' if name == 'start' else 'END' if name == 'end' else 'USER_TASK',
                       'properties': {} if name in ('start', 'end') else {'assigneeRule': 'role:ORG_PERSON_' + people[name]}} for name in ids],
             'edges': [{'id': 'edge' + str(i), 'source': a, 'target': b, 'condition': '', 'defaultBranch': False} for i, (a, b) in enumerate(zip(ids, ids[1:]))]}
    fields = [{'key': 'budgetAdjustmentDetails', 'label': '预算明细', 'type': 'TEXT', 'required': True, 'sensitive': True,
               'nodeAccess': {'finance': 'READ_ONLY', 'manager': 'READ_ONLY'}},
              {'key': 'amount', 'label': '金额', 'type': 'NUMBER', 'required': True}, {'key': 'currency', 'label': '币种', 'type': 'TEXT', 'required': True}]
    draft = call('POST', '/process-definitions', {'key': 'review-trace-budget', 'name': '预算追踪流程', 'graph': graph, 'formSchema': {'schemaVersion': 2, 'fields': fields}}, 'admin')
    definition = call('POST', '/process-definitions/' + draft['id'] + '/publish?expectedRevision=' + str(draft['revision']), {'changeNote': '合成预算来源验收'}, 'admin')
    return {'entityId': peer.entity, 'appointments': appointments, 'definition': definition}


def detail(runtime, value): return runtime.call('GET', '/budget-adjustments/' + value['id'])
def execution(runtime, value): return runtime.call('GET', '/budget-adjustments/' + value['id'] + '/execution', user='finance')


def wait_for(function, predicate): return split.risk.wait_for(function, predicate, 45)


def precheck_input(runtime, fixture, value):
    value = detail(runtime, value); options = runtime.call('GET', '/budget-adjustments/' + value['id'] + '/prechecks/options')
    return {**{key: value[key] for key in ('applicationVersion', 'requestVersion')}, 'initiatorAppointmentId': fixture['appointments']['alice'], 'targetDigest': options['targetDigest']}


def review_input(runtime, value):
    value = detail(runtime, value)
    return {**{key: value[key] for key in ('applicationVersion', 'requestVersion')}, 'roundNo': value['roundNo'], 'comment': '合成原预算核对'}


def authorization_input(runtime, value):
    view = execution(runtime, value)
    return {**review_input(runtime, value), 'reviewId': view['review']['id'], 'reviewVersion': view['review']['version']}


def create(runtime, fixture, approve=False):
    definition, reference = fixture['definition'], uuid4().hex
    value = runtime.call('POST', '/budget-adjustments', {'businessNo': 'TRACE-' + uuid4().hex, 'processKey': definition['key'],
        'definitionVersion': definition['version'], 'content': {'legalEntityId': fixture['entityId'], 'title': '合成预算调拨', 'purpose': 'PRIVATE synthetic budget memo',
        'type': 'TRANSFER', 'accountingDate': '2026-09-29', 'sourceBudgetReference': 'source-' + reference, 'targetBudgetReference': 'target-' + reference, 'amount': money(70)}}, expected=201)
    if not approve: return value
    path = '/budget-adjustments/' + value['id']; body = precheck_input(runtime, fixture, value)
    checked = runtime.call('POST', path + '/prechecks', body, expected=202)
    done = wait_for(lambda: runtime.call('GET', path + '/prechecks/' + checked['id']), lambda item: item['job']['status'] not in ('QUEUED', 'RUNNING'))
    assert done['job']['status'] == 'READY', done
    current = detail(runtime, value)
    runtime.call('POST', path + '/submit', {**{key: current[key] for key in ('applicationVersion', 'requestVersion')}, 'precheckId': checked['id']})
    for actor in ('finance', 'manager'):
        task = next(item for item in runtime.call('GET', '/tasks', user=actor) if item['applicationId'] == value['applicationId'])
        runtime.call('POST', '/tasks/' + task['taskId'] + '/actions', {'action': 'APPROVE', 'expectedVersion': task['version'], 'comment': '批准原预算'}, actor)
    assert detail(runtime, value)['status'] == 'APPROVED'
    return value


def prepare(runtime, fixture):
    values = {kind: create(runtime, fixture, kind != 'PRECHECK') for kind in TABLES}
    value = values['OPERATION']
    runtime.call('POST', '/budget-adjustments/' + value['id'] + '/execution/reviews', review_input(runtime, value), 'finance', 202)
    ready = wait_for(lambda: execution(runtime, value), lambda item: item.get('review', {}).get('status') not in ('QUEUED', 'RUNNING'))
    assert ready['review']['status'] == 'READY', ready
    return values


def queue(runtime, fixture, values):
    result = {}
    for kind, value in values.items():
        path = '/budget-adjustments/' + value['id'] + ('/prechecks' if kind == 'PRECHECK' else '/execution/reviews' if kind == 'REVIEW' else '/execution/authorizations')
        body = precheck_input(runtime, fixture, value) if kind == 'PRECHECK' else review_input(runtime, value) if kind == 'REVIEW' else authorization_input(runtime, value)
        actor, key = ('alice' if kind == 'PRECHECK' else 'finance'), str(uuid4())
        receipt = runtime.call('POST', path, body, actor, 202, key=key)
        identity = receipt['id' if kind == 'PRECHECK' else 'reviewId' if kind == 'REVIEW' else 'operationId']
        result[kind] = {'id': identity, 'value': value, 'path': path, 'body': body, 'actor': actor, 'key': key, 'receipt': receipt, 'traceId': runtime.last_trace}
    return result


def finish(runtime, wave):
    result = {}
    for kind, item in wave.items():
        if kind == 'PRECHECK':
            path = '/budget-adjustments/' + item['value']['id'] + '/prechecks/' + item['id']
            value = wait_for(lambda: runtime.call('GET', path), lambda value: value['job']['status'] not in ('QUEUED', 'RUNNING'))
            assert value['job']['status'] == 'READY', value
        else:
            path = '/budget-adjustments/' + item['value']['id'] + '/execution'; field = 'review' if kind == 'REVIEW' else 'operation'
            value = wait_for(lambda: execution(runtime, item['value']), lambda value: value[field]['status'] not in ('QUEUED', 'RUNNING', 'EXECUTING'))
            assert value[field]['status'] == ('READY' if kind == 'REVIEW' else 'APPLIED'), value
        assert runtime.call('POST', item['path'], item['body'], item['actor'], 202, key=item['key']) == item['receipt']
        assert runtime.records[-1]['replayed'] == 'true'
        result[kind] = {'path': path, 'actor': item['actor'], 'value': value}
    return result


def probe(runtime, h2, label):
    assert runtime.process.poll() is not None
    source, output = runtime.directory / 'ReviewQueueProbe.java', runtime.directory / (label + '-queues.tsv')
    if not source.exists():
        source.write_text('''import java.nio.file.*; import java.nio.charset.StandardCharsets; import java.sql.*; import java.util.*;
/** 停服后只读提取合成队列的原列，不修改验收数据库。 */
class ReviewQueueProbe {
 public static void main(String[] args) throws Exception {
  var lines = new ArrayList<String>();
  try(var connection=DriverManager.getConnection("jdbc:h2:file:"+args[0]+";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;DB_CLOSE_ON_EXIT=FALSE","sa","")) {
   for(String table:args[2].split(",")) {
    if(!table.matches("[a-z_]+")) throw new IllegalArgumentException("Invalid probe table");
    try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT * FROM "+table)) {
     while(rows.next()) for(int i=1;i<=rows.getMetaData().getColumnCount();i++) {
      String value=rows.getString(i); lines.add(table+"\\t"+rows.getString("id")+"\\t"+rows.getMetaData().getColumnName(i).toLowerCase(Locale.ROOT)+"\\t"+(value==null?"-":Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))));
     }
    }
   }
  } Files.write(Path.of(args[1]),lines,StandardCharsets.UTF_8);
 }
}''')
    with (runtime.directory / (label + '-probe.log')).open('x') as log:
        subprocess.run([runtime.java, '-Djava.io.tmpdir=/fyoung/tmp', '--class-path', str(h2), str(source),
            str(runtime.directory / 'data/agentflow'), str(output), ','.join(MIGRATED)], check=True, stdout=log, stderr=subprocess.STDOUT, timeout=40)
    rows = {table: {} for table in MIGRATED}
    for line in output.read_text().splitlines():
        table, identity, field, value = line.split('\t'); rows[table].setdefault(identity, {})[field] = None if value == '-' else base64.b64decode(value).decode()
    save(runtime.directory / (label + '-queues.json'), rows)
    return rows


def traces(wave, records, legacy):
    counts = {}
    for kind, item in wave.items():
        expected = str(UUID(bytes=hashlib.md5((TABLES[kind][1] + '\0demo\0' + item['id']).encode()).digest(), version=3)) if legacy else item['traceId']
        matches = [value for value in records if value['traceId'] == expected]
        assert matches, (kind, expected)
        assert all('traceId' not in value['body'] for value in matches)
        counts[kind] = len(matches)
    return counts


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
        after = split.columns_snapshot(runtime, h2, 'after-upgrade', runtime.directory / 'before-upgrade-columns.tsv'); upgraded = probe(runtime, h2, 'after-upgrade')
        for table, facts in before['tables'].items():
            if table != 'flyway_schema_history':
                assert facts['rows'] == after['tables'][table]['rows'] and facts['sha256'] == after['tables'][table]['originalColumnsSha256'], table
        for table in MIGRATED:
            assert after['columns'][table.upper()] == before['columns'][table.upper()] + ['TRACE_ID']
            for identity, row in upgraded[table].items():
                assert row['trace_id'] is None and {k: v for k, v in row.items() if k != 'trace_id'} == original[table][identity]
        evidence['checks'].append({'name': 'nonempty-upgrade', 'tables': len(before['tables']), 'newNullableColumns': 11, 'populatedQueueTypes': 3})
        marker = len(peer.records); workers(runtime, True); start(runtime, current)
        legacy_results = finish(runtime, legacy); legacy_traces = traces(legacy, peer.records[marker:], True)
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
        evidence['checks'].append({'name': 'origin-recovery-and-replay', 'queueTypes': 3, 'legacyGatewayCounts': legacy_traces,
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
