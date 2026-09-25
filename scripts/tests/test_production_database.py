"""生产备份格式、秘密传递、隔离恢复及失败保留的风险回归。"""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('production_database', Path(__file__).resolve().parents[1] / 'production_database.py')
db = importlib.util.module_from_spec(spec)
spec.loader.exec_module(db)


class ProductionDatabaseTest(unittest.TestCase):
    """恢复必须使用新数据库且只有完整成功后才能写回执。@author owlzhangfq@gmail.com"""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir='/fyoung/tmp', prefix='agentflow-production-backup-test-')
        self.root = Path(self.temp.name)
        self.password = self.root / 'password'
        self.password.write_text('fixture:secret\\literal\n')
        self.ca = self.root / 'ca.pem'; self.ca.write_text('fixture-ca')
        self.config = {'host': 'database.example', 'port': 5432, 'database': 'agentflow', 'schema': 'public', 'username': 'backup',
                       'passwordFile': str(self.password), 'caFile': str(self.ca), 'network': 'bridge',
                       'clientImage': 'sha256:' + '1' * 64}
        self.config_file = self.root / 'connection.json'; self.config_file.write_text(json.dumps(self.config))
        self.bundle = db.private_directory(self.root / 'bundle')
        self.archive = self.bundle / 'database.dump'; self.archive.write_bytes(b'PGDMPfixture')
        self.manifest = {'format': db.FORMAT, 'createdAt': '2026-09-24T12:00:00+00:00', 'sourceDatabase': 'agentflow', 'schema': 'public',
                         'postgresVersion': 170000, 'archive': 'database.dump', 'bytes': self.archive.stat().st_size,
                         'sha256': db.digest(self.archive), 'clientImage': self.config['clientImage']}
        self.save_manifest()

    def tearDown(self):
        self.temp.cleanup()

    def save_manifest(self):
        (self.bundle / 'manifest.json').write_text(json.dumps(self.manifest))

    def test_config_requires_exact_fields_immutable_image_and_valid_files(self):
        self.assertEqual(db.load_config(self.config_file), self.config)
        for key, bad in [('port', True), ('host', '--privileged'), ('clientImage', 'postgres:latest'),
                         ('caFile', '/does/not/exist'), ('database', 'postgres db')]:
            with self.subTest(key=key):
                value = dict(self.config); value[key] = bad; self.config_file.write_text(json.dumps(value))
                with self.assertRaises(db.RecoveryError): db.load_config(self.config_file)
        value = dict(self.config); value['password'] = 'do-not-accept-inline-secrets'
        self.config_file.write_text(json.dumps(value))
        with self.assertRaises(db.RecoveryError): db.load_config(self.config_file)

    def test_credentials_are_escaped_private_and_removed(self):
        with db.password_file(self.config) as path:
            self.assertEqual(path.read_text(), '*:*:*:*:fixture\\:secret\\\\literal\n')
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            self.assertEqual(path.parent.stat().st_mode & 0o777, 0o700)
        self.assertFalse(path.exists())
        for password in ('', 'two\nlines', 'nul\0value', 'x' * 4097):
            self.password.write_text(password)
            with self.assertRaises(db.RecoveryError):
                with db.password_file(self.config): pass

    def test_client_keeps_password_out_of_arguments_and_environment(self):
        with db.password_file(self.config) as path, patch.object(db.subprocess, 'run') as execute:
            execute.return_value = subprocess.CompletedProcess([], 0, b'170000\n', b'')
            client = db.PgClient(self.config, path, self.root)
            self.assertEqual(client.sql('SHOW server_version_num'), '170000')
            arguments = execute.call_args.args[0]
            self.assertIn('PGSSLMODE=verify-full', arguments)
            self.assertNotIn('PGPASSWORD=fixture:secret\\literal', arguments)
            self.assertNotIn(self.password.read_text().strip(), ' '.join(arguments))
            self.assertIn('--read-only', arguments)
            self.assertIn('--no-password', arguments)
            self.assertIn('ON_ERROR_STOP=1', arguments)

    def test_private_diagnostics_do_not_appear_in_exception(self):
        with db.password_file(self.config) as path, patch.object(db.subprocess, 'run') as execute:
            execute.return_value = subprocess.CompletedProcess([], 1, b'', b'private database detail')
            with self.assertRaises(db.RecoveryError) as failure:
                db.PgClient(self.config, path, self.root).sql('SHOW server_version_num')
            self.assertNotIn('private database detail', str(failure.exception))
            logs = list(self.root.glob('psql-*.log'))
            self.assertEqual(len(logs), 1)
            self.assertEqual(logs[0].stat().st_mode & 0o777, 0o600)

    def test_corrupt_incomplete_or_ambiguous_bundles_are_rejected(self):
        db.check_bundle(self.bundle)
        for key, value in [('archive', '../database.dump'), ('bytes', True), ('postgresVersion', 180000),
                           ('clientImage', 'postgres:17'), ('createdAt', '2026-09-24')]:
            old = self.manifest[key]; self.manifest[key] = value; self.save_manifest()
            with self.assertRaises(db.RecoveryError): db.check_bundle(self.bundle)
            self.manifest[key] = old; self.save_manifest()
        self.archive.write_bytes(b'PGDMPFixturE')
        with self.assertRaises(db.RecoveryError): db.check_bundle(self.bundle)
        self.archive.unlink(); self.archive.symlink_to(self.password)
        with self.assertRaises(db.RecoveryError): db.check_bundle(self.bundle)

    def test_existing_output_cannot_be_reused(self):
        with self.assertRaises(db.RecoveryError): db.private_directory(self.bundle)
        self.assertEqual(self.archive.read_bytes(), b'PGDMPfixture')

    def test_failed_backup_never_writes_completion_marker(self):
        output = db.private_directory(self.root / 'failed-backup')
        client = Mock(config=self.config)
        client.inspect.return_value = {'postgresVersion': 170000}
        client.run.side_effect = db.RecoveryError('CLIENT_FAILED', 'failed')
        with self.assertRaises(db.RecoveryError): db.backup(client, output)
        self.assertFalse((output / 'manifest.json').exists())
        self.assertEqual((output / 'database.dump').stat().st_mode & 0o777, 0o600)

    def test_restore_creates_random_new_target_before_single_transaction(self):
        output = db.private_directory(self.root / 'restored')
        client = Mock(config=self.config)
        client.sql.return_value = '170000'
        client.inspect.return_value = {'tables': sorted(db.REQUIRED_TABLES)}
        result = db.restore(client, self.bundle, output)
        target = result['targetDatabase']
        self.assertRegex(target, r'^agentflow_restore_[0-9a-f]{32}$')
        self.assertNotEqual(target, self.config['database'])
        self.assertEqual(client.run.call_args_list[0].args[0], 'createdb')
        restore = client.run.call_args_list[1].args
        self.assertIn('--single-transaction', restore)
        self.assertIn('--exit-on-error', restore)
        self.assertNotIn('--clean', restore)
        self.assertFalse(result['applicationStarted'])
        self.assertTrue((output / 'receipt.json').exists())

    def test_creation_race_or_restore_failure_preserves_intent_without_receipt(self):
        for failure_index in (0, 1):
            with self.subTest(failure_index=failure_index):
                output = db.private_directory(self.root / ('failure-' + str(failure_index)))
                client = Mock(config=self.config); client.sql.return_value = '170000'
                client.run.side_effect = ([db.RecoveryError('CLIENT_FAILED', 'existing database')] if failure_index == 0
                                          else ['', db.RecoveryError('CLIENT_FAILED', 'restore failed')])
                with self.assertRaises(db.RecoveryError): db.restore(client, self.bundle, output)
                self.assertTrue((output / 'intent.json').exists())
                self.assertFalse((output / 'receipt.json').exists())
                self.assertEqual(client.run.call_count, failure_index + 1)
                client.inspect.assert_not_called()

    def test_invalid_archive_fails_before_any_database_command(self):
        (self.bundle / 'manifest.json').unlink()
        client = Mock(config=self.config)
        with self.assertRaises(db.RecoveryError): db.restore(client, self.bundle, self.root)
        client.sql.assert_not_called(); client.run.assert_not_called()

    def test_wrong_client_major_is_rejected(self):
        client = db.PgClient(self.config, self.password, self.root)
        with patch.object(client, 'run', return_value='pg_dump (PostgreSQL) 18.1'):
            with self.assertRaises(db.RecoveryError): client.require_version()

    def test_custom_schema_is_used_for_inventory_and_migration_check(self):
        config = dict(self.config, schema='workflow_custom')
        client = db.PgClient(config, self.password, self.root)
        with patch.object(client, 'sql', side_effect=['170000', '\n'.join(db.REQUIRED_TABLES), 't']) as sql:
            client.inspect()
            self.assertIn("schemaname='workflow_custom'", sql.call_args_list[1].args[0])
            self.assertIn('"workflow_custom".flyway_schema_history', sql.call_args_list[2].args[0])
        self.manifest['schema'] = 'workflow_custom'; self.save_manifest()
        client = Mock(config=self.config); client.sql.return_value = '170000'
        client.inspect.return_value = {'tables': sorted(db.REQUIRED_TABLES)}
        output = db.private_directory(self.root / 'custom-restored')
        result = db.restore(client, self.bundle, output)
        client.inspect.assert_called_once_with(result['targetDatabase'], 'workflow_custom')

    def test_timeout_records_owned_client_and_does_not_claim_restore_success(self):
        client = db.PgClient(self.config, self.password, self.root)
        with patch.object(db.subprocess, 'run') as execute:
            execute.side_effect = [subprocess.TimeoutExpired('docker', 1800), subprocess.CompletedProcess([], 0)]
            with self.assertRaises(db.RecoveryError) as failure: client.sql('SHOW server_version_num')
            self.assertEqual(failure.exception.code, 'CLIENT_TIMEOUT')
            record = json.loads(next(self.root.glob('agentflow-pg-client-*-timeout.json')).read_text())
            self.assertTrue(record['stopConfirmed'])
            first = execute.call_args_list[0].args[0]
            name = first[first.index('--name') + 1]
            self.assertEqual(record['clientContainer'], name)
            self.assertEqual(execute.call_args_list[1].args[0], ['docker', 'stop', '--time', '5', name])

    def test_version_option_remains_first_for_native_postgres_early_exit(self):
        client = db.PgClient(self.config, self.password, self.root)
        with patch.object(db.subprocess, 'run') as execute:
            execute.return_value = subprocess.CompletedProcess([], 0, b'pg_dump (PostgreSQL) 17.9\n', b'')
            client.require_version()
            arguments = execute.call_args.args[0]
            self.assertEqual(arguments[arguments.index(self.config['clientImage']) + 1], '--version')

    def test_client_uses_credential_owner_without_relaxing_file_permissions(self):
        with db.password_file(self.config) as path, patch.object(db.subprocess, 'run') as execute:
            execute.return_value = subprocess.CompletedProcess([], 0, b'170000', b'')
            db.PgClient(self.config, path, self.root).sql('SHOW server_version_num')
            args = execute.call_args.args[0]
            self.assertIn('--user', args)
            self.assertEqual(args[args.index('--user') + 1], str(os.getuid()) + ':' + str(os.getgid()))
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
