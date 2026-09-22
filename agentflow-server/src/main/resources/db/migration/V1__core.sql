CREATE TABLE IF NOT EXISTS approval_application (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    business_no VARCHAR(128) NOT NULL,
    process_key VARCHAR(128) NOT NULL,
    definition_version BIGINT NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    title VARCHAR(256) NOT NULL,
    payload_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    round_no INT NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_application_business UNIQUE (tenant_id, business_no)
);

CREATE INDEX IF NOT EXISTS idx_application_status_updated
    ON approval_application (tenant_id, status, updated_at DESC);

CREATE TABLE IF NOT EXISTS audit_event (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    event_id VARCHAR(36) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    payload_json TEXT NOT NULL,
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_audit_event_id UNIQUE (tenant_id, event_id)
);

CREATE INDEX IF NOT EXISTS idx_audit_aggregate
    ON audit_event (tenant_id, aggregate_type, aggregate_id, occurred_at);

CREATE TABLE IF NOT EXISTS approval_definition (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    process_key VARCHAR(128) NOT NULL,
    name VARCHAR(256) NOT NULL,
    version BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    graph_json TEXT NOT NULL,
    published_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_definition_version UNIQUE (tenant_id, process_key, version)
);

CREATE INDEX IF NOT EXISTS idx_definition_status
    ON approval_definition (tenant_id, status, updated_at DESC);
