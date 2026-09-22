-- 新事件显式关联申请和动作；旧申请事件仅回填可验证的聚合身份，不推断动作。
ALTER TABLE audit_event ADD COLUMN application_id VARCHAR(36) NULL;
ALTER TABLE audit_event ADD COLUMN action VARCHAR(64) NULL;
UPDATE audit_event SET application_id = aggregate_id WHERE aggregate_type = 'Application';
CREATE INDEX idx_audit_application_time ON audit_event (tenant_id, application_id, occurred_at, id);
