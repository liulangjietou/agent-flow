#!/usr/bin/env python3
"""固定包验收报销恢复来源：四类非空数据升级、真实冲销 HTTP、结算归档及独立恢复。"""
import argparse
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
from uuid import UUID, uuid4

ROOT = Path(__file__).resolve().parents[1]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / path)
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value


base = module('expense_recovery_base', 'check-finance-trace-runtime.py')
peer_module = module('expense_recovery_peer', 'fixtures/ExpenseRecoveryPeer.py')
risk, split, reporting = base.risk, base.split, base.reporting
save, digest, wait_for = base.save, base.digest, base.wait_for
MIGRATED = ['expense_partial_adjustment_preparation', 'expense_partial_adjustment', 'expense_partial_adjustment_operation',
    'expense_resource_adjustment_preparation', 'expense_resource_adjustment', 'expense_settlement', 'expense_budget_review',
    'budget_consumption_reversal_operation', 'voucher_reversal_preparation', 'voucher_reversal_operation']
POPULATED = ['expense_settlement', 'expense_budget_review', 'voucher_reversal_preparation', 'voucher_reversal_operation']
FLAGS = ['agentflow.expenses.settlement', 'agentflow.expenses.archive', 'agentflow.expenses.budget-review',
         'agentflow.vouchers.reversal-execution']


def workers(runtime, enabled, parents=True):
    base.workers(runtime, parents)
    for prefix in FLAGS:
        runtime.settings[prefix + '-worker-enabled'] = enabled
        runtime.settings[prefix + '-poll-delay-ms'] = 200


def detail(runtime, path, user='finance'): return runtime.call('GET', path, user=user)
def archive_path(value): return '/expense-reports/' + value['report']['id'] + '/archive'


def paid(runtime, value, requests=None):
    path = '/cashier/payments/' + value['authorization']
    accounts = runtime.call('GET', path + '/accounts', user='bob'); account = accounts['items'][0]
    body = {'action': 'EXECUTE', 'authorizationVersion': accounts['authorizationVersion'],
        'debitAccountReference': account['reference'], 'debitAccountVersion': account['sourceVersion'], 'comment': '合成恢复付款'}
    request = submit(runtime, path + '/actions', body, 'bob')
    if requests is not None: requests.append(request)
    reporting.payment_state(runtime, value['authorization'], 'SUCCEEDED')
    reporting.payment_voucher(runtime, value['report'])
    value['paymentTrace'] = request['traceId']


def submit(runtime, path, body, user='finance', expected=202):
    key = str(uuid4()); receipt = runtime.call('POST', path, body, user, expected, key=key)
    return {'path': path, 'body': body, 'user': user, 'expected': expected, 'key': key, 'receipt': receipt, 'traceId': runtime.last_trace}


def preparation_input(view):
    return {**{key: view[key] for key in ('roundNo', 'applicationVersion', 'businessVersion', 'operationVersion')},
            'accountingDate': view['original']['accountingDate'], 'evidenceReference': 'synthetic-reversal-evidence', 'comment': '合成核对原件后准备冲销'}


def prepare(runtime, fixture):
    values = {}
    for name in ('PREPARATION', 'OPERATION', 'SETTLEMENT'):
        report = split.draft(runtime, fixture, {'key': 'risk-runtime', 'version': 1}, '100')
        split.submit(runtime, fixture, report)
        for actor in ('manager', 'finance', 'finance'): split.action(runtime, report, actor)
        value = {'report': report, 'authorization': reporting.authorize(runtime, report)}
        original = detail(runtime, '/applications/' + report['applicationId'] + '/vouchers')['operation']
        value['path'] = '/applications/' + report['applicationId'] + '/vouchers/' + original['id'] + '/reversal-execution'
        if name != 'SETTLEMENT':
            paid(runtime, value)
            wait_for(lambda: detail(runtime, archive_path(value)), lambda view: view['status'] == 'ARCHIVED', 45)
        if name == 'OPERATION':
            submit(runtime, value['path'] + '/preparations', preparation_input(detail(runtime, value['path'])))
            ready = wait_for(lambda: detail(runtime, value['path']), lambda view: (view.get('latestPreparation') or {}).get('status') == 'READY', 45)
            assert ready['latestPreparation']['canAuthorize'], ready
        values[name] = value
    values['BUDGET'] = split.draft(runtime, fixture, {'key': 'risk-runtime', 'version': 1}, '100')
    values['budgetInput'] = split.prepare(runtime, fixture, values['BUDGET'])
    return values


