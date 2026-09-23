#!/usr/bin/env python3
"""本地接收演示：原文验签、五分钟时窗、SQLite 持久化去重；不执行业务入账。"""
import argparse
import base64
import hashlib
import hmac
import json
import os
import sqlite3
import time
import uuid
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

MAX_BODY = 128 * 1024


def verify(secret, headers, body, now):
    """只接受原始正文签名；返回经过校验的事件，失败不落业务数据。"""
    event_id = headers.get('webhook-id', '')
    timestamp = headers.get('webhook-timestamp', '')
    if str(uuid.UUID(event_id)) != event_id or not timestamp.isdigit() or abs(now - int(timestamp)) > 300:
        raise ValueError('Invalid identity or timestamp')
    signed = event_id.encode() + b'.' + timestamp.encode() + b'.' + body
    expected = base64.b64encode(hmac.new(secret, signed, hashlib.sha256).digest()).decode()
    signatures = headers.get('webhook-signature', '').split(' ')
    if not any(hmac.compare_digest('v1,' + expected, signature) for signature in signatures):
        raise ValueError('Invalid signature')
    event = json.loads(body)
    if not isinstance(event, dict) or event.get('eventId') != event_id or event.get('payloadVersion') != 1:
        raise ValueError('Invalid envelope')
    return event


def serve(database, key, tenant, host, port, fail_first):
    """仅作集成验收；部署接收端应在自己的业务事务中提交去重记录与实际处理。"""
    with sqlite3.connect(database) as conn:
        conn.execute('CREATE TABLE IF NOT EXISTS receipts (event_id TEXT PRIMARY KEY, digest TEXT NOT NULL, payload TEXT NOT NULL, received_at INTEGER NOT NULL)')
        conn.execute('CREATE TABLE IF NOT EXISTS attempts (event_id TEXT PRIMARY KEY, count INTEGER NOT NULL)')

    class Receiver(BaseHTTPRequestHandler):
        """验证成功才写入；相同 ID 不同正文必须拒绝。@author owlzhangfq@gmail.com"""
        def do_POST(self):
            self.connection.settimeout(5)
            if self.path != '/events':
                self.send_error(404); return
            try:
                length = int(self.headers.get('Content-Length', '-1'))
                if not 0 < length <= MAX_BODY or self.headers.get('Transfer-Encoding'):
                    raise ValueError('Invalid content length')
                body = self.rfile.read(length)
                if len(body) != length:
                    raise ValueError('Incomplete body')
                event = verify(key, self.headers, body, int(time.time()))
                if event.get('tenantId') != tenant:
                    raise ValueError('Unexpected tenant')
                event_id = event['eventId']; digest = hashlib.sha256(body).hexdigest()
                with sqlite3.connect(database) as conn:
                    conn.execute('INSERT INTO attempts VALUES (?,1) ON CONFLICT(event_id) DO UPDATE SET count=count+1', (event_id,))
                    count = conn.execute('SELECT count FROM attempts WHERE event_id=?', (event_id,)).fetchone()[0]
                    old = conn.execute('SELECT digest FROM receipts WHERE event_id=?', (event_id,)).fetchone()
                    if old and old[0] != digest:
                        raise ValueError('Changed event body')
                    if fail_first and count == 1:
                        status = 503
                    else:
                        conn.execute('INSERT OR IGNORE INTO receipts VALUES (?,?,?,?)', (event_id, digest, body.decode(), int(time.time())))
                        status = 204
                self.send_response(status); self.send_header('Content-Length', '0'); self.end_headers()
            except (ValueError, TypeError, UnicodeError, TimeoutError, OSError):
                self.send_error(400, 'Invalid webhook')

        def log_message(self, format, *args):
            """不输出请求头、正文或签名。"""
            return

    HTTPServer((host, port), Receiver).serve_forever()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database', required=True, type=Path)
    parser.add_argument('--tenant', default='demo')
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8091)
    parser.add_argument('--fail-first', action='store_true', help='每个事件第一次返回 503，演示自动重试')
    args = parser.parse_args()
    secret = os.environ.get('AGENTFLOW_RECEIVER_SECRET', '')
    if not secret.startswith('whsec_'):
        parser.error('AGENTFLOW_RECEIVER_SECRET must contain a whsec_ key')
    try:
        key = base64.b64decode(secret[6:], validate=True)
        if not 32 <= len(key) <= 64: raise ValueError()
    except ValueError:
        parser.error('Invalid signing key')
    args.database.parent.mkdir(parents=True, exist_ok=True)
    serve(str(args.database), key, args.tenant, args.host, args.port, args.fail_first)


if __name__ == '__main__':
    main()
