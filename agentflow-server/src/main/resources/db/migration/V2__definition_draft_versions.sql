-- 未发布草稿不占用发布版本号；保留已发布版本及既有唯一约束。
ALTER TABLE approval_definition ALTER COLUMN version DROP NOT NULL;
UPDATE approval_definition SET version = NULL WHERE status = 'DRAFT';