def queue(runtime, values):
    requests, items = [], {}
    value = values['PREPARATION']; request = submit(runtime, value['path'] + '/preparations', preparation_input(detail(runtime, value['path'])))
    requests.append(request); items['PREPARATION'] = {'id': request['receipt']['preparationId'], 'traceId': request['traceId'],
        'table': 'voucher_reversal_preparation', 'scope': 'voucher-reversal-preparation', 'path': value['path']}
    value = values['OPERATION']; view = detail(runtime, value['path']); ready = view['latestPreparation']
    body = {**{key: view[key] for key in ('roundNo', 'applicationVersion', 'businessVersion', 'operationVersion')},
            'preparationId': ready['id'], 'preparationVersion': ready['version'], 'comment': '合成明确授权独立冲销'}
    request = submit(runtime, value['path'] + '/authorizations', body); requests.append(request)
    items['OPERATION'] = {'id': request['receipt']['reversalId'], 'traceId': request['traceId'],
        'table': 'voucher_reversal_operation', 'scope': 'voucher-reversal-operation', 'path': value['path']}
    value = values['SETTLEMENT']; paid(runtime, value, requests)
    items['SETTLEMENT'] = {'id': value['report']['id'], 'traceId': value['paymentTrace'], 'table': 'expense_settlement',
        'path': '/expense-reports/' + value['report']['id'] + '/settlement', 'archive': archive_path(value)}
    value = values['BUDGET']; path = '/expense-reports/' + value['id']
    request = submit(runtime, path + '/submit', values['budgetInput'], 'alice', 200); requests.append(request)
    wait_for(lambda: runtime.call('GET', path + '/workflow'), lambda view: view['budget']['confirmedCurrent'], 45)
    items['BUDGET'] = {'id': value['id'], 'traceId': request['traceId'], 'table': 'expense_budget_review', 'path': path + '/budget-review?roundNo=1', 'user': 'alice'}
    return {'requests': requests, 'items': items}


def finish(runtime, wave):
    for request in wave['requests']:
        result = runtime.call('POST', request['path'], request['body'], request['user'], request['expected'], key=request['key'])
        assert result == request['receipt'] and runtime.client.records[-1]['replayed'] == 'true'
    views = {}
    for kind, item in wave['items'].items():
        def ready(view):
            if kind == 'PREPARATION': return (view.get('latestPreparation') or {}).get('status') == 'READY'
            if kind == 'OPERATION': return (view.get('operation') or {}).get('status') == 'POSTED'
            if kind == 'SETTLEMENT': return (view.get('settlement') or {}).get('status') == 'SETTLED'
            return (view.get('details') or {}).get('status') == 'CONFIRMED'
        user = item.get('user', 'finance')
        value = wait_for(lambda: detail(runtime, item['path'], user), ready, 45)
        views[kind] = {'path': item['path'], 'value': value, 'user': user}
        if kind == 'SETTLEMENT':
            archive = wait_for(lambda: detail(runtime, item['archive']), lambda view: view['status'] == 'ARCHIVED', 45)
            views['ARCHIVE'] = {'path': item['archive'], 'value': archive}
    return views


