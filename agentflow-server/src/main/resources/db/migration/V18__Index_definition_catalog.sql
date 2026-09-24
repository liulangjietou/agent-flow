-- 流程目录按不可变创建时间分页，避免草稿更新导致排序漂移。
CREATE INDEX idx_definition_tenant_created ON approval_definition (tenant_id, created_at DESC, id DESC);
