#!/usr/bin/env python3
"""固定包验证 Agent 原业务定位、真实模型 HTTP、排队后强退和独立恢复。"""

import argparse
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('agent_business_base', ROOT / 'scripts/check-agent-trace-runtime.py')
base = importlib.util.module_from_spec(spec); spec.loader.exec_module(base)
risk, split, save, digest = base.risk, base.split, base.save, base.digest


def source_facts(runtime, h2, label):
    """分别读取单据、轮次和摘要索引，在 Python 中核对；不复用生产候选关联 SQL。"""
    assert runtime.process.poll() is not None
    source = runtime.directory / 'AgentBusinessProbe.java'
    source.write_text(r'''import java.sql.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
class AgentBusinessProbe {
 public static void main(String[] args) throws Exception {
  var lines = new ArrayList<String>();
  try(var connection=DriverManager.getConnection("jdbc:h2:file:"+args[0]+";IFEXISTS=TRUE;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0;REUSE_SPACE=FALSE","sa","")) {
   var queries = Map.of("A","SELECT tenant_id,id,business_no FROM approval_application",
      "R","SELECT tenant_id,application_id,round_no,process_instance_id FROM approval_submission_round",
      "S","SELECT tenant_id,id,application_id,round_no FROM agent_assist_run");
   for(var entry: queries.entrySet()) try(var statement=connection.createStatement();var rows=statement.executeQuery(entry.getValue())) {
    while(rows.next()) {
     var fields = new ArrayList<String>(); fields.add(entry.getKey());
     for(int i=1;i<=rows.getMetaData().getColumnCount();i++) fields.add(Base64.getEncoder().encodeToString(rows.getString(i).getBytes(StandardCharsets.UTF_8)));
     lines.add(String.join("\t", fields));
    }
   }
  }
  Files.write(Path.of(args[1]),lines,StandardCharsets.UTF_8);
 }
}''')
    output = runtime.directory / (label + '-business.tsv')
    with (runtime.directory / (label + '-business-probe.log')).open('x') as log:
        subprocess.run([runtime.java, '-Djava.io.tmpdir=/fyoung/tmp', '--class-path', str(h2), str(source),
                        str(runtime.directory / 'data/agentflow'), str(output)], stdout=log, stderr=subprocess.STDOUT, check=True, timeout=40)
    import base64
    result = {'A': {}, 'R': {}, 'S': {}}
    for line in output.read_text().splitlines():
        kind, *encoded = line.split('\t'); values = [base64.b64decode(value).decode() for value in encoded]
        key = tuple(values[:3] if kind == 'R' else values[:2]); result[kind][key] = values[3:] if kind == 'R' else values[2:]
    return result


def verify_business_logs(runtime, waves, rows, facts):
    log = '\n'.join(path.read_text() for path in sorted(runtime.directory.glob('runtime-*.log')))
    assert base.SENTRY not in log
    expected = []
    for item in waves:
        kind, identity, trace = item['kind'], item['receipt']['id'], item['sourceTrace']
        row = rows[base.TABLES[kind][0]][identity]; tenant = row['tenant_id']
        selected = [line for line in log.splitlines() if 'Agent execution claimed,' in line
                    and 'traceId=' + trace + ' ' in line and 'runId=' + identity in line]
        assert len(selected) == 1, (kind, identity, selected)
        line = selected[0]; assert 'tenantId=' + tenant + ' ' in line
        business = instance = task = ''
        if kind != 'INVOICE':
            if kind == 'SUMMARY':
                application, round_no = facts['S'][(tenant, identity)]
            else:
                application, round_no = row['application_id'], row.get('round_no')
            business = facts['A'][(tenant, application)][0]
            if kind in ('SUMMARY', 'RISK'):
                instance = facts['R'][(tenant, application, round_no)][0]; task = row['task_id']
        for key, value in [('businessNo', business), ('processInstanceId', instance), ('taskId', task)]:
            assert key + '=' + value + ' ' in line, (kind, key, value, line)
        expected.append({'kind': kind, 'runId': identity, 'traceId': trace, 'businessNo': business or None,
                         'processInstanceId': instance or None, 'taskId': task or None})
    save(runtime.directory / 'verified-business-contexts.json', expected)
    return len(expected)