def probe(runtime, h2, label):
    """所有记录按真实租户、主键与轮次区分；停服只读观察，不补建业务状态。"""
    assert runtime.process.poll() is not None
    source, output = runtime.directory / 'ExpenseRecoveryProbe.java', runtime.directory / (label + '-queues.tsv')
    if not source.exists():
        source.write_text('''import java.nio.file.*; import java.nio.charset.StandardCharsets; import java.sql.*; import java.util.*;
/** 只读观察合成队列，包括没有 id 列的结算和复核。 */
class ExpenseRecoveryProbe {
 public static void main(String[] args) throws Exception {
  var lines=new ArrayList<String>();
  try(var connection=DriverManager.getConnection("jdbc:h2:file:"+args[0]+";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;DB_CLOSE_ON_EXIT=FALSE","sa","")) {
   for(String table:args[2].split(",")) {
    if(!table.matches("[a-z_]+")) throw new IllegalArgumentException("Invalid probe table");
    try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT * FROM "+table)) {
     while(rows.next()) {
      var values=new LinkedHashMap<String,String>();
      for(int i=1;i<=rows.getMetaData().getColumnCount();i++) values.put(rows.getMetaData().getColumnName(i).toLowerCase(Locale.ROOT),rows.getString(i));
      String identity=values.get("tenant_id")+"/"+values.getOrDefault("id",values.get("report_id"))+"/"+values.getOrDefault("round_no","");
      for(var value:values.entrySet()) lines.add(table+"\\t"+identity+"\\t"+value.getKey()+"\\t"+(value.getValue()==null?"-":Base64.getEncoder().encodeToString(value.getValue().getBytes(StandardCharsets.UTF_8))));
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
        table, identity, field, value = line.split('\t')
        rows[table].setdefault(identity, {})[field] = None if value == '-' else base64.b64decode(value).decode()
    save(runtime.directory / (label + '-queues.json'), rows); return rows


def row(rows, item):
    values = [value for value in rows[item['table']].values() if value['tenant_id'] == 'demo'
              and value.get('id', value.get('report_id')) == item['id']]
    assert len(values) == 1, (item['table'], item['id'], len(values))
    return values[0]


def traces(wave, records, legacy):
    counts = {}
    for kind in ('PREPARATION', 'OPERATION'):
        item = wave['items'][kind]
        expected = str(UUID(bytes=hashlib.md5((item['scope'] + '\0demo\0' + item['id']).encode()).digest(), version=3)) if legacy else item['traceId']
        selected = [record for record in records if record['traceId'] == expected]
        operation = 'accounting-period' if kind == 'PREPARATION' else 'voucher-reversal-command'
        assert any(record['operation'] == operation for record in selected), (kind, expected, selected)
        if kind == 'OPERATION':
            sent = [record for record in records if record['operation'] == operation and record['body']['data']['command']['id'] == item['id']]
            assert len(sent) == 1 and sent[0]['traceId'] == expected and sent[0]['idempotencyKey'] == item['id']
        counts[kind] = len(selected)
    return counts


def run(args):
    directory = Path(args.output); assert str(directory).startswith('/fyoung/tmp/') and not directory.exists(); directory.mkdir()
    for name in ('check-expense-recovery-trace-runtime.py', 'fixtures/ExpenseRecoveryPeer.py'):
        target = directory / 'scripts' / name; target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / 'scripts' / name, target)
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
        after = split.columns_snapshot(runtime, idp.h2, 'after-upgrade', runtime.directory / 'before-upgrade-columns.tsv')
        upgraded = probe(runtime, idp.h2, 'after-upgrade')
        for table, facts in before['tables'].items():
            if table != 'flyway_schema_history':
                assert facts['rows'] == after['tables'][table]['rows'] and facts['sha256'] == after['tables'][table]['originalColumnsSha256'], table
        for table in MIGRATED:
            assert after['columns'][table.upper()] == before['columns'][table.upper()] + ['TRACE_ID']
            for identity, value in upgraded[table].items():
                assert value['trace_id'] is None and {key: entry for key, entry in value.items() if key != 'trace_id'} == original[table][identity]
        evidence['checks'].append({'name': 'nonempty-upgrade', 'tables': len(before['tables']), 'newNullableColumns': len(MIGRATED), 'populatedQueueTypes': len(POPULATED)})
        marker = len(records); workers(runtime, True); runtime.start(current)
        legacy_views = finish(runtime, legacy); legacy_traces = traces(legacy, records[marker:], True)
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
        evidence['checks'].append({'name': 'origin-recovery-and-replay', 'legacyGatewayCounts': legacy_traces, 'currentGatewayCounts': current_traces,
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
