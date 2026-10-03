"""对隔离本机演示执行票夹真实 HTTP 验收，保留全部合成原件和数据库证据。"""
import hashlib
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid


def main():
    """必须明确指定本机代理和写入验收，不访问企业或生产服务。"""
    if len(sys.argv) != 3 or sys.argv[2] != '--exercise':
        raise SystemExit('Usage: check-invoice-wallet.py http://127.0.0.1:PORT --exercise')
    base = urllib.parse.urlparse(sys.argv[1])
    if base.scheme != 'http' or base.hostname not in {'127.0.0.1', 'localhost', '::1'} or base.username or base.password or base.query or base.fragment or base.path not in {'', '/'}:
        raise SystemExit('An isolated loopback HTTP origin is required')
    origin = sys.argv[1].rstrip('/')
    tokens, checks = {}, []

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        """认证信息和合成财务正文不能被重定向到其他目的地。"""
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None

    opener = urllib.request.build_opener(NoRedirect())

    def request(method, path, expected=200, user='alice', body=None, raw=None, key=None):
        headers = {}
        if user in tokens:
            headers['Authorization'] = 'Bearer ' + tokens[user]
        if key:
            headers['Idempotency-Key'] = key
        if raw is not None:
            data = raw
            headers['Content-Type'] = 'application/octet-stream'
        elif body is not None:
            data = json.dumps(body, ensure_ascii=False).encode()
            headers['Content-Type'] = 'application/json'
        else:
            data = None
        message = urllib.request.Request(origin + '/api/v1' + path, data=data, headers=headers, method=method)
        try:
            response = opener.open(message, timeout=30)
        except urllib.error.HTTPError as failure:
            response = failure
        with response:
            content = response.read()
            actual = response.status
            result_headers = response.headers
        checks.append({'method': method, 'path': path.split('?')[0], 'expected': expected, 'actual': actual})
        assert actual == expected, f'{method} {path}: expected {expected}, got {actual}'
        if 'application/json' in result_headers.get('Content-Type', ''):
            return json.loads(content), result_headers
        return content, result_headers

    for user in ('alice', 'admin'):
        value, _ = request('POST', '/auth/login', user=user, body={'tenantId': 'demo', 'username': user, 'password': 'demo'})
        tokens[user] = value['token']
    options, _ = request('GET', '/invoices/options')
    assert options['enabled'] and options['maxFileBytes'] >= 2 * 1024 * 1024
    assert set(options['formats']) == {'PDF', 'OFD', 'PNG', 'JPEG'}
    content = b'%PDF-1.7\n' + b'x' * (2 * 1024 * 1024 - 15) + b'\n%%EOF'
    digest = hashlib.sha256(content).hexdigest()
    body = {'filename': '合成验收原件.pdf', 'size': len(content), 'sha256': digest, 'format': 'PDF'}
    key = str(uuid.uuid4())
    value, _ = request('POST', '/invoices', 201, body=body, key=key)
    invoice = value['id']; path = '/invoices/' + invoice
    item, _ = request('GET', path)
    assert item['verification'] == 'PENDING' and item['original']['status'] == 'UPLOADING'
    request('GET', path + '/content', 422)
    request('PUT', path + '/content', raw=content)
    downloaded, headers = request('GET', path + '/content')
    assert downloaded == content and hashlib.sha256(downloaded).hexdigest() == digest
    assert headers['Cache-Control'] == 'no-store' and headers['X-Content-Type-Options'] == 'nosniff'
    assert headers['Content-Disposition'].startswith('attachment;')
    request('PUT', path + '/content', raw=content)
    request('PUT', path + '/content', 422, raw=b'wrong')
    item, _ = request('GET', path)
    assert item['original']['status'] == 'READY' and item['verification'] == 'PENDING' and 'facts' not in item
    replayed, headers = request('POST', '/invoices', 201, body=body, key=key)
    assert replayed['id'] == invoice and headers['Idempotency-Replayed'] == 'true'
    request('GET', path, 404, user='admin')
    request('GET', path + '/content', 404, user='admin')
    request('PUT', path + '/content', 404, user='admin', raw=content)
    request('GET', path, 401, user='anonymous')
    request('GET', '/invoices?tenantId=other', 400)
    page, _ = request('GET', '/invoices?limit=1')
    assert len(page['items']) == 1
    # 普通 JSON 继续受代理的 1 MiB 限制，不能通过新增文件路径放宽所有接口。
    request('POST', '/invoices', 413, body={**body, 'filename': 'x' * (1024 * 1024 + 1)}, key=str(uuid.uuid4()))
    print(json.dumps({'result': 'PASS', 'checks': len(checks), 'invoiceId': invoice, 'originalBytes': len(content),
                      'sha256': digest, 'verification': item['verification'], 'throughProxy': origin}, ensure_ascii=False))


if __name__ == '__main__':
    main()
