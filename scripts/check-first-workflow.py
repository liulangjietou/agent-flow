#!/usr/bin/env python3
"""独立本机演示库验证首次流程进度，创建的流程和申请保留，不输出令牌。"""
import json
import sys
import uuid
from urllib.error import HTTPError
from urllib.parse import urlparse
from urllib.request import Request, urlopen

base = sys.argv[1].rstrip('/') if len(sys.argv) > 1 else ''
if urlparse(base).hostname not in ('localhost', '127.0.0.1', '::1') or sys.argv[2:] != ['--exercise']:
    raise SystemExit('Use an isolated loopback demo: check-first-workflow.py http://127.0.0.1:8082 --exercise')
tokens = {}


def request(method, path, user='admin', body=None, expected=200):
    headers = {'Content-Type': 'application/json'}
    if user in tokens:
        headers['Authorization'] = 'Bearer ' + tokens[user]
    if method != 'GET':
        headers['Idempotency-Key'] = str(uuid.uuid4())
    try:
        response = urlopen(Request(base + '/api/v1' + path, headers=headers, method=method,
                           data=None if body is None else json.dumps(body, ensure_ascii=False).encode()), timeout=20)
    except HTTPError as error:
        response = error
    with response:
        data = response.read()
        value = json.loads(data) if data else None
        assert response.status == expected, (method, path, response.status, value)
        if path.startswith('/system/first-workflow') and expected == 200:
            assert response.headers.get('Cache-Control') == 'no-store'
        return value


for user in ('admin', 'alice', 'manager'):
    tokens[user] = request('POST', '/auth/login', user='anonymous', body={'tenantId':'demo','username':user,'password':'demo'})['token']
key = 'guide-' + uuid.uuid4().hex[:10]
graph = {'nodes':[{'id':'start','name':'开始','type':'START','properties':{}},
                  {'id':'review','name':'经理审批','type':'USER_TASK','properties':{'assigneeRule':'user:manager'}},
                  {'id':'end','name':'结束','type':'END','properties':{}}],
         'edges':[{'id':'a','source':'start','target':'review','condition':''},{'id':'b','source':'review','target':'end','condition':''}]}
definition = request('POST','/process-definitions',body={'key':key,'name':'首次流程验收','graph':graph})
path = '/system/first-workflow?definitionId=' + definition['id']
assert request('GET',path)['definition']['version'] == 0
request('POST',f"/process-definitions/{definition['id']}/publish?expectedRevision=0",body={'changeNote':'首次流程引导验收'})
assert request('GET',path)['submittedRounds'] == 0
app = request('POST','/applications',user='alice',expected=201,body={'processKey':key,'definitionVersion':1,'businessNo':key,'title':'首次流程运行验收','payload':{}})
app = request('POST',f"/applications/{app['id']}/submit",user='alice',body={'expectedVersion':app['version']})
task, = [t for t in request('GET','/tasks',user='manager') if t['applicationId'] == app['id']]
request('POST',f"/tasks/{task['taskId']}/actions",user='manager',body={'action':'RETURN','expectedVersion':task['version'],'comment':'补充材料'})
assert request('GET',path)['approvedRounds'] == 0
app = request('GET',f"/applications/{app['id']}")
app = request('POST',f"/applications/{app['id']}/submit",user='alice',body={'expectedVersion':app['version']})
task, = [t for t in request('GET','/tasks',user='manager') if t['applicationId'] == app['id']]
request('POST',f"/tasks/{task['taskId']}/actions",user='manager',body={'action':'APPROVE','expectedVersion':task['version']})
report = request('GET',path)
assert report['unrecordedHistoricalRounds'] == 0
assert report['submittedRounds'] == 2 and report['approvedRounds'] == 1
assert report['latestApproval']['roundNo'] == 2 and report['latestApproval']['applicationId'] == app['id']
history = {suffix:request('GET',f"/applications/{app['id']}/{suffix}") for suffix in ('rounds','timeline','audit')}
request('GET',path)
assert history == {suffix:request('GET',f"/applications/{app['id']}/{suffix}") for suffix in history}
next_version = request('POST','/process-definitions',body={'key':key,'name':'新版本不能借用旧记录','graph':graph})
next_path = '/system/first-workflow?definitionId=' + next_version['id']
assert request('GET',next_path)['submittedRounds'] == 0
request('POST',f"/process-definitions/{next_version['id']}/publish?expectedRevision=0",body={'changeNote':'隔离旧版本运行证据'})
assert request('GET',next_path)['definition']['version'] == 2
assert request('GET',next_path)['approvedRounds'] == 0
request('GET',path,user='alice',expected=403)
request('GET',path,user='anonymous',expected=401)
request('GET',path+'&tenantId=other',expected=400)
request('GET','/system/first-workflow?definitionId='+str(uuid.uuid4()),expected=404)
print(json.dumps({'result':'PASS','base':base,'processKey':key,'definitionId':definition['id'],'nextDefinitionId':next_version['id'],
                  'approvedApplication':app['id'],'submittedRounds':report['submittedRounds'],'approvedRounds':report['approvedRounds']},ensure_ascii=False))
