"""在隔离演示环境验证 Excel 实际内容、完整筛选和导出只读性，保留合成申请。"""
import io
import json
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zipfile

base, output, flag = sys.argv[1:]
assert flag == '--exercise' and urllib.parse.urlsplit(base).hostname in ('127.0.0.1', 'localhost', '::1')
prefix = 'export-' + uuid.uuid4().hex[:10]
root = Path(output); root.mkdir(mode=0o700)
tokens = {}
namespace = {'s': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
media = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'


def request(method, path, user='admin', body=None, expected=200):
    headers = {'Content-Type': 'application/json'}
    if user in tokens:
        headers['Authorization'] = 'Bearer ' + tokens[user]
    if method != 'GET':
        headers['Idempotency-Key'] = str(uuid.uuid4())
    req = urllib.request.Request(base.rstrip('/') + '/api/v1' + path, headers=headers, method=method,
                                 data=None if body is None else json.dumps(body, ensure_ascii=False).encode())
    try:
        response = urllib.request.urlopen(req, timeout=60)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        assert response.status == expected, (path, response.status)
        if response.headers.get_content_type() == media:
            assert response.headers['Cache-Control'] == 'no-store'
            assert response.headers['X-Content-Type-Options'] == 'nosniff'
            assert 'agentflow-applications.xlsx' in response.headers['Content-Disposition']
            return raw
        return json.loads(raw)


def workbook(raw):
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        assert archive.testzip() is None
        assert not any('vba' in name.lower() or 'externalLinks' in name for name in archive.namelist())
        strings = [''.join(item.itertext()) for item in ET.fromstring(archive.read('xl/sharedStrings.xml'))]
        result = []
        for name in ('xl/worksheets/sheet1.xml', 'xl/worksheets/sheet2.xml'):
            tree = ET.fromstring(archive.read(name))
            assert not tree.findall('.//s:f', namespace)
            rows = []
            for row in tree.findall('.//s:sheetData/s:row', namespace):
                cells = []
                for cell in row.findall('s:c', namespace):
                    assert cell.attrib['t'] == 's'
                    value = strings[int(cell.find('s:v', namespace).text)]
                    cells.append(re.sub(r'_x([0-9a-fA-F]{4})_', lambda match: chr(int(match[1], 16)), value))
                rows.append(cells)
            result.append(rows)
        return result


for user in ('admin', 'alice', 'bob'):
    tokens[user] = request('POST', '/auth/login', user, {'tenantId': 'demo', 'username': user, 'password': 'demo'})['token']
process = request('POST', '/process-definitions', body={'key': prefix, 'name': '申请摘要导出验证',
    'graph': {'nodes': [{'id': 's', 'type': 'START', 'name': '开始', 'properties': {}},
                        {'id': 'u', 'type': 'USER_TASK', 'name': '经理审批', 'properties': {'assigneeRule': 'role:MANAGER'}},
                        {'id': 'e', 'type': 'END', 'name': '结束', 'properties': {}}],
              'edges': [{'id': 'a', 'source': 's', 'target': 'u', 'condition': ''}, {'id': 'b', 'source': 'u', 'target': 'e', 'condition': ''}]},
    'formSchema': {'schemaVersion': 1, 'fields': [{'key': 'privateNote', 'label': '正文', 'type': 'TEXT', 'required': False}]}})
request('POST', '/process-definitions/' + process['id'] + '/publish?expectedRevision=0', body={'changeNote': '验证 Excel 摘要和原值保留'})
applications = []
titles = ['=导出公式样例', '+导出文本', '导出_x000D_原文', '导出,逗号"引号\n换行', '导出中文😀']
for index in range(35):
    app = request('POST', '/applications', 'alice', {'processKey': prefix, 'definitionVersion': 1,
        'businessNo': '000' + str(uuid.uuid4().int) if index == 0 else prefix + '-' + str(index),
        'title': titles[index % len(titles)], 'payload': {'privateNote': 'MUST_NOT_EXPORT_PRIVATE_BODY'}}, expected=201)
    applications.append(app)
app = applications[-1]
applications[-1] = request('POST', '/applications/' + app['id'] + '/cancel', 'alice', {'expectedVersion': app['version'], 'comment': '导出筛选验收'})


def facts():
    return {app['id']: {path: request('GET', '/applications/' + app['id'] + path, 'alice')
                       for path in ('', '/rounds', '/audit?limit=100', '/timeline?limit=100')}
            for app in applications}


before = facts()
query = {'processKey': prefix, 'definitionVersion': 1, 'applicant': 'alice'}
export_path = '/operations/applications/export'
file = request('GET', export_path + '?' + urllib.parse.urlencode(query))
(root / 'applications.xlsx').write_bytes(file)
rows, metadata = workbook(file)
assert len(rows) == 36 and len(rows[0]) == 10
found = {row[0]: row for row in rows[1:]}
assert set(found) == {app['id'] for app in applications}
for app in applications:
    row = found[app['id']]
    assert row[1] == app['businessNo'] and row[2] == app['title'] and row[6] == app['status']
assert 'MUST_NOT_EXPORT_PRIVATE_BODY' not in str(rows) + str(metadata)
assert metadata[3] == ['记录数', '35']
for state, expected_count in (('CANCELLED', 1), ('DRAFT', 34), ('APPROVED', 0)):
    filtered, _ = workbook(request('GET', export_path + '?' + urllib.parse.urlencode({**query, 'status': state})))
    assert len(filtered) == expected_count + 1
request('GET', export_path, 'alice', expected=403)
request('GET', export_path, 'bob', expected=403)
request('GET', export_path, 'anonymous', expected=401)
for field in ('cursor', 'limit', 'tenantId'):
    response = request('GET', export_path + '?' + field + '=1', expected=400)
    assert response['code'] == 'INVALID_APPLICATION_QUERY'
assert facts() == before
report = {'status': 'PASS', 'processKey': prefix, 'applications': 35, 'drafts': 34, 'cancelled': 1,
          'beforeAfter': 'EXACT_MATCH', 'textCells': 'formula-like titles, OOXML escape literal, long leading-zero businessNo, quotes/newlines, Unicode preserved',
          'file': str(root / 'applications.xlsx')}
(root / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(json.dumps(report, ensure_ascii=False))
