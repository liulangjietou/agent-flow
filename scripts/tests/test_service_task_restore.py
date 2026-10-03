"""配套恢复证据比对只允许无语义的独立约束输出顺序变化。"""

from pathlib import Path
import runpy
import tempfile
import unittest


canonical_content = runpy.run_path(str(Path(__file__).resolve().parents[1] / "check-service-task-restore.py"))["canonical_content"]


class RestoreComparisonTest(unittest.TestCase):
    first = 'ALTER TABLE "PUBLIC"."A" ADD CONSTRAINT "PUBLIC"."FIRST" UNIQUE ("ID");\n'
    second = 'ALTER TABLE "PUBLIC"."B" ADD CONSTRAINT "PUBLIC"."SECOND" CHECK ("VERSION">0);\n'
    data = 'CREATE TABLE "A"("ID" INT);\nINSERT INTO "A" VALUES (1);\n'

    def compare(self, source, target):
        """所有临时比对文件都位于用户指定根目录。"""
        with tempfile.TemporaryDirectory(prefix="service-restore-comparison-", dir="/fyoung/tmp") as directory:
            left, right = Path(directory) / "source.sql", Path(directory) / "restored.sql"
            left.write_text(source)
            right.write_text(target)
            return canonical_content(left) == canonical_content(right)

    def test_independent_constraint_order_does_not_report_data_loss(self):
        self.assertTrue(self.compare(self.data + self.first + self.second, self.data + self.second + self.first))

    def test_data_and_schema_changes_are_not_ignored(self):
        for replacement in [self.data.replace('VALUES (1)', 'VALUES (2)'), self.data.replace('INT', 'VARCHAR(20)')]:
            with self.subTest(replacement=replacement):
                self.assertFalse(self.compare(self.data + self.first, replacement + self.first))

    def test_changed_missing_or_duplicate_constraints_fail(self):
        for changed in [self.first.replace('UNIQUE', 'PRIMARY KEY'), '', self.first + self.first]:
            with self.subTest(changed=changed):
                self.assertFalse(self.compare(self.data + self.first, self.data + changed))

    def test_multi_line_constraints_and_data_order_are_preserved(self):
        original = 'ALTER TABLE "PUBLIC"."A" ADD CONSTRAINT "PUBLIC"."M"\nCHECK ("ID">0);\n'
        moved = 'CHECK ("ID">0);\nALTER TABLE "PUBLIC"."A" ADD CONSTRAINT "PUBLIC"."M"\n'
        self.assertFalse(self.compare(self.data + original, self.data + moved))
        self.assertFalse(self.compare('INSERT INTO A VALUES (1);\nINSERT INTO A VALUES (2);\n',
                                      'INSERT INTO A VALUES (2);\nINSERT INTO A VALUES (1);\n'))


if __name__ == "__main__":
    unittest.main()
