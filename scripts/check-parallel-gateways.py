#!/usr/bin/env python3
"""在明确指定的独立本地环境验证并行审批，保留测试记录，不打印会话令牌。"""
import copy
import json
import sys
import uuid
from urllib.error import HTTPError
from urllib.parse import urlparse
from urllib.request import Request, urlopen

base = sys.argv[1].rstrip('/') if len(sys.argv) > 1 else ''
endpoint = urlparse(base)
if (endpoint.scheme != 'http' or endpoint.hostname not in ('localhost', '127.0.0.1', '::1')
        or endpoint.port is None or endpoint.port in (8080, 8180, 5193) or endpoint.path
        or endpoint.username or endpoint.password or endpoint.query or endpoint.fragment or sys.argv[2:] != ['--exercise']):
    raise SystemExit('Use an isolated demo: check-parallel-gateways.py http://127.0.0.1:18180 --exercise')
tokens = {}


def request(method, path, user='admin', body=None, expected=200, key=None):
    headers = {'Content-Type': 'application/json'}
    if user in tokens:
        headers['Authorization'] = 'Bearer ' + tokens[user]
    if method != 'GET':
        headers['Idempotency-Key'] = key or str(uuid.uuid4())
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    try:
        response = urlopen(Request(base + '/api/v1' + path, data=data, headers=headers, method=method), timeout=20)
    except HTTPError as error:
        response = error
    with response:
        result = json.loads(response.read())
        assert response.status == expected, (method, path, response.status, result)
        return result


def graph():
    nodes = [('start', '开始', 'START', 40, 180, None), ('fork', '并行拆分', 'PARALLEL_GATEWAY', 220, 180, None),
             ('manager', '主管审批', 'USER_TASK', 420, 80, 'manager'), ('finance', '财务审批', 'USER_TASK', 420, 300, 'finance'),
             ('join', '全部汇合', 'PARALLEL_GATEWAY', 640, 180, None), ('final', '最终审批', 'USER_TASK', 840, 180, 'admin'),
             ('end', '结束', 'END', 1040, 180, None)]
    return {'conditionLanguageVersion': 1, 'nodes': [
        {'id': key, 'name': name, 'type': kind, 'properties': dict(x=str(x), y=str(y), **({'assigneeRule': 'user:' + user} if user else {}))}
        for key, name, kind, x, y, user in nodes], 'edges': [
        {'id': source + '-' + target, 'source': source, 'target': target, 'condition': '', 'defaultBranch': False}
        for source, target in [('start', 'fork'), ('fork', 'manager'), ('fork', 'finance'), ('manager', 'join'),
                               ('finance', 'join'), ('join', 'final'), ('final', 'end')]]}


def create(name, process=None):
    return request('POST', '/process-definitions', body={'key': 'parallel-' + uuid.uuid4().hex[:10],
                   'name': name, 'graph': graph() if process is None else process, 'formSchema': {'schemaVersion': 1, 'fields': []}})


def submit(definition):
    application = request('POST', '/applications', 'alice', expected=201, body={
        'businessNo': 'PG-' + uuid.uuid4().hex[:10], 'title': '并行运行验证', 'processKey': definition['key'],
        'definitionVersion': definition['version'], 'payload': {}})
    return request('POST', '/applications/' + application['id'] + '/submit', 'alice', body={'expectedVersion': application['version']})


def tasks(application, user):
    return [task for task in request('GET', '/tasks', user) if task['applicationId'] == application['id']]


def act(task, user, action='APPROVE', key=None, expected=200):
    return request('POST', '/tasks/' + task['taskId'] + '/actions', user, expected=expected, key=key,
                   body={'action': action, 'expectedVersion': task['version'], 'comment': '并行分支验证'})


for user in ('admin', 'alice', 'manager', 'finance', 'bob'):
    tokens[user] = request('POST', '/auth/login', 'anonymous', body={'tenantId': 'demo', 'username': user, 'password': 'demo'})['token']
