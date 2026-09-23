#!/usr/bin/env python3
"""只在明确指定的独立本机演示环境新增验收数据，验证真实 Webhook 重试与去重。"""
import argparse
import json
import time
import uuid
from urllib.parse import urlparse
from urllib.request import Request, urlopen


def exercise(base):
    tokens = {}
    def call(path, user='admin', body=None, key=None):
        headers = {'Content-Type': 'application/json'}
        if user in tokens: headers['Authorization'] = 'Bearer ' + tokens[user]
        if body is not None: headers['Idempotency-Key'] = key or str(uuid.uuid4())
        request = Request(base + '/api/v1' + path, data=json.dumps(body, ensure_ascii=False).encode() if body is not None else None, headers=headers)
        with urlopen(request, timeout=15) as response:
            return json.load(response)
    for user in ('admin', 'alice', 'finance'):
        tokens[user] = call('/auth/login', user, {'tenantId':'demo', 'username':user, 'password':'demo'})['token']
    targets = call('/integrations/webhooks')
    assert any(target['enabled'] for target in targets), 'A local verification receiver must be explicitly configured'
    suffix = str(uuid.uuid4())[:8]
    app = call('/applications', 'alice', {'businessNo':'WH-VERIFY-' + suffix, 'processKey':'expense-reimbursement', 'definitionVersion':1, 'title':'Webhook 联调 ' + suffix, 'payload':{'amount':6000}})
    call('/applications/' + app['id'] + '/submit', 'alice', {'expectedVersion':1})
    task = next(task for task in call('/tasks', 'finance') if task['applicationId'] == app['id'])
    call('/tasks/' + task['taskId'] + '/actions', 'finance', {'expectedVersion':task['version'], 'action':'APPROVE', 'comment':'本地接收服务验收'})
    root = '/integrations/webhooks/deliveries'
    def delivered(minimum_attempts):
        for _ in range(45):
            items = call(root + '?applicationId=' + app['id'])['items']
            if len(items) == 3 and all(item['status']=='DELIVERED' and item['attempts'] >= minimum_attempts for item in items): return items
            time.sleep(1)
        raise AssertionError('Delivery did not converge to DELIVERED')
    items = delivered(2)
    assert {item['eventType'] for item in items} == {'ApplicationSubmitted','TaskActionAccepted','ApplicationApproved'}
    for item in items:
        attempts = call(root + '/' + item['id'])['attempts']
        assert [attempt['httpStatus'] for attempt in attempts] == [204,503], 'Receiver must use --fail-first'
    selected = items[0]; key = str(uuid.uuid4()); retry = {'expectedVersion':selected['version']}
    replay_path = root + '/' + selected['id'] + '/retry'
    result = call(replay_path, body=retry, key=key)
    assert result == call(replay_path, body=retry, key=key), 'Original request must replay exactly'
    for _ in range(15):
        detail = call(root + '/' + selected['id'])
        if detail['delivery']['status']=='DELIVERED' and detail['delivery']['attempts']==3:break
        time.sleep(1)
    else:raise AssertionError('Manual redelivery did not complete')
    assert len(detail['retryRequests']) == 1
    assert detail['delivery']['eventId'] == selected['eventId']
    assert call('/applications/' + app['id'], 'alice')['status'] == 'APPROVED'
    return {'result':'PASS','applicationId':app['id'],'events':[item['eventId'] for item in items], 'manualRetryEvent':selected['eventId'], 'deliveryId':selected['id'], 'automaticAttemptsPerEvent':2, 'manualAttempts':3}


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('base');parser.add_argument('--exercise',action='store_true');args=parser.parse_args()
    url=urlparse(args.base)
    if not args.exercise or url.scheme!='http' or url.hostname not in ('127.0.0.1','localhost') or url.port in (None,8080,8180) or url.path not in ('','/'):
        parser.error('Use --exercise with an isolated loopback demo port, never 8080 or 8180')
    print(json.dumps(exercise(args.base.rstrip('/')),ensure_ascii=False))


if __name__=='__main__':main()
