"""真实进程验收必须隔离数据库，不能继承项目或机器的默认库。"""
import importlib.util
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest

spec = importlib.util.spec_from_file_location("expense_runtime", Path(__file__).parents[1] / "check-precheck-explanation.py")
runtime_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime_module)


class RuntimeIsolationTest(unittest.TestCase):
    def test_synthetic_finance_covers_real_editor_policy_guidance(self):
        fixture = object.__new__(runtime_module.Sources)
        fixture.valid_seconds = 600
        result, status = fixture.finance("expense-policy-guidance", {"requestId": "synthetic-guidance", "data": {"managedPolicy": None}})
        self.assertEqual(status, 200)
        self.assertEqual(result["requestId"], "synthetic-guidance")
        self.assertEqual(result["data"]["policyVersion"], 1)
        self.assertEqual(result["data"]["constraints"]["effect"], "ALLOW")
        self.assertIsNone(result["data"]["selection"])

    def test_runtime_explicitly_owns_database_and_syncs_commits_before_hard_stop(self):
        with tempfile.TemporaryDirectory(dir="/fyoung/tmp") as directory:
            runtime = runtime_module.Runtime("unused-java", Path(directory), SimpleNamespace(server=SimpleNamespace(server_port=12345)))
            self.assertEqual(runtime.settings.get("spring.datasource.url"), "jdbc:h2:file:" + str(Path(directory) / "data/agentflow") + ";WRITE_DELAY=0")
            self.assertEqual(runtime.settings.get("spring.datasource.driver-class-name"), "org.h2.Driver")
            self.assertEqual(runtime.settings.get("spring.datasource.username"), "sa")
            self.assertEqual(runtime.settings.get("spring.datasource.password"), "")


if __name__ == "__main__":
    unittest.main()
