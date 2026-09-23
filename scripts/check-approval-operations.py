#!/usr/bin/env python3
"""独立演示库通过真实会签与重提验证运营口径，只输出业务标识并保留数据。"""
import json
import sys
import uuid
from datetime import datetime, timezone
from urllib.error import HTTPError
from urllib.parse import urlparse
from urllib.request import Request, urlopen

base = sys.argv[1].rstrip('/') if len(sys.argv) > 1 else ''
if urlparse(base).hostname not in ('localhost', '127.0.0.1', '::1') or sys.argv[2:] != ['--exercise']:
    raise SystemExit('Use an isolated loopback demo: check-approval-operations.py http://127.0.0.1:8082 --exercise')
tokens = {}


def request(method, path, user='admin', body=None, expected=200):
    headers = {'Content-Type': 'application/json'}
    if user in tokens:
        headers['Authorization'] = 'Bearer ' + tokens[user]
    if method != 'GET':
        headers['Idempotency-Key'] = str(uuid.uuid4())
    raw = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    try:
        response = urlopen(Request(base + '/api/v1' + path, data=raw, headers=headers, method=method), timeout=20)
    except HTTPError as error:
        response = error
    with response:
        text = response.read()
        value = json.loads(text) if text else None
        assert response.status == expected, (method, path, response.status, value)
        if path.startswith('/operations') and expected == 200:
            assert response.headers.get('Cache-Control') == 'no-store'
        return value


for username in ('admin', 'alice', 'finance'):
    tokens[username] = request('POST', '/auth/login', user='anonymous', body={
        'tenantId': 'demo', 'username': username, 'password': 'demo'})['token']
key = 'operations-' + uuid.uuid4().hex[:10]
graph = {'nodes': [{'id': 'start', 'name': '开始', 'type': 'START', 'properties': {}},
                   {'id': 'review', 'name': '运营财务会签', 'type': 'USER_TASK', 'properties': {'assigneeRule': 'role:FINANCE', 'approvalMode': 'ALL'}},
                   {'id': 'end', 'name': '结束', 'type': 'END', 'properties': {}}],
         'edges': [{'id': 'a', 'source': 'start', 'target': 'review', 'condition': ''}, {'id': 'b', 'source': 'review', 'target': 'end', 'condition': ''}]}
definition = request('POST', '/process-definitions', body={'key': key, 'name': '审批运营真实数据验收', 'graph': graph})
request('POST', f"/process-definitions/{definition['id']}/publish?expectedRevision=0", body={'changeNote': '运营统计真实轮次验收'})


def submit(application=None):
    if application is None:
        application = request('POST', '/applications', user='alice', expected=201, body={
            'businessNo': 'OPS-' + uuid.uuid4().hex[:10], 'title': '审批运营样本', 'processKey': key, 'definitionVersion': 1, 'payload': {}})
    return request('POST', f"/applications/{application['id']}/submit", user='alice', body={'expectedVersion': application['version']})


def act(application, user, action):
    task, = [t for t in request('GET', '/tasks', user=user) if t['applicationId'] == application['id']]
    return request('POST', f"/tasks/{task['taskId']}/actions", user=user,
                   body={'action': action, 'expectedVersion': task['version'], 'comment': '运营口径验收'})


returned = submit()
act(returned, 'finance', 'RETURN')
returned = submit(request('GET', f"/applications/{returned['id']}"))
act(returned, 'finance', 'APPROVE')
act(returned, 'admin', 'APPROVE')
rejected = submit(); act(rejected, 'finance', 'REJECT')
withdrawn = submit()
request('POST', f"/applications/{withdrawn['id']}/withdraw", user='alice', body={'expectedVersion': withdrawn['version'], 'comment': '申请人撤回'})
pending = submit()
path = '/operations/approvals?processKey=' + key
report = request('GET', path)
metrics = report['metrics']
assert {k: metrics[k] for k in ('submittedRounds', 'applications', 'approved', 'returned', 'rejected', 'withdrawn', 'inApproval', 'decidedRounds', 'returnRatePercent', 'durationSamples')} == {
    'submittedRounds': 5, 'applications': 4, 'approved': 1, 'returned': 1, 'rejected': 1, 'withdrawn': 1, 'inApproval': 1,
    'decidedRounds': 3, 'returnRatePercent': 33.3, 'durationSamples': 1}
assert report['pendingTasks'] == 2 and report['waitingNodes'][0]['tasks'] == 2
assert sum(row['submittedRounds'] for row in report['daily']) == 5
assert report['daily'][-1]['date'] == datetime.now(timezone.utc).date().isoformat()
assert report['daily'][-1]['submittedRounds'] == 5
assert report['unrecordedHistoricalRounds'] == 0
for user in ('alice', 'finance'):
    request('GET', path, user=user, expected=403)
request('GET', path, user='anonymous', expected=401)
request('GET', path + '&tenantId=other', expected=400)
assert request('GET', path + '&definitionVersion=2')['metrics']['submittedRounds'] == 0
history = {suffix: request('GET', f"/applications/{returned['id']}/{suffix}") for suffix in ('rounds', 'timeline', 'audit')}
for _ in range(10):
    submit()
bounded = request('GET', path)
assert bounded['pendingTasks'] == 22 and len(bounded['oldestTasks']) == 20 and bounded['moreOldestTasks'] is True
historic = request('GET', path + '&from=2020-01-01&to=2020-01-02')
assert historic['metrics']['submittedRounds'] == 0 and 'returnRatePercent' not in historic['metrics']
assert historic['pendingTasks'] == 22 and len(historic['daily']) == 2
assert history == {suffix: request('GET', f"/applications/{returned['id']}/{suffix}") for suffix in history}
print(json.dumps({'result': 'PASS', 'base': base, 'processKey': key, 'metricsBeforeLimitSamples': metrics,
                  'pendingTasksAfterLimitSamples': bounded['pendingTasks'], 'oldestTasksReturned': len(bounded['oldestTasks']),
                  'resubmittedApplication': returned['id'], 'pendingApplication': pending['id']}, ensure_ascii=False))
