"""验证生产密钥读取和进程退出，不连接数据库或启动容器。"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ENTRYPOINT = Path(__file__).resolve().parents[2] / "deploy/production/entrypoint.sh"


class ProductionEntrypointTest(unittest.TestCase):
    """密钥不得被解释为 shell，失败不得继续启动。

    @author owlzhangfq@gmail.com
    """

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="production-entrypoint-", dir="/fyoung/tmp")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.java = self.root / "java"
        self.java.write_text("""#!/usr/bin/env python3
import hashlib,json,os,sys
print(json.dumps({'assist':hashlib.sha256(os.environ.get('AGENTFLOW_ASSIST_API_KEY','').encode()).hexdigest(),'arguments':sys.argv[1:], 'database':hashlib.sha256(os.environ.get('AGENTFLOW_DATASOURCE_PASSWORD','').encode()).hexdigest(), 'oidc':hashlib.sha256(os.environ.get('AGENTFLOW_OIDC_CLIENT_SECRET','').encode()).hexdigest()}))
sys.exit(int(os.environ.get('FIXTURE_EXIT_CODE','0')))
""")
        self.java.chmod(0o700)
        self.environment = {"PATH": str(self.root) + os.pathsep + os.environ["PATH"]}

    def secret(self, name, value):
        path = self.root / name
        path.write_text(value)
        path.chmod(0o600)
        return str(path)

    def invoke(self, **environment):
        return subprocess.run(["/bin/sh", str(ENTRYPOINT), "--schema=migrate"],
                              env={**self.environment, **environment}, capture_output=True, text=True, timeout=5)

    def test_passes_literal_secret_values_and_original_arguments(self):
        database = "db-'$(touch /fyoung/tmp/never-created-by-secret)'-$VALUE"
        oidc = "oidc-`uname`-special;&"
        result = self.invoke(AGENTFLOW_DATASOURCE_PASSWORD_FILE=self.secret("database", database + "\n"),
                             AGENTFLOW_OIDC_CLIENT_SECRET_FILE=self.secret("oidc", oidc + "\n"),
                             AGENTFLOW_ASSIST_API_KEY_FILE=self.secret("assist", "synthetic-model-key\n"))
        self.assertEqual(result.returncode, 0)
        observed = json.loads(result.stdout)
        self.assertEqual(observed["database"], hashlib.sha256(database.encode()).hexdigest())
        self.assertEqual(observed["oidc"], hashlib.sha256(oidc.encode()).hexdigest())
        self.assertEqual(observed["assist"], hashlib.sha256(b"synthetic-model-key").hexdigest())
        self.assertEqual(observed["arguments"], ["-jar", "/app/app.jar", "--schema=migrate"])
        self.assertEqual(result.stderr, "")

    def test_missing_or_empty_secret_prevents_process_start(self):
        for variable in ("AGENTFLOW_DATASOURCE_PASSWORD_FILE", "AGENTFLOW_OIDC_CLIENT_SECRET_FILE", "AGENTFLOW_ASSIST_API_KEY_FILE"):
            for value in (str(self.root / "missing"), self.secret("empty", "\n")):
                with self.subTest(variable=variable, empty=value.endswith("empty")):
                    result = self.invoke(**{variable: value})
                    self.assertEqual(result.returncode, 2)
                    self.assertEqual(result.stdout, "")

    def test_ambiguous_environment_and_file_secrets_fail_without_leaking_values(self):
        for variable in ("AGENTFLOW_DATASOURCE_PASSWORD", "AGENTFLOW_OIDC_CLIENT_SECRET", "AGENTFLOW_ASSIST_API_KEY"):
            with self.subTest(variable=variable):
                result = self.invoke(**{variable: "private-environment-value",
                                        variable + "_FILE": self.secret("secret", "private-file-value")})
                self.assertEqual(result.returncode, 2)
                self.assertEqual(result.stdout, "")
                self.assertNotIn("private-", result.stderr)

    def test_preserves_java_exit_status(self):
        self.assertEqual(self.invoke(FIXTURE_EXIT_CODE="7").returncode, 7)


if __name__ == "__main__":
    unittest.main()
