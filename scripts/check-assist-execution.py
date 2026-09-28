"""在独立回环演示实例验收真实 HTTP 摘要、来源和人工复核，保留浏览器用合成申请。"""
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

if len(sys.argv) != 3 or sys.argv[2] != '--exercise':
    raise SystemExit('Usage: check-assist-execution.py http://127.0.0.1:18210 --exercise')
origin = urllib.parse.urlsplit(sys.argv[1])
if origin.hostname not in ('127.0.0.1', 'localhost', '::1') or origin.scheme != 'http' or origin.username or origin.query or origin.fragment:
    raise SystemExit('Only an explicitly selected loopback demo is supported')
BASE = sys.argv[1].rstrip('/') + '/api/v1'
PREFIX = 'assist-' + uuid.uuid4().hex[:10]
tokens = {}
count = 0


def request(method, path, user=None, body=None, expected=200, key=None):
    global count
    headers = {'Content-Type': 'application/json'}
    if user:
        headers['Authorization'] = 'Bearer ' + tokens[user]
    if method != 'GET' and not path.startswith('/auth/'):
        headers['Idempotency-Key'] = key or str(uuid.uuid4())
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        count += 1
        value = json.loads(response.read())
        assert response.status == expected, (method, path, response.status, value)
        if method == 'GET' and '/assist-runs' in path and expected == 200:
            assert response.headers['Cache-Control'] == 'no-store'
        return value


for user in ('admin', 'alice', 'manager', 'bob'):
    tokens[user] = request('POST', '/auth/login', body={'tenantId': 'demo', 'username': user, 'password': 'demo'})['token']

graph = {'nodes': [
    {'id': 'start', 'name': '开始', 'type': 'START', 'properties': {}},
    {'id': 'review', 'name': '主管审批', 'type': 'USER_TASK', 'properties': {'assigneeRule': 'user:manager'}},
    {'id': 'end', 'name': '结束', 'type': 'END', 'properties': {}}],
    'edges': [{'id': 'e1', 'source': 'start', 'target': 'review', 'condition': ''},
              {'id': 'e2', 'source': 'review', 'target': 'end', 'condition': ''}]}
schema = {'schemaVersion': 2, 'fields': [
    {'key': 'reason', 'label': '采购说明', 'type': 'TEXTAREA', 'required': True},
    {'key': 'secret', 'label': '内部资料', 'type': 'TEXT', 'required': False, 'nodeAccess': {'review': 'HIDDEN'}},
    {'key': 'account', 'label': '敏感账户', 'type': 'TEXT', 'required': False, 'sensitive': True},
    {'key': 'proof', 'label': '采购附件', 'type': 'ATTACHMENT', 'required': False}]}
definition = request('POST', '/process-definitions', 'admin', {'key': PREFIX, 'name': 'Agent 摘要验收', 'graph': graph, 'formSchema': schema})
request('POST', f"/process-definitions/{definition['id']}/publish?expectedRevision={definition['revision']}", 'admin', {'changeNote': '合成模型与人工复核验收'})


def prepare(suffix):
    app = request('POST', '/applications', 'alice', {'businessNo': PREFIX + suffix, 'processKey': PREFIX, 'definitionVersion': 1,
        'title': '研发设备采购 · 摘要核对', 'payload': {'reason': '采购两台开发工作站，用于已批准的研发项目。预计本月交付，依据采购清单逐项核对。',
        'secret': '合成内部资料，禁止向模型发送', 'account': '合成敏感账户，禁止向模型发送'}}, expected=201)
    request('POST', f"/applications/{app['id']}/submit", 'alice', {'expectedVersion': 1})
    return app['id']


app_id = prepare('-http')
path = f'/applications/{app_id}'
task = next(task for task in request('GET', '/tasks', 'manager') if task['applicationId'] == app_id)
input_path = path + '/assist-runs/input?taskId=' + task['taskId']
options = request('GET', input_path, 'manager')
assert options['enabled'], 'Configure the loopback synthetic model for this acceptance test'
assert {v['reference']['sourceId'] for v in options['sources']} == {'application:title', 'form:reason'}
request('GET', input_path, 'admin', expected=403)
request('GET', input_path, 'alice', expected=403)
body = {'taskId': task['taskId'], 'expectedVersion': 2, 'targetDigest': options['targetDigest'], 'sourceIds': ['form:reason']}
request('POST', path + '/assist-runs', 'manager', {**body, 'sourceIds': ['form:secret']}, expected=403)
key = str(uuid.uuid4())
queued = request('POST', path + '/assist-runs', 'manager', body, expected=202, key=key)
assert request('POST', path + '/assist-runs', 'manager', body, expected=202, key=key) == queued
run_path = path + '/assist-runs/' + queued['id']
deadline = time.monotonic() + 20
while True:
    run = request('GET', run_path, 'manager')
    if run['status'] not in ('QUEUED', 'RUNNING'):
        break
    assert time.monotonic() < deadline, 'Persisted worker did not finish within the fixture deadline'
    time.sleep(0.2)
assert run['status'] == 'COMPLETED', run
assert run['suggestion']['modelVersion'] == 'loopback-synthetic-model-v1', 'This script requires the documented synthetic fixture'
assert {v['sourceId'] for v in run['inputReferences']} == {'form:reason'}
assert all(v in run['inputReferences'] for claim in run['suggestion']['claims'] for v in claim['evidence'])
review = {'taskId': task['taskId'], 'expectedVersion': 2, 'expectedRunVersion': 3, 'action': 'ADOPT', 'acceptedText': '人工核对：采购两台工作站，交付资料仍需在审批前确认。', 'comment': '已逐项核对原文及所选来源'}
request('POST', run_path + '/review', 'admin', review, expected=403)
before = request('GET', path, 'alice')
review_key = str(uuid.uuid4())
accepted = request('POST', run_path + '/review', 'manager', review, key=review_key)
assert request('POST', run_path + '/review', 'manager', review, key=review_key) == accepted
assert accepted['status'] == 'ADOPTED'
reviewed = request('GET', run_path, 'manager')
assert reviewed['suggestion'] == run['suggestion']
assert reviewed['review']['acceptedText'] == review['acceptedText']
assert request('GET', path, 'alice') == before
assert any(t['taskId'] == task['taskId'] for t in request('GET', '/tasks', 'manager'))
request('GET', run_path, 'bob', expected=404)
browser_id = prepare('-browser')
print(json.dumps({'result': 'PASS', 'httpRequests': count, 'applicationId': app_id, 'runId': queued['id'], 'browserApplicationId': browser_id,
                  'definitionId': definition['id'], 'processKey': PREFIX, 'fixtureOnly': True, 'applicationVersionAfterReview': before['version']}, ensure_ascii=False, indent=2))
