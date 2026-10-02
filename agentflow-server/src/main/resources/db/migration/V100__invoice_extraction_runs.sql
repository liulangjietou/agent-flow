-- 抽取建议独立于税务查验；原件、归属和活动任务由复合外键及唯一键约束。
CREATE TABLE agent_invoice_extraction_run (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    invoice_id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    original_id VARCHAR(36) NOT NULL,
    original_digest VARCHAR(64) NOT NULL,
    original_format VARCHAR(16) NOT NULL CHECK (original_format IN ('XML','PNG','JPEG','PDF','OFD')),
    original_bytes BIGINT NOT NULL CHECK (original_bytes BETWEEN 1 AND 20971520),
    page_count INTEGER NOT NULL CHECK (page_count BETWEEN 1 AND 10),
    method VARCHAR(32) NOT NULL,
    target_digest VARCHAR(64),
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL,
    context_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE,
    active_invoice_id VARCHAR(36),
    CONSTRAINT uq_invoice_extraction_tenant UNIQUE (tenant_id,id),
    CONSTRAINT uq_invoice_extraction_active UNIQUE (tenant_id,active_invoice_id),
    CONSTRAINT fk_invoice_extraction_original FOREIGN KEY (tenant_id,invoice_id,owner_id,original_id,original_digest)
        REFERENCES invoice_original(tenant_id,invoice_id,owner_id,id,sha256),
    CONSTRAINT ck_invoice_extraction_method CHECK (
        (method='STRUCTURED_XML' AND original_format='XML' AND target_digest IS NULL)
        OR (method='MODEL' AND target_digest IS NOT NULL AND LENGTH(target_digest)=64)),
    CONSTRAINT ck_invoice_extraction_pages CHECK (original_format IN ('PDF','OFD') OR page_count=1),
    CONSTRAINT ck_invoice_extraction_stage CHECK (
        (status='QUEUED' AND version=1) OR (status='RUNNING' AND version=2)
        OR (status IN ('COMPLETED','FAILED') AND version=3)
        OR (status IN ('CONFIRMED','DISMISSED') AND version=4)),
    CONSTRAINT ck_invoice_extraction_active CHECK (
        (status IN ('QUEUED','RUNNING') AND active_invoice_id IS NOT NULL AND active_invoice_id=invoice_id)
        OR (status IN ('COMPLETED','FAILED','CONFIRMED','DISMISSED') AND active_invoice_id IS NULL)),
    CONSTRAINT ck_invoice_extraction_lease CHECK (
        (status='RUNNING' AND lease_until IS NOT NULL) OR (status<>'RUNNING' AND lease_until IS NULL))
);
CREATE INDEX idx_invoice_extraction_due ON agent_invoice_extraction_run(status,lease_until,created_at,id);
CREATE INDEX idx_invoice_extraction_history ON agent_invoice_extraction_run(tenant_id,invoice_id,created_at,id);

CREATE TABLE agent_invoice_extraction_transition (
    tenant_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    run_version BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,run_id,run_version),
    CONSTRAINT fk_invoice_extraction_transition FOREIGN KEY (tenant_id,run_id)
        REFERENCES agent_invoice_extraction_run(tenant_id,id)
);
