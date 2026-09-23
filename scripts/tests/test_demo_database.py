"""覆盖备份完整性、目标保护及恢复失败时不启动应用的风险边界。"""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("demo_database", Path(__file__).resolve().parents[1] / "demo_database.py")
db = importlib.util.module_from_spec(spec)
spec.loader.exec_module(db)


class DemoDatabaseTest(unittest.TestCase):
    """隔离恢复不得覆盖现有数据或把未完成归档声明为有效。@author owlzhangfq@gmail.com"""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir="/fyoung/tmp", prefix="agentflow-backup-test-")
        self.root = Path(self.temp.name)
        self.bundle = db.private_directory(self.root / "bundle")
        self.archive = self.bundle / "database.dump"
        self.archive.write_bytes(b"PGDMPexample")
        self.manifest = {"format": db.FORMAT, "createdAt": "2026-09-23T15:00:00+00:00", "sourceProject": "agentflow-demo",
                         "postgresVersion": 170011, "database": "agentflow", "archive": "database.dump",
                         "bytes": self.archive.stat().st_size, "sha256": db.sha256(self.archive),
                         "images": {name: "sha256:" + str(index + 1) * 64 for index, name in enumerate(db.SERVICES)}}
        self.save_manifest()

    def tearDown(self):
        self.temp.cleanup()

    def save_manifest(self):
        (self.bundle / "manifest.json").write_text(json.dumps(self.manifest))

    def test_checksum_rejects_same_size_corruption_and_truncation(self):
        db.check_bundle(self.bundle)
        for content in (b"PGDMPExamplE", b"PGDMPex"):
            self.archive.write_bytes(content)
            with self.assertRaises(db.RecoveryError):
                db.check_bundle(self.bundle)

    def test_invalid_manifest_and_incomplete_dump_are_not_recoverable(self):
        for key, value in (("archive", "../other.dump"), ("bytes", True), ("postgresVersion", 160000),
                           ("images", {"database": "postgres:latest"}), ("sourceProject", "--privileged"), ("createdAt", "2026-09-23")):
            with self.subTest(key=key):
                original = self.manifest[key]; self.manifest[key] = value; self.save_manifest()
                with self.assertRaises(db.RecoveryError):
                    db.check_bundle(self.bundle)
                self.manifest[key] = original
        (self.bundle / "manifest.json").unlink()
        with self.assertRaises(db.RecoveryError):
            db.check_bundle(self.bundle)

    def test_archive_symlink_and_existing_output_are_rejected(self):
        self.archive.rename(self.root / "outside.dump")
        self.archive.symlink_to(self.root / "outside.dump")
        with self.assertRaises(db.RecoveryError):
            db.check_bundle(self.bundle)
        with self.assertRaises(db.RecoveryError):
            db.private_directory(self.bundle)
        self.assertEqual((self.root / "outside.dump").read_bytes(), b"PGDMPexample")

    def test_new_output_and_json_are_private(self):
        output = db.private_directory(self.root / "private")
        db.write_json(output / "record.json", {"state": "test"})
        self.assertEqual(output.stat().st_mode & 0o777, 0o700)
        self.assertEqual((output / "record.json").stat().st_mode & 0o777, 0o600)

    def test_original_project_and_existing_volume_fail_before_mutation(self):
        with patch.object(db, "docker") as command:
            with self.assertRaises(db.RecoveryError):
                db.require_unused_target("agentflow-demo", self.manifest, 8290)
            command.assert_not_called()
        calls = []
        def fake(*args, **kwargs):
            calls.append(args)
            return "recover-copy_demo-postgres\n" if args == ("volume", "ls", "--format", "{{.Name}}") else ""
        with patch.object(db, "docker", fake), self.assertRaises(db.RecoveryError):
            db.require_unused_target("recover-copy", self.manifest, 8290)
        self.assertFalse(any("create" in args or "run" in args or "up" in args for args in calls))

    def test_corrupt_archive_stops_before_docker_and_intent(self):
        self.archive.write_bytes(b"PGDMPbroken!")
        output = db.private_directory(self.root / "restore")
        with patch.object(db, "docker") as command, self.assertRaises(db.RecoveryError):
            db.restore(self.bundle, "recover-copy", 8290, output)
        command.assert_not_called()
        self.assertEqual(list(output.iterdir()), [])

    def test_volume_race_does_not_start_database(self):
        output = db.private_directory(self.root / "race")
        calls = []
        def fake(*args, **kwargs):
            calls.append(args)
            return json.dumps([{"Labels": {db.RUN_LABEL: "someone-else"}}]) if args[:2] == ("volume", "inspect") else ""
        with patch.object(db, "require_unused_target", return_value="recover-copy_demo-postgres"), patch.object(db, "docker", fake), self.assertRaises(db.RecoveryError):
            db.restore(self.bundle, "recover-copy", 8290, output)
        self.assertFalse(any(args[0] == "run" for args in calls))

    def test_failed_transaction_stops_seed_and_never_starts_application(self):
        output = db.private_directory(self.root / "failure")
        calls = []
        def fake(*args, **kwargs):
            calls.append(args)
            if args[:2] == ("volume", "inspect"):
                run_id = json.loads((output / "intent.json").read_text())["runId"]
                return json.dumps([{"Labels": {db.RUN_LABEL: run_id}}])
            if args[0] == "run":
                return "new-seed-id"
            if "pg_restore" in args:
                self.assertIn("--single-transaction", args); self.assertIn("--exit-on-error", args)
                self.assertNotIn("--clean", args)
                raise db.RecoveryError("DOCKER_FAILED", "Restore failed")
            return ""
        with patch.object(db, "require_unused_target", return_value="recover-copy_demo-postgres"), patch.object(db, "docker", fake), patch.object(db, "sql", return_value="0"), self.assertRaises(db.RecoveryError):
            db.restore(self.bundle, "recover-copy", 8290, output)
        self.assertIn(("stop", "--timeout", "30", "new-seed-id"), calls)
        self.assertFalse(any(args[0] == "compose" for args in calls))
        self.assertFalse((output / "receipt.json").exists())

    def test_backup_failure_does_not_write_completion_manifest(self):
        output = db.private_directory(self.root / "partial")
        services = {name: {"Id": name, "Image": image} for name, image in self.manifest["images"].items()}
        def failed(*args, **kwargs):
            kwargs["stdout"].write(b"partial")
            raise db.RecoveryError("DOCKER_FAILED", "Backup interrupted")
        with patch.object(db, "source_services", return_value=(services, 170011)), patch.object(db, "docker", failed), self.assertRaises(db.RecoveryError):
            db.backup("agentflow-demo", output)
        self.assertFalse((output / "manifest.json").exists())

    def test_source_uses_canonical_name_and_checks_labels_among_historical_containers(self):
        def fake(*args, **kwargs):
            if args[0] == "ps":
                return "current-server\nhistorical-server\n"
            self.assertEqual(args[0], "inspect")
            service = args[1].removeprefix("agentflow-demo-").removesuffix("-1")
            self.assertIn(service, db.SERVICES)
            return json.dumps([{"Id": service, "Image": self.manifest["images"][service], "State": {"Running": True},
                                "Config": {"Env": ["POSTGRES_DB=agentflow", "POSTGRES_USER=agentflow", "AGENTFLOW_DEMO_AUTH=true"],
                                           "Labels": {"com.docker.compose.project": "agentflow-demo", "com.docker.compose.service": service}}}])
        with patch.object(db, "docker", fake), patch.object(db, "sql", return_value="170011"):
            services, version = db.source_services("agentflow-demo")
        self.assertEqual(services["server"]["Id"], "server")
        self.assertEqual(version, 170011)


if __name__ == "__main__":
    unittest.main()
