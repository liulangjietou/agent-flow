"""附件与数据库配套恢复的失败边界，不把单独数据库回执当作完整恢复。"""
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import production_attachments as attachments
import production_database as db


class PairedAttachmentRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir='/fyoung/tmp', prefix='agentflow-attachment-recovery-')
        self.root = Path(self.temp.name)
        self.source = db.private_directory(self.root / 'source')
        self.record = {'id': str(uuid.uuid4()), 'size': 3, 'sha256': hashlib.sha256(b'abc').hexdigest(), 'status': 'READY'}
        (self.source / (self.record['id'] + '.bin')).write_bytes(b'abc')
        self.client = Mock(config={'schema': 'public'})
        self.client.sql.return_value = json.dumps([self.record])
        self.client.sql.side_effect = lambda query, database=None: 'approval_attachment' if query.startswith('SELECT CASE') else self.client.sql.return_value

    def tearDown(self):
        self.temp.cleanup()

    def database_backup(self, client, output):
        archive = output / 'database.dump'; archive.write_bytes(b'PGDMP-content')
        manifest = {'format': db.FORMAT, 'createdAt': '2026-09-28T00:00:00+00:00', 'sourceDatabase': 'source',
                    'schema': 'public', 'postgresVersion': 170011, 'archive': 'database.dump', 'bytes': archive.stat().st_size,
                    'sha256': db.digest(archive), 'clientImage': 'sha256:' + '1' * 64}
        db.write_json(output / 'manifest.json', manifest)
        return {'sha256': manifest['sha256']}

    def bundle(self):
        output = db.private_directory(self.root / ('backup-' + uuid.uuid4().hex))
        with patch.object(db, 'backup', side_effect=self.database_backup):
            attachments.backup(self.client, self.source, output, True)
        return output

    def test_round_trip_preserves_original_and_new_restore_directory(self):
        bundle = self.bundle()
        self.assertEqual(len(attachments.check_bundle(bundle)[0]['files']), 1)
        output = db.private_directory(self.root / 'recovery')
        with patch.object(db, 'restore', return_value={'targetDatabase': 'agentflow_restore_new'}) as restore:
            result = attachments.restore(self.client, bundle, output)
        self.assertEqual(result['status'], 'DATABASE_AND_ATTACHMENTS_RESTORED')
        self.assertFalse(result['applicationStarted'])
        self.assertEqual((output / 'attachments' / (self.record['id'] + '.bin')).read_bytes(), b'abc')
        self.assertEqual((self.source / (self.record['id'] + '.bin')).read_bytes(), b'abc')
        restore.assert_called_once()
        with self.assertRaises(FileExistsError):
            attachments.copy_verified(self.source / (self.record['id'] + '.bin'), self.record,
                                      output / 'attachments' / (self.record['id'] + '.bin'))

    def test_missing_ready_or_changing_inventory_never_completes_backup(self):
        for reason in ['missing', 'changed']:
            with self.subTest(reason=reason):
                output = db.private_directory(self.root / reason)
                source = self.source / (self.record['id'] + '.bin')
                if reason == 'missing':
                    source.unlink()
                else:
                    source.write_bytes(b'abc')
                    self.client.sql.side_effect = ['approval_attachment', json.dumps([self.record]), 'approval_attachment', '[]']
                with patch.object(db, 'backup', side_effect=self.database_backup):
                    with self.assertRaises((OSError, db.RecoveryError)):
                        attachments.backup(self.client, self.source, output, True)
                self.assertFalse((output / 'manifest.json').exists())

    def test_backup_requires_explicit_stopped_writers_and_keeps_pending_recoverable_content(self):
        output = db.private_directory(self.root / 'guard')
        with self.assertRaises(db.RecoveryError):
            attachments.backup(self.client, self.source, output, False)
        self.client.sql.assert_not_called()
        self.record['status'] = 'UPLOADING'; self.client.sql.return_value = json.dumps([self.record])
        self.assertEqual(len(attachments.check_bundle(self.bundle())[0]['files']), 1)
        (self.source / (self.record['id'] + '.bin')).unlink()
        self.assertEqual(len(attachments.check_bundle(self.bundle())[0]['files']), 0)

    def test_corruption_symlink_and_path_injection_fail_before_restore_creates_a_database(self):
        for mutation in ['digest', 'link', 'path', 'duplicate', 'database']:
            with self.subTest(mutation=mutation):
                bundle = self.bundle(); path = bundle / 'files' / (self.record['id'] + '.bin')
                descriptor = bundle / 'manifest.json'; manifest = json.loads(descriptor.read_text())
                if mutation == 'digest': path.write_bytes(b'abd')
                if mutation == 'link': path.unlink(); path.symlink_to(self.source / (self.record['id'] + '.bin'))
                if mutation == 'path': manifest['files'][0]['id'] = '../../external'
                if mutation == 'duplicate': manifest['files'].append(dict(manifest['files'][0]))
                if mutation == 'database': manifest['databaseSha256'] = '0' * 64
                descriptor.write_text(json.dumps(manifest))
                with patch.object(db, 'restore') as restore:
                    with self.assertRaises((OSError, ValueError)):
                        attachments.restore(self.client, bundle, self.root)
                    restore.assert_not_called()

    def test_database_reference_mismatch_leaves_no_paired_success_receipt(self):
        bundle = self.bundle()
        self.client.sql.return_value = json.dumps([dict(self.record, id=str(uuid.uuid4()))])
        output = db.private_directory(self.root / 'failed-recovery')
        with patch.object(db, 'restore', return_value={'targetDatabase': 'retained_failed_target'}):
            with self.assertRaises(ValueError): attachments.restore(self.client, bundle, output)
        self.assertFalse((output / 'receipt.json').exists())
        self.assertTrue((output / 'database').exists())

    def test_invoice_originals_join_the_paired_backup_inventory(self):
        invoice = dict(self.record, id=str(uuid.uuid4()))
        (self.source / (invoice['id'] + '.bin')).write_bytes(b'abc')
        def sql(query, database=None):
            if query.startswith('SELECT CASE'):
                return 'stored_document_inventory'
            return json.dumps([self.record, invoice] if '.stored_document_inventory' in query else [self.record])
        self.client.sql.side_effect = sql
        bundle = self.bundle()
        self.assertEqual(len(attachments.check_bundle(bundle)[0]['files']), 2)
        output = db.private_directory(self.root / 'invoice-recovery')
        with patch.object(db, 'restore', return_value={'targetDatabase': 'invoice_restore_new'}):
            result = attachments.restore(self.client, bundle, output)
        self.assertEqual(result['files'], 2)
        self.assertEqual((output / 'attachments' / (invoice['id'] + '.bin')).read_bytes(), b'abc')


if __name__ == '__main__':
    unittest.main()
