-- 管理员按创建时间检索租户申请，只增加查询索引，不改写业务数据。
CREATE INDEX idx_application_tenant_created ON approval_application (tenant_id, created_at DESC, id DESC);
