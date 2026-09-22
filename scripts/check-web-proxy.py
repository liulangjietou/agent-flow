"""验证本地前端代理的浏览器 Origin 语义，演示服务需已启动。"""
import json
import sys
import urllib.error
import urllib.request


def verify(origin, expected_status):
    request = urllib.request.Request(
        frontend + "/api/v1/auth/login",
        data=json.dumps({"tenantId": "demo", "username": "admin", "password": "demo"}).encode(),
        headers={"Content-Type": "application/json", "Origin": origin},
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            actual_status = response.status
            if expected_status == 200:
                body = json.load(response)
                assert body["user"]["userId"] == "admin", "Unexpected login user"
    except urllib.error.HTTPError as error:
        actual_status = error.code
    assert actual_status == expected_status, f"Origin {origin}: expected {expected_status}, got {actual_status}"
    print(f"Origin {origin}: HTTP {actual_status}")


frontend = sys.argv[1].rstrip("/") if len(sys.argv) > 1 else "http://127.0.0.1:5173"
verify(frontend, 200)
verify("https://untrusted.example", 403)
