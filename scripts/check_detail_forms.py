#!/usr/bin/env python3
"""在独立本机演示环境验证明细表单、版本冻结、退回重提和权限边界。"""
import argparse
import json
import uuid
from urllib.error import HTTPError
from urllib.parse import urlparse
from urllib.request import Request, urlopen


def exercise(base):
    tokens = {}
    def call(path, user='admin', body=None, expected=200, method=None):
        headers = {'Content-Type': 'application/json'}
        if user in tokens: headers['Authorization'] = 'Bearer ' + tokens[user]
        if body is not None: headers['Idempotency-Key'] = str(uuid.uuid4())
        request = Request(base + '/api/v1' + path, data=json.dumps(body, ensure_ascii=False).encode() if body is not None else None,
                          headers=headers, method=method)
        try: response = urlopen(request, timeout=15)
        except HTTPError as error: response = error
        with response:
            result = json.load(response)
            assert response.status == expected, (path, user, response.status, result.get('code'))
            return result
    for user in ('admin', 'alice', 'manager', 'bob'):
        tokens[user] = call('/auth/login', user, {'tenantId': 'demo', 'username': user, 'password': 'demo'})['token']
    key = 'details-' + uuid.uuid4().hex[:8]
    graph = {'nodes': [
        {'id': 'start', 'name': '开始', 'type': 'START', 'properties': {}},
        {'id': 'review', 'name': '经理审批', 'type': 'USER_TASK', 'properties': {'assigneeRule': 'role:MANAGER'}},
        {'id': 'end', 'name': '结束', 'type': 'END', 'properties': {}}], 'edges': [
        {'id': 'begin', 'source': 'start', 'target': 'review', 'condition': '', 'defaultBranch': False},
        {'id': 'finish', 'source': 'review', 'target': 'end', 'condition': '', 'defaultBranch': False}]}
    schema = {'schemaVersion': 2, 'fields': [{'key': 'items', 'label': '设备明细', 'type': 'TABLE', 'required': True, 'maxRows': 3, 'columns': [
        {'key': 'name', 'label': '设备名称', 'type': 'TEXT', 'required': True},
        {'key': 'quantity', 'label': '数量', 'type': 'NUMBER', 'required': True, 'minimum': '0'},
        {'key': 'date', 'label': '使用日期', 'type': 'DATE', 'required': False},
        {'key': 'category', 'label': '分类', 'type': 'SELECT', 'required': False, 'options': [{'value': 'a', 'label': '办公设备'}]},
        {'key': 'confirmed', 'label': '确认', 'type': 'BOOLEAN', 'required': False},
        {'key': 'note', 'label': '说明', 'type': 'TEXTAREA', 'required': False}]}]}
    definition = call('/process-definitions', body={'key': key, 'name': '重复明细验收 ' + key, 'graph': graph, 'formSchema': schema})
    assert definition['formSchema'] == schema
    path = '/process-definitions/' + definition['id']
    call(path + '/publish?expectedRevision=0', body={'changeNote': '明细表单隔离环境验收'})
    def draft(payload):
        return call('/applications', 'alice', {'businessNo': 'DT-' + uuid.uuid4().hex, 'processKey': key, 'definitionVersion': 1,
                    'title': '设备明细申请 ' + key, 'payload': payload}, 201)
    app = draft({'items': [{'name': '待完善'}]}); app_path = '/applications/' + app['id']
    failed = call(app_path + '/submit', 'alice', {'expectedVersion': app['version']}, 422)
    assert failed['details']['fieldErrors'] == {'items[0].quantity': 'REQUIRED'}
    assert call(app_path, 'alice')['version'] == app['version'] and call(app_path + '/rounds', 'alice') == []
    first_values = {'items': [{'name': '显示器', 'quantity': '0009007199254740993.00', 'date': '2028-02-29', 'category': 'a', 'confirmed': False, 'note': '保留原样\n第二行'},
                              {'name': '键盘', 'quantity': '2', 'note': None}]}
    app = call(app_path, 'alice', {'expectedVersion': app['version'], 'title': app['title'], 'payload': first_values}, method='PUT')
    app = call(app_path + '/submit', 'alice', {'expectedVersion': app['version']})
    assert app['payload'] == first_values and app['formSchema'] == schema
    original = call(app_path + '/rounds', 'alice')[0]
    call(app_path, 'bob', expected=404)
    assert call(app_path, 'manager')['payload'] == first_values
    task = next(task for task in call('/tasks', 'manager') if task['applicationId'] == app['id'])
    call('/tasks/' + task['taskId'] + '/actions', 'manager', {'action': 'RETURN', 'expectedVersion': task['version'], 'comment': '请调整明细数量'})
    app = call(app_path, 'alice')
    # 新定义修改列配置后，既有申请仍绑定原发布版本。
    changed = json.loads(json.dumps(schema)); changed['fields'][0]['columns'][1]['label'] = '新数量'; changed['fields'][0]['maxRows'] = 5
    version2 = call('/process-definitions', body={'key': key, 'name': definition['name'], 'graph': graph, 'formSchema': changed})
    comparison = call(path + '/compare', body={'key': key, 'name': definition['name'], 'graph': graph, 'formSchema': changed})
    assert {'columns', 'maxRows'} <= {change['property'] for change in comparison['changes']}
    version2 = call('/process-definitions/' + version2['id'] + '/publish?expectedRevision=0', body={'changeNote': '新列配置不改变原申请快照'})
    assert version2['version'] == 2
    changed_values = {'items': [{'name': '键盘', 'quantity': '1'}]}
    app = call(app_path, 'alice', {'expectedVersion': app['version'], 'title': '补正后的设备明细 ' + key, 'payload': changed_values}, method='PUT')
    app = call(app_path + '/submit', 'alice', {'expectedVersion': app['version']})
    rounds = call(app_path + '/rounds', 'alice')
    assert len(rounds) == 2 and rounds[0]['payload'] == original['payload'] and rounds[0]['formSchema'] == original['formSchema']
    assert rounds[1]['payload'] == changed_values and rounds[1]['formSchema'] == schema and app['definitionVersion'] == 1
    failures = [({'items': ''}, {'items': 'INVALID_TYPE'}), ({'items': [None]}, {'items[0]': 'INVALID_TYPE'}),
                ({'items': [{'quantity': 1}]}, {'items[0].quantity': 'INVALID_TYPE'}),
                ({'items': [{'extra': 'secret'}]}, {'items[0].extra': 'UNKNOWN_FIELD'}),
                ({'items': [{}, {}, {}, {}]}, {'items': 'TOO_MANY_ROWS'})]
    for payload, errors in failures:
        response = call('/applications', 'alice', {'businessNo': 'INVALID-' + uuid.uuid4().hex, 'processKey': key, 'definitionVersion': 1, 'title': '应拒绝的明细', 'payload': payload}, 422)
        assert response['details']['fieldErrors'] == errors
    return {'result': 'PASS', 'processKey': key, 'applicationId': app['id'], 'definitionId': definition['id'], 'definitionVersion': 1,
            'newDefinitionVersion': version2['version'], 'rounds': len(rounds), 'invalidPayloadsRejected': len(failures),
            'originalNumber': rounds[0]['payload']['items'][0]['quantity'], 'originalBoolean': rounds[0]['payload']['items'][0]['confirmed']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('base'); parser.add_argument('--exercise', action='store_true'); args = parser.parse_args()
    url = urlparse(args.base)
    if not args.exercise or url.scheme != 'http' or url.hostname not in ('127.0.0.1', 'localhost') or url.port in (None, 8080, 8180) or url.path not in ('', '/'):
        parser.error('Use --exercise with an isolated loopback demo port, never 8080 or 8180')
    print(json.dumps(exercise(args.base.rstrip('/')), ensure_ascii=False))


if __name__ == '__main__': main()
