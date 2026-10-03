"""仅供本机合成验收的固定模型夹具，不接入外部模型，不验证摘要质量。"""
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class FixtureHandler(BaseHTTPRequestHandler):
    """只返回有来源引用的固定合成正文，不执行输入指令或记录原文。"""

    def log_message(self, *_args):
        pass

    def do_POST(self):
        size = int(self.headers.get('Content-Length', '0'))
        if self.path != '/v1/chat/completions' or not 0 < size <= 256 * 1024:
            self.send_error(400)
            return
        try:
            body = json.loads(self.rfile.read(size))
            sources = json.loads(body['messages'][1]['content'])['sources']
            if not isinstance(sources, list) or not 1 <= len(sources) <= 64:
                raise ValueError('Invalid source count')
            result = {'claims': [{'text': '本次采购两台开发工作站，用于已批准的研发项目。请人工核对采购依据、金额及交付计划。',
                                 'evidence': [source['reference'] for source in sources]}], 'confidence': 0.76}
            response = json.dumps({'model': 'loopback-synthetic-model-v1', 'choices': [{'finish_reason': 'stop',
                'message': {'role': 'assistant', 'content': json.dumps(result, ensure_ascii=False)}}]}, ensure_ascii=False).encode()
        except (ValueError, KeyError, TypeError, IndexError):
            self.send_error(400)
            return
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(response)))
        self.end_headers()
        self.wfile.write(response)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=18219)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error('Port must be between 1024 and 65535')
    ThreadingHTTPServer(('127.0.0.1', args.port), FixtureHandler).serve_forever()
