"""压测工具必须保护现有资源、保留失败样本，并拒绝失真的统计结果。"""
import argparse
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from io import BytesIO

spec = importlib.util.spec_from_file_location("capacity_baseline", Path(__file__).resolve().parents[1] / "capacity_baseline.py")
baseline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(baseline)


class CapacityBaselineTest(unittest.TestCase):
    """覆盖资源身份、失败记录、输出防覆盖和权限边界。@author owlzhangfq@gmail.com"""

    def test_remote_docker_is_rejected_before_resource_creation(self):
        sandbox = baseline.Sandbox(argparse.Namespace(), Path("/fyoung/tmp"))
        context = [{"Endpoints": {"docker": {"Host": "ssh://remote"}}}]
        with patch.dict(baseline.os.environ, {"DOCKER_HOST": ""}), patch.object(baseline, "docker", return_value=json.dumps(context)) as command:
            with self.assertRaisesRegex(baseline.BaselineError, "LOCAL_DOCKER_REQUIRED"):
                sandbox.start()
            command.assert_called_once_with("context", "inspect")

    def test_cli_rejects_existing_services_unbounded_load_and_outside_output(self):
        with tempfile.TemporaryDirectory(dir="/fyoung/tmp") as root:
            common = ["--server-image", "agentflow-demo-server", "--output", root + "/new"]
            for extra in (["--port", "8080"], ["--port", "8180"], ["--concurrency", "0"],
                          ["--concurrency", "1,1"], ["--applications", "10001"], ["--requests", "100000"],
                          ["--output", "/tmp/elsewhere"], ["--output", root]):
                with self.subTest(extra=extra), patch("sys.stderr"), self.assertRaises(SystemExit):
                    baseline.arguments(common + extra)

    def test_output_is_private_and_cannot_replace_an_existing_report(self):
        with tempfile.TemporaryDirectory(dir="/fyoung/tmp") as root:
            path = Path(root) / "report.json"
            baseline.write_json(path, {"result": "INCOMPLETE"})
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            with self.assertRaises(FileExistsError):
                baseline.write_json(path, {"result": "PASS"})
            self.assertEqual(json.loads(path.read_text())["result"], "INCOMPLETE")

    def test_cleanup_never_stops_container_with_a_different_identity(self):
        sandbox = baseline.Sandbox(argparse.Namespace(), Path("/fyoung/tmp"))
        sandbox.containers = {"server": "new-server", "database": "new-db"}
        def command(*args, **kwargs):
            if args[0] == "inspect":
                return json.dumps([{"Id": args[1], "Config": {"Labels": {baseline.LABEL: "someone-else"}}}])
            self.fail("Cleanup must not mutate resources with a different identity")
        with patch.object(baseline, "docker", side_effect=command):
            errors = sandbox.stop()
        self.assertEqual(len(errors), 2)
        self.assertTrue(all(row["error"] == "RESOURCE_IDENTITY_MISMATCH" for row in errors))

    def test_cleanup_stops_owned_resources_without_removing_containers_or_volumes(self):
        sandbox = baseline.Sandbox(argparse.Namespace(), Path("/fyoung/tmp"))
        sandbox.containers = {"server": "own-server", "database": "own-db"}
        stopped = []
        def command(*args, **kwargs):
            if args[0] == "inspect":
                return json.dumps([{"Id": args[1], "Config": {"Labels": {baseline.LABEL: sandbox.run_id}},
                                    "State": {"Running": args[1] not in stopped}}])
            self.assertEqual(args[:3], ("stop", "--timeout", "15"))
            stopped.append(args[3])
            return args[3]
        with patch.object(baseline, "docker", side_effect=command):
            self.assertEqual(sandbox.stop(), [])
        self.assertEqual(stopped, ["own-server", "own-db"])

    def test_failed_http_is_counted_without_retry_or_response_body_leak(self):
        samples = []
        client = baseline.Client(8192, samples)
        error = HTTPError(client.base, 503, "Unavailable", {}, BytesIO(b'{"secret":"never-log-me"}'))
        with patch.object(baseline, "build_opener") as opener:
            opener.return_value.open.side_effect = error
            with self.assertRaisesRegex(baseline.BaselineError, "UNEXPECTED_HTTP_503"):
                client.call("POST", "/applications", body={"secret": "private-body"}, phase="seed", operation="create")
            self.assertEqual(opener.return_value.open.call_count, 1)
        self.assertEqual(samples[0]["status"], 503)
        self.assertEqual(samples[0]["error"], "UNEXPECTED_HTTP_503")
        self.assertNotIn("never-log-me", json.dumps(samples))
        self.assertNotIn("private-body", json.dumps(samples))

    def test_redirect_is_refused_and_failed_latency_is_not_excluded(self):
        with self.assertRaisesRegex(baseline.BaselineError, "HTTP_REDIRECT_REJECTED"):
            baseline.NoRedirect().redirect_request(None, None, 302, None, None, "https://example.com")
        samples = [{"phase": "read_c4", "operation": "query", "milliseconds": duration,
                    "error": "HTTP_TRANSPORT_OR_JSON_FAILED" if duration == 15000 else None}
                   for duration in (10, 20, 30, 15000)]
        summary, = baseline.summarize(samples)
        self.assertEqual((summary["requests"], summary["failed"], summary["p50Ms"], summary["p95Ms"]), (4, 1, 20, 15000))
        self.assertEqual(baseline.percentile([], 95), None)

    def test_paging_rejects_a_repeated_cursor(self):
        client = baseline.Client(8192, [])
        with patch.object(client, "call", return_value={"items": [], "nextCursor": "same"}) as call:
            with self.assertRaisesRegex(baseline.BaselineError, "INVALID_PAGE_CURSOR"):
                client.page("/operations/applications")
            self.assertEqual(call.call_count, 2)

    def test_failed_worker_does_not_issue_queued_mutations(self):
        issued = []
        def action(index):
            issued.append(index)
            raise baseline.BaselineError("FIRST_REQUEST_FAILED")
        with self.assertRaisesRegex(baseline.BaselineError, "FIRST_REQUEST_FAILED"):
            baseline.parallel(action, range(100), 1)
        self.assertEqual(issued, [0])


if __name__ == "__main__":
    unittest.main()
