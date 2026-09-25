#!/usr/bin/env python3
"""用两个隔离后端核对生产代理的分流、故障恢复和请求重放边界。"""
import json
import os
from pathlib import Path
import ssl
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid


ROOT = Path(__file__).resolve().parents[1]
NGINX_IMAGE = os.environ.get("AGENTFLOW_PROXY_TEST_NGINX_IMAGE",
                             "nginx@sha256:bf3201ab56f23e5954646379c775d511fc466e9f11376d9725361064ad07ed35")
PYTHON_IMAGE = os.environ.get("AGENTFLOW_PROXY_TEST_PYTHON_IMAGE",
                              "python@sha256:401f6e1a67dad31a1bd78e9ad22d0ee0a3b52154e6bd30e90be696bb6a3d7461")
BACKEND = '''import json,os,socket
from http.server import BaseHTTPRequestHandler,HTTPServer
from urllib.parse import urlsplit
class Handler(BaseHTTPRequestHandler):
    """隔离故障后端。@author owlzhangfq@gmail.com"""
    def log_message(self,*args): pass
    def handle_request(self):
        path=urlsplit(self.path).path
        self.rfile.read(int(self.headers.get('Content-Length','0')))
        with open('/fixture/requests.jsonl','a') as out:
            out.write(json.dumps({'node':os.environ['NODE'],'method':self.command,'path':path})+'\\n')
        if path.endswith('/drop'):
            self.connection.shutdown(socket.SHUT_RDWR);self.connection.close();return
        body=json.dumps({'node':os.environ['NODE'],'status':'UP'}).encode()
        self.send_response(200);self.send_header('Content-Type','application/json')
        self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
    do_GET=handle_request
    do_POST=handle_request
HTTPServer(('0.0.0.0',8080),Handler).serve_forever()
'''


def docker(*arguments):
    return subprocess.check_output(["docker", *arguments], text=True, stderr=subprocess.PIPE).strip()


def main():
    directory = Path(tempfile.mkdtemp(prefix="agentflow-proxy-cluster-", dir="/fyoung/tmp"))
    network = "agentflow-proxy-test-" + uuid.uuid4().hex[:12]
    owned = []
    network_id = docker("network", "create", network)
    try:
        (directory / "server.py").write_text(BACKEND)
        (directory / "requests.jsonl").touch()
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                        "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost",
                        "-keyout", str(directory / "key.pem"), "-out", str(directory / "cert.pem")],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for name in ("a", "b"):
            owned.append(docker("run", "-d", "--network", network, "--network-alias", "server",
                                "-e", "NODE=" + name, "-v", str(directory) + ":/fixture",
                                PYTHON_IMAGE, "python", "/fixture/server.py"))
        for backend in owned:
            deadline = time.monotonic() + 20
            while time.monotonic() < deadline:
                probe = subprocess.run(["docker", "exec", backend, "python", "-c",
                        "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/ready',timeout=2).close()"],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                if probe.returncode == 0: break
                time.sleep(.2)
            else: raise AssertionError("Fixture backend did not become ready")
        web = docker("run", "-d", "--network", network, "-p", "127.0.0.1::8443",
                     "-v", str(ROOT / "deploy/production/nginx.conf") + ":/etc/nginx/conf.d/default.conf:ro",
                     "-v", str(directory / "cert.pem") + ":/run/secrets/tls_certificate:ro",
                     "-v", str(directory / "key.pem") + ":/run/secrets/tls_private_key:ro", NGINX_IMAGE)
        owned.append(web)
        port = json.loads(docker("inspect", web))[0]["NetworkSettings"]["Ports"]["8443/tcp"][0]["HostPort"]
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                    urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=str(directory / "cert.pem"))))

        def request(path, method="GET"):
            try:
                response = opener.open(urllib.request.Request("https://localhost:" + port + path,
                        data=b"{}" if method == "POST" else None, method=method), timeout=12)
            except urllib.error.HTTPError as error:
                response = error
            with response: return response.code, response.read().decode()

        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            try:
                if request("/actuator/health/readiness")[0] == 200: break
            except OSError: pass
            time.sleep(.5)
        else: raise AssertionError("Proxy did not become ready")

        def wait_for_both_nodes():
            nodes = set()
            deadline = time.monotonic() + 20
            while time.monotonic() < deadline and nodes != {"a", "b"}:
                code, body = request("/api/probe")
                if code == 200: nodes.add(json.loads(body)["node"])
                time.sleep(.2)
            assert nodes == {"a", "b"}, "Both application nodes must receive traffic"

        wait_for_both_nodes()
        for path, method in [("/api/drop", "POST"), ("/api/v1/auth/oidc/drop", "GET")]:
            before = len((directory / "requests.jsonl").read_text().splitlines())
            assert request(path, method)[0] == 502
            after = [json.loads(line) for line in (directory / "requests.jsonl").read_text().splitlines()[before:]]
            assert len(after) == 1, "Ambiguous write or OIDC callback must not be replayed by the proxy"

        # 上一步故意中断连接会触发上游短期隔离；先验证恢复，再开始独立的停节点场景。
        wait_for_both_nodes()
        docker("stop", "--time", "5", owned[0])
        for _ in range(12):
            code, body = request("/api/probe")
            assert code == 200 and json.loads(body)["node"] == "b", "Remaining application node read failed: " + str(code)
        docker("start", owned[0])
        recovered = set()
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline and recovered != {"a", "b"}:
            code, body = request("/api/probe")
            if code == 200: recovered.add(json.loads(body)["node"])
            time.sleep(.2)
        assert recovered == {"a", "b"}, "Recovered node must rejoin without restarting the proxy"
        report = {"bothNodesServed": True, "unsafeRequestsNotReplayed": True,
                  "readsSurviveNodeStop": True, "nodeRecoveryDiscovered": True,
                  "nginxImage": NGINX_IMAGE, "evidenceDirectory": str(directory)}
        (directory / "report.json").write_text(json.dumps(report, indent=2))
        print(json.dumps(report))
    except BaseException:
        for identifier in owned:
            logs = subprocess.run(["docker", "logs", identifier], capture_output=True, text=True)
            (directory / (identifier[:12] + ".log")).write_text(logs.stdout + logs.stderr)
        print("Proxy evidence retained at " + str(directory), flush=True)
        raise
    finally:
        # 只删除本次创建并记录 ID 的无业务数据夹具；主项目、数据库及其他数据卷不受影响。
        for identifier in reversed(owned):
            subprocess.run(["docker", "rm", "-f", identifier], stdout=subprocess.DEVNULL, check=True)
        subprocess.run(["docker", "network", "rm", network_id], stdout=subprocess.DEVNULL, check=True)


if __name__ == "__main__":
    main()
