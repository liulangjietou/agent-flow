-- 审计事件分批留存归档表与归档执行台账，保留完整审计事实同时控制热表体量。
CREATE TABLE audit_event_archive (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    event_id VARCHAR(36) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    application_id VARCHAR(36) NULL,
    action VARCHAR(64) NULL,
    actor_id VARCHAR(128) NULL,
    payload_json TEXT NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    archived_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_audit_archive_event_id UNIQUE (tenant_id, event_id)
);

CREATE INDEX idx_audit_archive_tenant_time
    ON audit_event_archive (tenant_id, occurred_at DESC);

CREATE TABLE audit_retention_run (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    cutoff_at TIMESTAMP WITH TIME ZONE NOT NULL,
    archived_count INT NOT NULL,
    triggered_by VARCHAR(128) NOT NULL,
    executed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_audit_retention_run_lookup
    ON audit_retention_run (tenant_id, executed_at DESC);
