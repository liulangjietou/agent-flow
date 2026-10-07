#!/usr/bin/env python3
"""财务固定包按原单据与原轮次核对业务日志、网关调用和独立恢复。"""

import argparse
import base64
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('finance_business_base', ROOT / 'scripts/check-finance-trace-runtime.py')
base = importlib.util.module_from_spec(spec); spec.loader.exec_module(base)
risk, split, reporting = base.risk, base.split, base.reporting
save, digest = base.save, base.digest


def source_facts(runtime, h2):
    """分表读取事实，再独立关联；不复用生产候选 SQL，也不从日志反推期望。"""
    assert runtime.process.poll() is not None
    source = runtime.directory / 'FinanceBusinessProbe.java'
    source.write_text(r'''import java.sql.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
class FinanceBusinessProbe {
 public static void main(String[] args) throws Exception {
  var result = new ArrayList<String>();
  try(var connection=DriverManager.getConnection("jdbc:h2:file:"+args[0]+";IFEXISTS=TRUE;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0;REUSE_SPACE=FALSE","sa","")) {
   var queries=Map.of("A","SELECT tenant_id,id,business_no FROM approval_application",
       "R","SELECT tenant_id,application_id,round_no,process_instance_id FROM approval_submission_round",
       "E","SELECT tenant_id,id,application_id FROM expense_report",
       "P","SELECT tenant_id,id,application_id,round_no FROM payment_authorization");
   for(var entry:queries.entrySet()) try(var statement=connection.createStatement();var rows=statement.executeQuery(entry.getValue())) {
    while(rows.next()) {
     var fields=new ArrayList<String>(); fields.add(entry.getKey());
     for(int i=1;i<=rows.getMetaData().getColumnCount();i++) fields.add(Base64.getEncoder().encodeToString(rows.getString(i).getBytes(StandardCharsets.UTF_8)));
     result.add(String.join("\t",fields));
    }
   }
  } Files.write(Path.of(args[1]),result,StandardCharsets.UTF_8);
 }
}''')
    output = runtime.directory / 'business-facts.tsv'
    with (runtime.directory / 'business-probe.log').open('x') as log:
        subprocess.run([runtime.java, '-Djava.io.tmpdir=/fyoung/tmp', '--class-path', str(h2), str(source),
                        str(runtime.directory / 'data/agentflow'), str(output)], stdout=log, stderr=subprocess.STDOUT, check=True, timeout=40)
    facts = {key: {} for key in ('A', 'R', 'E', 'P')}
    for line in output.read_text().splitlines():
        key, *encoded = line.split('\t'); values = [base64.b64decode(value).decode() for value in encoded]
        count = 3 if key == 'R' else 2; facts[key][tuple(values[:count])] = values[count:]
    return facts


def verify_business_logs(runtime, waves, rows, facts):
    lines = '\n'.join(path.read_text() for path in sorted(runtime.directory.glob('runtime-*.log'))).splitlines()
    verified = []
    for wave in waves:
        for kind, item in wave['queues'].items():
            row = rows[base.TABLES[kind][0]][item['id']]; tenant = row['tenant_id']
            matched = [line for line in lines if 'Finance execution claimed,' in line
                       and 'traceId=' + item['sourceTrace'] + ' ' in line
                       and 'source=' + base.TABLES[kind][1] + ',' in line and 'operationId=' + item['id'] in line]
            assert matched, (kind, item['id'])
            business = instance = ''
            if kind != 'INVOICE':
                round_no = None
                if kind == 'BUDGET': application = facts['E'][(tenant, row['report_id'])][0]
                elif kind in ('PAYMENT', 'REQUEST', 'PAYEE'):
                    authorization = row['authorization_id'] if kind == 'REQUEST' else row['original_authorization_id'] if kind == 'PAYEE' else row['id']
                    application, round_no = facts['P'][(tenant, authorization)]
                else: application, round_no = row['application_id'], row.get('round_no')
                business = facts['A'][(tenant, application)][0]
                if round_no is not None: instance = facts['R'][(tenant, application, round_no)][0]
            for line in matched:
                for key, value in [('tenantId', tenant), ('businessNo', business), ('processInstanceId', instance), ('taskId', '')]:
                    assert key + '=' + value + ' ' in line, (kind, key, value, line)
            verified.append({'kind': kind, 'id': item['id'], 'traceId': item['sourceTrace'], 'businessNo': business or None,
                             'processInstanceId': instance or None, 'taskId': None, 'matchedLogs': len(matched)})
    save(runtime.directory / 'verified-business-contexts.json', verified)
    return len(verified)