assert request('POST', '/process-definitions/validate', body={'graph': graph()})['errors'] == []
simulation = request('POST', '/process-definitions/simulate', body={'graph': graph(), 'values': {}})
assert simulation['path'] == ['start', 'fork', 'manager', 'finance', 'join', 'final', 'end']
invalid = copy.deepcopy(graph())
invalid['edges'][1]['condition'] = 'amount > 10'
assert 'PARALLEL_CONDITION_FORBIDDEN:fork-manager' in request('POST', '/process-definitions/validate', body={'graph': invalid})['errors']
draft = create('并行审批运行验证')
definition = request('POST', f"/process-definitions/{draft['id']}/publish?expectedRevision=0", body={'changeNote': '全部分支完成后汇合'})
application = submit(definition)
manager, = tasks(application, 'manager')
finance, = tasks(application, 'finance')
assert manager['taskId'] != finance['taskId'] and not tasks(application, 'admin') and not tasks(application, 'bob')
act(manager, 'bob', expected=403)
key = str(uuid.uuid4())
first = act(finance, 'finance', key=key)
assert first['applicationStatus'] == 'IN_APPROVAL' and act(finance, 'finance', key=key) == first
assert not tasks(application, 'finance') and not tasks(application, 'admin')
manager, = tasks(application, 'manager')
act(manager, 'manager')
final, = tasks(application, 'admin')
assert act(final, 'admin')['applicationStatus'] == 'APPROVED'
diagram = request('GET', f"/applications/{application['id']}/rounds/1/diagram", 'alice')
assert {node['id'] for node in diagram['nodes'] if node['type'] == 'PARALLEL_GATEWAY'} == {'fork', 'join'}
assert all(node['activeTasks'] == 0 for node in diagram['nodes'])
negative = {}
for action in ('REJECT', 'RETURN'):
    current = submit(definition)
    finance, = tasks(current, 'finance')
    state = act(finance, 'finance', action)['applicationStatus']
    assert state == ('REJECTED' if action == 'REJECT' else 'RETURNED')
    assert all(not tasks(current, user) for user in ('manager', 'finance', 'admin'))
    negative[action] = current['id']
nested = graph()
nested['nodes'].extend([
    {'id': 'inner-fork', 'name': '内部拆分', 'type': 'PARALLEL_GATEWAY', 'properties': {}},
    {'id': 'inner-join', 'name': '内部汇合', 'type': 'PARALLEL_GATEWAY', 'properties': {}},
    {'id': 'legal', 'name': '法务审批', 'type': 'USER_TASK', 'properties': {'assigneeRule': 'user:bob'}}])
for edge in nested['edges']:
    if edge['id'] == 'fork-finance':
        edge['target'] = 'inner-fork'
    if edge['id'] == 'finance-join':
        edge['target'] = 'inner-join'
nested['edges'].extend({'id': source + '-' + target, 'source': source, 'target': target, 'condition': ''}
                       for source, target in [('inner-fork', 'finance'), ('inner-fork', 'legal'),
                                              ('legal', 'inner-join'), ('inner-join', 'join')])
nested_draft = create('嵌套并行运行验证', nested)
nested_definition = request('POST', f"/process-definitions/{nested_draft['id']}/publish?expectedRevision=0", body={'changeNote': '嵌套并行各自汇合'})
nested_application = submit(nested_definition)
assert all(len(tasks(nested_application, user)) == 1 for user in ('manager', 'finance', 'bob'))
for user in ('finance', 'bob', 'manager', 'admin'):
    task, = tasks(nested_application, user)
    result = act(task, user)
    assert result['applicationStatus'] == ('APPROVED' if user == 'admin' else 'IN_APPROVAL')
    if user in ('finance', 'bob'):
        assert not tasks(nested_application, 'admin')
browser = create('并行设计器验收 ' + uuid.uuid4().hex[:6])
print(json.dumps({'result': 'PASS', 'approvedApplication': application['id'], 'negativeApplications': negative,
                  'nestedApplication': nested_application['id'],
                  'definitionId': definition['id'], 'browserDraft': browser['id'], 'browserKey': browser['key'],
                  'browserName': browser['name'], 'simulationPath': simulation['path']}, ensure_ascii=False, indent=2))