def run(args):
    directory = Path(args.output); assert directory.resolve().is_relative_to(Path('/fyoung/tmp').resolve()) and not directory.exists()
    directory.mkdir(); shutil.copy2(__file__, directory / Path(__file__).name)
    old, new = Path(args.previous_jar).resolve(), Path(args.current_jar).resolve()
    evidence = {'status': 'RUNNING', 'database': 'H2', 'previousJarSha256': digest(old), 'jarSha256': digest(new),
                'browserVerified': False, 'postgresqlVerified': False, 'realEnterpriseModelVerified': False, 'checks': []}
    save(directory / 'evidence.json', evidence)
    sources, model = risk.Sources(directory), base.Model(directory)
    idp = runtime = restored = None
    try:
        idp = risk.IdentityProvider(args.java, directory, new)
        runtime = base.runtime_for(args.java, directory / 'runtime', sources, idp, model)
        runtime.start(old); fixture = risk.setup(runtime, sources); business_before = risk.business_state(runtime, fixture)
        definition = base.basic_definition(runtime, fixture); original = base.queue_wave(runtime, sources, fixture, definition, 'original')
        save(directory / 'original-wave.json', original); assert not model.requests
        runtime.stop(); baseline = split.columns_snapshot(runtime, idp.h2, 'before-upgrade'); original_rows = base.probe(runtime, idp, 'before-upgrade')
        runtime.start(new); runtime.stop()
        assert split.columns_snapshot(runtime, idp.h2, 'after-upgrade') == baseline
        assert base.probe(runtime, idp, 'after-upgrade') == original_rows
        evidence['checks'].append({'name': 'nonempty-upgrade', 'unchangedTables': len(baseline['tables']), 'unchangedOriginalQueues': 6})
        runtime.settings['agentflow.invoices.extraction-worker-enabled'] = True
        runtime.start(new, worker=True); original_results = base.finish(runtime, original, model)
        assert risk.business_state(runtime, fixture) == business_before and len(model.requests) == 6
        runtime.stop(); runtime.settings['agentflow.invoices.extraction-worker-enabled'] = False
        runtime.start(new); current = base.queue_wave(runtime, sources, fixture, definition, 'current')
        save(directory / 'current-wave.json', current); assert len(model.requests) == 6
        runtime.stop(force=True); queued = base.probe(runtime, idp, 'after-queued-kill')
        runtime.settings['agentflow.invoices.extraction-worker-enabled'] = True
        runtime.start(new, worker=True); current_results = base.finish(runtime, current, model)
        assert len(model.requests) == 12 and risk.business_state(runtime, fixture) == business_before
        runtime.stop(); final = base.probe(runtime, idp, 'completed')
        for item in original + current:
            table, identity = base.TABLES[item['kind']][0], item['receipt']['id']
            before, after = queued[table][identity], final[table][identity]
            assert before['trace_id'] == after['trace_id'] == item['sourceTrace']
            for field in ('context_json', 'requester_json', 'sources_json', 'target_digest'):
                if field in before: assert before[field] == after[field]
        matched = verify_business_logs(runtime, original + current, final, source_facts(runtime, idp.h2, 'completed'))
        evidence['checks'].append({'name': 'six-workers-after-process-restart', 'businessContextsVerified': matched,
            'actualModelRequests': 12, 'originalKeyReplays': 12, 'originalApprovalsUnchanged': True,
            'invoiceDoesNotInventWorkflowIdentifiers': True, 'draftsDoNotBorrowAnApprovalInstance': True})
        backup = split.columns_snapshot(runtime, idp.h2, 'before-restore')
        restored = base.runtime_for(args.java, directory / 'restored', sources, idp, model)
        for folder in ('data', 'attachments'): shutil.copytree(runtime.directory / folder, restored.directory / folder)
        restored.settings.update({'agentflow.expenses.precheck-worker-enabled': False, 'agentflow.budgets.worker-enabled': False,
                                  'agentflow.invoices.verification-worker-enabled': False})
        restored.start(new); restored.stop()
        assert split.columns_snapshot(restored, idp.h2, 'after-restore') == backup
        assert base.probe(restored, idp, 'after-restore') == final
        restored.start(new)
        for actor in risk.ROLES: restored.client.login(actor)
        for collection, expected in ((original, original_results), (current, current_results)):
            for item in collection:
                assert restored.call('GET', item['path'] + '/' + item['receipt']['id'], user=item['user']) == expected[item['kind']]
        assert len(model.requests) == 12 and not model.errors and not sources.errors and not sources.base.errors
        restored.stop(); assert base.probe(restored, idp, 'after-restart') == final
        evidence['checks'].append({'name': 'independent-restore', 'tables': len(backup['tables']), 'sameQueueRows': 12,
                                  'sameAuthorizedDetails': 12, 'resentModelRequests': 0})
        evidence.update(status='PASS', boots=runtime.starts + restored.starts, modelRequests=len(model.requests),
                        businessHttpRequests=len(runtime.client.records) + len(restored.client.records),
                        responseTraces=len(runtime.trace_records) + len(restored.trace_records))
        save(directory / 'evidence.json', evidence); print(json.dumps(evidence, ensure_ascii=False), flush=True)
    except Exception as error:
        evidence.update(status='FAILED', failure=repr(error)); save(directory / 'evidence.json', evidence); raise
    finally:
        if restored: restored.stop()
        if runtime: runtime.stop()
        if idp: idp.close()
        model.close(); sources.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    for field in ('java', 'previous-jar', 'current-jar', 'output'): parser.add_argument('--' + field, required=True)
    run(parser.parse_args())