def run(args):
    directory = Path(args.output); assert directory.resolve().is_relative_to(Path('/fyoung/tmp').resolve()) and not directory.exists()
    directory.mkdir(); shutil.copy2(__file__, directory / Path(__file__).name)
    previous, current = Path(args.previous_jar).resolve(), Path(args.current_jar).resolve()
    sources, peer = reporting.sources_for(directory); records = base.observe_gateway(sources, directory)
    risk.ROLES['admin'].add('FINANCE_CONFIG_ADMIN'); risk.ROLES['bob'].add('CASHIER')
    idp = runtime = restored = None
    evidence = {'status': 'RUNNING', 'database': 'H2', 'previousJarSha256': digest(previous), 'jarSha256': digest(current),
                'browserVerified': False, 'postgresqlVerified': False, 'realFinancialSystemsVerified': False, 'checks': []}
    save(directory / 'evidence.json', evidence)
    try:
        idp = risk.IdentityProvider(args.java, directory, current, financial_reporting=True)
        runtime = base.runtime_for(args.java, directory / 'runtime', sources, idp); base.workers(runtime, True); runtime.start(previous)
        fixture = risk.setup(runtime, sources); fixture['entityId'] = sources.base.entity; reporting.configure_accounts(runtime, fixture)
        prepared = base.prepare_wave(runtime, sources, fixture)
        runtime.stop(); base.workers(runtime, False, parents=True); runtime.start(previous)
        original = base.queue_wave(runtime, sources, fixture, prepared); save(directory / 'original-wave.json', original)
        runtime.stop(); before = split.columns_snapshot(runtime, idp.h2, 'before-upgrade'); old_rows = base.probe(runtime, idp, 'before-upgrade')
        base.workers(runtime, False); runtime.start(current); runtime.stop()
        assert split.columns_snapshot(runtime, idp.h2, 'after-upgrade') == before
        assert base.probe(runtime, idp, 'after-upgrade') == old_rows
        evidence['checks'].append({'name': 'nonempty-upgrade', 'unchangedTables': len(before['tables']), 'unchangedQueueTables': 8})
        marker = len(records); base.workers(runtime, True); runtime.start(current)
        original_results = base.finish(runtime, original)
        # 两个父队列已在旧包真实执行；其派生命令继承来源，原上下文只在新波次核对。
        original_counts = base.check_traces({'queues': {key: value for key, value in original['queues'].items()
                                                       if key not in ('PREPARATION', 'REQUEST')}}, records[marker:], False)
        prepared = base.prepare_wave(runtime, sources, fixture)
        runtime.stop(); base.workers(runtime, False, parents=True); runtime.start(current)
        wave = base.queue_wave(runtime, sources, fixture, prepared); save(directory / 'current-wave.json', wave)
        runtime.stop(force=True); queued_rows = base.probe(runtime, idp, 'after-queued-kill')
        base.workers(runtime, True); runtime.start(current); results = base.finish(runtime, wave)
        counts = base.check_traces(wave, records[marker:], False)
        assert all(base.tracing.TRACE.fullmatch(item['traceId'] or '') for item in records[marker:])
        assert all(value['receivedCommands'] == 1 for value in peer['receipts'].values())
        runtime.stop(); final_rows = base.probe(runtime, idp, 'completed')
        for collection in (original, wave):
            for kind, item in collection['queues'].items():
                table = base.TABLES[kind][0]; before_row, after_row = queued_rows[table][item['id']], final_rows[table][item['id']]
                for key in ('trace_id', 'input_json', 'command_digest'):
                    if key in before_row: assert before_row[key] == after_row[key], (kind, key)
                assert after_row['trace_id'] == item['sourceTrace']
        original_workers = {'queues': {key: value for key, value in original['queues'].items() if key not in ('PREPARATION', 'REQUEST')}}
        contexts = verify_business_logs(runtime, (original_workers, wave), final_rows, source_facts(runtime, idp.h2))
        evidence['checks'].append({'name': 'persisted-worker-recovery', 'businessContextsVerified': contexts, 'queueTables': 8,
                                  'originalRequestCounts': original_counts, 'currentRequestCounts': counts,
                                  'originalKeyReplays': len(original['requests']) + len(wave['requests']),
                                  'independentInvoiceHasNoWorkflowIdentifiers': True, 'financeDoesNotInventApprovalTask': True})
        backup = split.columns_snapshot(runtime, idp.h2, 'before-restore')
        restored = base.runtime_for(args.java, directory / 'restored', sources, idp); base.workers(restored, False)
        for folder in ('data', 'attachments'): shutil.copytree(runtime.directory / folder, restored.directory / folder)
        restored.start(current); restored.stop()
        assert split.columns_snapshot(restored, idp.h2, 'after-restore') == backup
        assert base.probe(restored, idp, 'after-restore') == final_rows
        count_before = len(records); restored.start(current)
        for actor in risk.ROLES: restored.client.login(actor)
        for collection, expected in ((original, original_results), (wave, results)):
            for kind, item in collection['queues'].items():
                assert restored.call('GET', item['path'], user=item['user']) == expected[kind], kind
        assert len(records) == count_before and not sources.errors and not sources.base.errors
        restored.stop(); assert base.probe(restored, idp, 'after-restart') == final_rows
        evidence['checks'].append({'name': 'independent-restore', 'tables': len(backup['tables']), 'sameAuthorizedDetails': 16, 'resentRequests': 0})
        evidence.update(status='PASS', boots=runtime.starts + restored.starts, gatewayRequests=len(records),
                        responseTraces=len(runtime.trace_records) + len(restored.trace_records),
                        businessHttpRequests=len(runtime.client.records) + len(restored.client.records),
                        gatewayCommands=len(peer['receipts']), duplicateCommands=0)
        save(directory / 'evidence.json', evidence); print(json.dumps(evidence, ensure_ascii=False), flush=True)
    except Exception as error:
        evidence.update(status='FAILED', failure=repr(error)); save(directory / 'evidence.json', evidence); raise
    finally:
        if restored: restored.stop()
        if runtime: runtime.stop()
        if idp: idp.close()
        sources.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    for field in ('java', 'previous-jar', 'current-jar', 'output'): parser.add_argument('--' + field, required=True)
    run(parser.parse_args())
