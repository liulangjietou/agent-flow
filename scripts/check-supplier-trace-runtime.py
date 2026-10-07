#!/usr/bin/env python3
"""固定包验收供应商队列来源、派生队列、非空升级、停机恢复和命令去重。"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
from uuid import UUID, uuid4
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / path)
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value


base = module('supplier_runtime_base', 'check-financial-review-trace-runtime.py')
peer_module = module('supplier_trace_peer', 'fixtures/SupplierTracePeer.py')
save, digest, wait_for = base.save, base.digest, base.wait_for
TABLES = {'REVIEW': ('supplier_payable_review', 'supplier-payable-review'),
          'HOLD': ('supplier_payable_hold_operation', 'supplier-payable-hold'),
          'REQUEST': ('supplier_payment_execution_request', 'supplier-payment-execution'),
          'RETURN': ('supplier_payment_return_check', 'supplier-payment-return'),
          'SETTLEMENT_PREPARATION': ('supplier_settlement_preparation', 'supplier-settlement-preparation'),
          'ADJUSTMENT_PREPARATION': ('supplier_adjustment_preparation', 'supplier-adjustment-preparation')}
DERIVED = {'REQUEST': 'supplier_payment_operation', 'SETTLEMENT_PREPARATION': 'supplier_payable_settlement_operation',
           'ADJUSTMENT_PREPARATION': 'supplier_payable_adjustment_operation'}
MIGRATED = [value[0] for value in TABLES.values()] + list(DERIVED.values())


def runtime_for(java, directory, peer): return base.runtime_for(java, directory, peer)
def probe(runtime, h2, label): return base.probe(runtime, h2, label, MIGRATED)


def workers(runtime, enabled):
    for name in ('review', 'hold', 'execution', 'payment', 'return', 'settlement-preparation', 'settlement', 'adjustment-preparation', 'adjustment'):
        runtime.settings['agentflow.supplier-payments.' + name + '-worker-enabled'] = enabled
        runtime.settings['agentflow.supplier-payments.' + name + '-poll-delay-ms'] = 200
    runtime.settings['agentflow.procurement-payments.precheck-worker-enabled'] = enabled
    runtime.settings['agentflow.procurement-payments.precheck-poll-delay-ms'] = 200


def start(runtime, jar, login=True):
    base.start(runtime, jar, login)
    if login: runtime.tokens['cashier'] = runtime.call('POST', '/auth/login', {'tenantId': 'demo', 'username': 'cashier', 'password': 'demo'}, user=None)['token']


def setup(runtime, peer):
    call = runtime.call; call('POST', '/organization/initialize', {}, 'admin', 201)
    entity = call('POST', '/organization/units', {'kind': 'LEGAL_ENTITY', 'name': '供应商追踪法人', 'active': True}, 'admin', 201)
    peer.entity = entity['id']
    department = call('POST', '/organization/units', {'kind': 'DEPARTMENT', 'name': '采购部门', 'legalEntityId': peer.entity, 'active': True}, 'admin', 201)
    position = call('POST', '/organization/units', {'kind': 'POSITION', 'name': '采购岗位', 'legalEntityId': peer.entity, 'active': True}, 'admin', 201)
    people, appointments = {}, {}
    for actor in ('alice', 'finance', 'manager', 'cashier'):
        person = call('POST', '/organization/people', {'subject': actor, 'displayName': '合成' + actor, 'active': True, 'approvalEligible': actor != 'alice'}, 'admin', 201)
        people[actor] = person['id']
        appointments[actor] = call('POST', '/organization/appointments', {'personId': person['id'], 'departmentId': department['id'], 'positionId': position['id'], 'active': True}, 'admin', 201)['id']
    ids = ['start', 'finance', 'manager', 'end']
    graph = {'nodes': [{'id': name, 'name': name, 'type': 'START' if name == 'start' else 'END' if name == 'end' else 'USER_TASK',
                       'properties': {} if name in ('start', 'end') else {'assigneeRule': 'role:ORG_PERSON_' + people[name]}} for name in ids],
             'edges': [{'id': 'edge' + str(i), 'source': a, 'target': b, 'condition': '', 'defaultBranch': False} for i, (a, b) in enumerate(zip(ids, ids[1:]))]}
    fields = [{'key': 'procurementPaymentDetails', 'label': '采购明细', 'type': 'TEXT', 'required': True, 'sensitive': True,
               'nodeAccess': {'finance': 'READ_ONLY', 'manager': 'READ_ONLY'}},
              {'key': 'amount', 'label': '金额', 'type': 'NUMBER', 'required': True}, {'key': 'currency', 'label': '币种', 'type': 'TEXT', 'required': True}]
    draft = call('POST', '/process-definitions', {'key': 'supplier-trace', 'name': '供应商追踪流程', 'graph': graph, 'formSchema': {'schemaVersion': 2, 'fields': fields}}, 'admin')
    definition = call('POST', '/process-definitions/' + draft['id'] + '/publish?expectedRevision=' + str(draft['revision']), {'changeNote': '合成供应商来源验收'}, 'admin')
    return {'entityId': peer.entity, 'appointments': appointments, 'definition': definition}


def detail(runtime, request): return runtime.call('GET', '/procurement-payments/' + request)
def finance_path(request): return '/procurement-payments/' + request + '/supplier-payment'
def finance(runtime, request): return runtime.call('GET', finance_path(request), user='finance')
def cashier_path(payment): return '/cashier/supplier-payments/' + payment
def supplier_path(payment, suffix): return '/supplier-payments/' + payment + '/' + suffix


def review_input(runtime, request):
    view = detail(runtime, request)
    return {**{key: view[key] for key in ('applicationVersion', 'requestVersion', 'roundNo')}, 'comment': '合成财务复核'}


def create(runtime, fixture):
    definition = fixture['definition']
    view = runtime.call('POST', '/procurement-payments', {'businessNo': 'TRACE-' + uuid4().hex, 'processKey': definition['key'],
        'definitionVersion': definition['version'], 'content': {'legalEntityId': fixture['entityId'], 'title': '合成设备采购', 'purpose': '合成采购付款',
        'supplierReference': 'supplier-1', 'payableReference': 'payable-' + uuid4().hex, 'amount': base.money(70)}}, expected=201)
    request = view['id']; path = '/procurement-payments/' + request; options = runtime.call('GET', path + '/prechecks/options')
    checked = runtime.call('POST', path + '/prechecks', {**{key: view[key] for key in ('applicationVersion', 'requestVersion')},
        'initiatorAppointmentId': fixture['appointments']['alice'], 'targetDigest': options['targetDigest']}, expected=202)
    done = wait_for(lambda: runtime.call('GET', path + '/prechecks/' + checked['id']), lambda item: item['job']['status'] not in ('QUEUED', 'RUNNING'))
    assert done['job']['status'] == 'READY', done
    view = detail(runtime, request)
    runtime.call('POST', path + '/submit', {**{key: view[key] for key in ('applicationVersion', 'requestVersion')}, 'precheckId': checked['id']})
    for actor in ('finance', 'manager'):
        task = next(item for item in runtime.call('GET', '/tasks', user=actor) if item['applicationId'] == view['applicationId'])
        runtime.call('POST', '/tasks/' + task['taskId'] + '/actions', {'action': 'APPROVE', 'expectedVersion': task['version'], 'comment': '批准原应付'}, actor)
    assert detail(runtime, request)['status'] == 'APPROVED'
    return request


def queue_review(runtime, request):
    runtime.call('POST', finance_path(request) + '/reviews', review_input(runtime, request), 'finance', 202)
    view = wait_for(lambda: finance(runtime, request), lambda v: v.get('review') and v['review']['status'] not in ('QUEUED', 'RUNNING'))
    assert view['review']['status'] == 'READY', view
    return view


def authorization_input(runtime, request):
    view = finance(runtime, request)
    return {**review_input(runtime, request), 'reviewId': view['review']['id'], 'reviewVersion': view['review']['version']}


def held(runtime, request):
    queue_review(runtime, request)
    value = runtime.call('POST', finance_path(request) + '/authorizations', authorization_input(runtime, request), 'finance', 202)
    view = wait_for(lambda: finance(runtime, request), lambda v: v.get('hold') and v['hold']['status'] not in ('QUEUED', 'RESERVING'))
    assert view['hold']['status'] == 'HELD', view
    return value['authorizationId']


def execute_input(runtime, payment):
    view = runtime.call('GET', cashier_path(payment), user='cashier')
    return {'action': 'EXECUTE', 'holdVersion': view['hold']['version'], 'debitAccountReference': 'debit-1', 'debitAccountVersion': 'debit-v1', 'comment': '核对本次付款'}


def paid(runtime, fixture):
    request = create(runtime, fixture); payment = held(runtime, request)
    runtime.call('POST', cashier_path(payment) + '/actions', execute_input(runtime, payment), 'cashier', 202)
    view = wait_for(lambda: runtime.call('GET', cashier_path(payment), user='cashier'), lambda v: v.get('operation') and v['operation']['status'] not in ('QUEUED', 'CHECKING', 'EXECUTING'))
    assert view['operation']['status'] == 'SUCCEEDED', view
    return {'request': request, 'payment': payment}


def return_input(runtime, payment):
    view = runtime.call('GET', supplier_path(payment, 'returns'), user='finance')
    return {**{key: view[key] for key in ('operationVersion', 'returnVersion')}, 'comment': '核对原银行回款'}


def register_returns(runtime, payment):
    path = supplier_path(payment, 'returns')
    runtime.call('POST', path + '/checks', return_input(runtime, payment), 'finance', 202)
    view = wait_for(lambda: runtime.call('GET', path, user='finance'), lambda v: v.get('latestCheck') and v['latestCheck']['status'] not in ('QUEUED', 'RUNNING'))
    assert view['latestCheck']['status'] == 'CHECKED', view
    runtime.call('POST', path + '/registrations', {**{key: view[key] for key in ('operationVersion', 'returnVersion')},
        'checkId': view['latestCheck']['id'], 'checkVersion': view['latestCheck']['version'], 'outcome': view['latestCheck']['evidence']['outcome'],
        'evidenceReference': 'synthetic-return-proof', 'comment': '确认公司已收款'}, 'finance', 202)


def prepare(runtime, fixture):
    values = {kind: {'request': create(runtime, fixture)} if kind in ('REVIEW', 'HOLD', 'REQUEST') else paid(runtime, fixture) for kind in TABLES}
    queue_review(runtime, values['HOLD']['request'])
    values['REQUEST']['payment'] = held(runtime, values['REQUEST']['request'])
    register_returns(runtime, values['ADJUSTMENT_PREPARATION']['payment'])
    return values


def queue(runtime, values):
    wave = {}
    for kind, value in values.items():
        actor = 'finance'; request = value['request']
        if kind in ('REVIEW', 'HOLD'):
            path = finance_path(request) + ('/reviews' if kind == 'REVIEW' else '/authorizations')
            body = review_input(runtime, request) if kind == 'REVIEW' else authorization_input(runtime, request)
            id_field = 'reviewId' if kind == 'REVIEW' else 'authorizationId'
        elif kind == 'REQUEST':
            path = cashier_path(value['payment']) + '/actions'; actor = 'cashier'; body = execute_input(runtime, value['payment']); id_field = 'preparationId'
        elif kind == 'RETURN':
            path = supplier_path(value['payment'], 'returns/checks'); body = return_input(runtime, value['payment']); id_field = 'checkId'
        else:
            adjustment = kind == 'ADJUSTMENT_PREPARATION'; name = 'adjustment' if adjustment else 'settlement'
            view = runtime.call('GET', supplier_path(value['payment'], name + 's'), user='finance')
            path = supplier_path(value['payment'], name + '-preparations'); id_field = 'preparationId'
            body = {'paymentVersion': view['bank']['version'], 'accountingDate': view['minimumAccountingDate'], 'comment': '核对原银行和记账日期'}
            if adjustment: body['returnVersion'] = view['returnVersion']
        key = str(uuid4()); receipt = runtime.call('POST', path, body, actor, 202, key=key)
        wave[kind] = {'id': receipt[id_field], 'value': value, 'path': path, 'body': body, 'actor': actor, 'key': key, 'receipt': receipt, 'traceId': runtime.last_trace}
    return wave


def finish(runtime, wave):
    results = {}
    for kind, item in wave.items():
        value, actor = item['value'], item['actor']
        if kind in ('REVIEW', 'HOLD'): path, field, expected = finance_path(value['request']), 'review' if kind == 'REVIEW' else 'hold', 'READY' if kind == 'REVIEW' else 'HELD'
        elif kind == 'REQUEST': path, field, expected = cashier_path(value['payment']), 'operation', 'SUCCEEDED'
        elif kind == 'RETURN': path, field, expected = supplier_path(value['payment'], 'returns'), 'latestCheck', 'CHECKED'
        else: path, field, expected = supplier_path(value['payment'], 'adjustments' if kind == 'ADJUSTMENT_PREPARATION' else 'settlements'), 'completion', None
        result = wait_for(lambda: runtime.call('GET', path, user=actor), lambda v: v.get(field) and (expected is None or v[field].get('status') == expected))
        assert runtime.call('POST', item['path'], item['body'], actor, 202, key=item['key']) == item['receipt']
        assert runtime.records[-1]['replayed'] == 'true'
        results[kind] = {'path': path, 'actor': actor, 'value': result}
    return results


def origin(kind, item, legacy):
    return str(UUID(bytes=hashlib.md5((TABLES[kind][1] + '\0demo\0' + item['id']).encode()).digest(), version=3)) if legacy else item['traceId']


def check_traces(wave, records, legacy, rows):
    counts = {}
    for kind, item in wave.items():
        trace_id = origin(kind, item, legacy); matches = [record for record in records if record['traceId'] == trace_id]
        assert matches, (kind, trace_id)
        assert all('traceId' not in record['body'] for record in matches)
        assert rows[TABLES[kind][0]][item['id']]['trace_id'] == (None if legacy else trace_id)
        if kind in DERIVED:
            identity = item['value']['payment'] if kind == 'REQUEST' else item['id']
            assert rows[DERIVED[kind]][identity]['trace_id'] == trace_id
        counts[kind] = len(matches)
    return counts


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
        after = base.split.columns_snapshot(runtime, h2, 'after-upgrade', runtime.directory / 'before-upgrade-columns.tsv'); upgraded = probe(runtime, h2, 'after-upgrade')
        for table, facts in before['tables'].items():
            if table != 'flyway_schema_history':
                assert facts['rows'] == after['tables'][table]['rows'] and facts['sha256'] == after['tables'][table]['originalColumnsSha256'], table
        for table in MIGRATED:
            assert after['columns'][table.upper()] == before['columns'][table.upper()] + ['TRACE_ID']
            for identity, row in upgraded[table].items():
                assert row['trace_id'] is None and {k: v for k, v in row.items() if k != 'trace_id'} == original[table][identity]
        evidence['checks'].append({'name': 'nonempty-upgrade', 'tables': len(before['tables']), 'newNullableColumns': len(MIGRATED),
                                   'populatedQueueTypes': sum(bool(value) for value in original.values())})
        marker = len(peer.records); workers(runtime, True); start(runtime, current)
        legacy_results = finish(runtime, legacy); legacy_calls = peer.records[marker:]
        values = prepare(runtime, fixture)
        runtime.stop(); legacy_rows = probe(runtime, h2, 'legacy-completed'); legacy_counts = check_traces(legacy, legacy_calls, True, legacy_rows)
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
        evidence['checks'].append({'name': 'origin-recovery-and-replay', 'parentQueueTypes': 6, 'derivedQueueTypes': 3,
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
