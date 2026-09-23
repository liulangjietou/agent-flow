#!/usr/bin/env python3
"""在独立本机演示环境校验参与者分页、实际办理权限及旧详情授权的一致性。"""
import argparse
import json
import uuid
from urllib.error import HTTPError
from urllib.parse import urlencode, urlparse
from urllib.request import Request, urlopen


def exercise(base):
    tokens = {}
    parity_stages = 0
    def call(path, user='admin', body=None, expected=200):
        headers = {'Content-Type': 'application/json'}
        if user in tokens: headers['Authorization'] = 'Bearer ' + tokens[user]
        if body is not None: headers['Idempotency-Key'] = str(uuid.uuid4())
        request = Request(base + '/api/v1' + path, data=json.dumps(body, ensure_ascii=False).encode() if body is not None else None, headers=headers)
        try:
            response = urlopen(request, timeout=15)
        except HTTPError as error:
            response = error
        with response:
            result = json.load(response)
            assert response.status == expected, (path, user, response.status, result.get('code'))
            if response.status == 200 and path.startswith('/applications/search'):
                assert response.headers['Cache-Control'] == 'no-store'
            return result
    for user in ('admin', 'alice', 'manager', 'bob', 'finance', 'employee'):
        tokens[user] = call('/auth/login', user, {'tenantId': 'demo', 'username': user, 'password': 'demo'})['token']
    marker = '参与检索-' + str(uuid.uuid4())[:8]
    key = 'participant-' + str(uuid.uuid4())[:8]
    graph = {'nodes': [
        {'id': 'start', 'name': '开始', 'type': 'START', 'properties': {}},
        {'id': 'review', 'name': '经理审批', 'type': 'USER_TASK', 'properties': {'assigneeRule': 'role:MANAGER'}},
        {'id': 'end', 'name': '结束', 'type': 'END', 'properties': {}}],
        'edges': [{'id': 'begin', 'source': 'start', 'target': 'review', 'condition': ''}, {'id': 'finish', 'source': 'review', 'target': 'end', 'condition': ''}]}
    definition = call('/process-definitions', body={'key': key, 'name': marker, 'graph': graph})
    call('/process-definitions/' + definition['id'] + '/publish?expectedRevision=0', body={'changeNote': '参与者分页验收'})
    def draft(title, user='alice'):
        return call('/applications', user, {'businessNo': 'PS-' + str(uuid.uuid4()), 'processKey': key, 'definitionVersion': 1,
            'title': marker + ' ' + title, 'payload': {'secret': 'summary-must-not-contain'}}, 201)
    def page(user, **filters):
        return call('/applications/search?' + urlencode({'processKey': key, **filters}), user)
    def all_rows(user):
        rows = []; cursor = None
        while True:
            result = page(user, limit=7, **({'cursor': cursor} if cursor else {})); rows.extend(result['items'])
            cursor = result.get('nextCursor')
            if not cursor: break
        assert len({row['id'] for row in rows}) == len(rows)
        assert all('payload' not in row and 'formSchema' not in row for row in rows)
        return rows
    def parity():
        nonlocal parity_stages
        parity_stages += 1
        for user in tokens:
            visible = {row['id'] for row in all_rows(user)}
            legacy = {row['id'] for row in call('/applications', user) if row['processKey'] == key}
            assert visible == legacy, (user, visible, legacy)
    def submit(app): return call('/applications/' + app['id'] + '/submit', 'alice', {'expectedVersion': app['version']})
    def action(app, user, action_name, target=None):
        task = next(row for row in call('/tasks', user) if row['applicationId'] == app['id'])
        return call('/tasks/' + task['taskId'] + '/actions', user, {'action': action_name, 'expectedVersion': task['version'], 'comment': '参与者查询验收', **({'targetUser': target} if target else {})})
    active = submit(draft('转交与委托'))
    parity()
    action(active, 'manager', 'TRANSFER', 'bob')
    action(active, 'bob', 'DELEGATE', 'finance'); parity()
    action(active, 'finance', 'RESOLVE'); action(active, 'bob', 'RETURN'); parity()
    returned = call('/applications/' + active['id'], 'alice')
    resubmitted = submit(returned); parity()
    assert resubmitted['roundNo'] == 2
    cancelled = submit(draft('认领释放后撤回'))
    action(cancelled, 'manager', 'CLAIM'); action(cancelled, 'manager', 'RELEASE')
    cancelled = call('/applications/' + cancelled['id'], 'alice')
    call('/applications/' + cancelled['id'] + '/withdraw', 'alice', {'expectedVersion': cancelled['version'], 'comment': '权限随当前候选关系收回'})
    assert cancelled['id'] not in {row['id'] for row in all_rows('manager')}
    call('/applications/' + cancelled['id'], 'manager', expected=404)
    for index in range(33): draft('分页 %_! ' + str(index))
    for index in range(3): draft('其他申请人 ' + str(index), 'bob')
    first = page('alice', limit=30); second = page('alice', limit=30, cursor=first['nextCursor'])
    assert len(first['items']) == 30 and len(second['items']) == 5 and not second.get('nextCursor')
    assert len({row['id'] for row in first['items'] + second['items']}) == 35
    assert len(all_rows('admin')) == 38 and len(all_rows('bob')) == 4
    assert len(page('alice', q='%_!', limit=100)['items']) == 33
    assert not page('employee')['items']
    call('/applications/search?' + urlencode({'processKey': key, 'cursor': first['nextCursor']}), 'bob', expected=400)
    call('/applications/search?tenantId=other', 'alice', expected=400)
    call('/operations/applications', 'alice', expected=403)
    parity()
    return {'result': 'PASS', 'processKey': key, 'marker': marker, 'applicationId': active['id'], 'roundNo': 2,
            'withdrawnId': cancelled['id'], 'applicantPages': [30, 5], 'tenantAdminRecords': 38, 'unrelatedRecords': 0,
            'parityActors': list(tokens), 'parityStages': parity_stages}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('base'); parser.add_argument('--exercise', action='store_true'); args = parser.parse_args()
    url = urlparse(args.base)
    if not args.exercise or url.scheme != 'http' or url.hostname not in ('127.0.0.1', 'localhost') or url.port in (None, 8080, 8180) or url.path not in ('', '/'):
        parser.error('Use --exercise with an isolated loopback demo port, never 8080 or 8180')
    print(json.dumps(exercise(args.base.rstrip('/')), ensure_ascii=False))


if __name__ == '__main__': main()
