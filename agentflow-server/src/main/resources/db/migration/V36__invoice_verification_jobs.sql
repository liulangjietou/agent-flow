-- 冻结本人原件与发票输入版本，数据库复合外键同时约束身份和财务版本来源。
ALTER TABLE invoice_original ADD CONSTRAINT uq_invoice_original_verification UNIQUE (tenant_id,invoice_id,owner_id,id,sha256);

CREATE TABLE invoice_verification_job (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    invoice_id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    original_id VARCHAR(36) NOT NULL,
    original_digest VARCHAR(64) NOT NULL,
    invoice_version BIGINT NOT NULL CHECK (invoice_version > 0),
    resource_type VARCHAR(32) NOT NULL DEFAULT 'INVOICE' CHECK (resource_type='INVOICE'),
    input_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version BETWEEN 1 AND 3),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','REJECTED','UNAVAILABLE')),
    active_invoice_id VARCHAR(36),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_invoice_verification_active UNIQUE (tenant_id,active_invoice_id),
    CONSTRAINT ck_invoice_verification_active CHECK (
        (status IN ('QUEUED','RUNNING') AND active_invoice_id IS NOT NULL AND active_invoice_id=invoice_id AND completed_at IS NULL)
        OR (status IN ('SUCCEEDED','REJECTED','UNAVAILABLE') AND active_invoice_id IS NULL AND completed_at IS NOT NULL)),
    CONSTRAINT ck_invoice_verification_lease CHECK ((status='QUEUED' AND lease_until IS NULL AND version=1)
        OR (status='RUNNING' AND lease_until IS NOT NULL AND version=2)
        OR (status IN ('SUCCEEDED','REJECTED','UNAVAILABLE') AND lease_until IS NOT NULL AND version=3)),
    CONSTRAINT fk_invoice_verification_original FOREIGN KEY (tenant_id,invoice_id,owner_id,original_id,original_digest)
        REFERENCES invoice_original(tenant_id,invoice_id,owner_id,id,sha256),
    CONSTRAINT fk_invoice_verification_version FOREIGN KEY (tenant_id,resource_type,invoice_id,invoice_version)
        REFERENCES finance_resource_revision(tenant_id,resource_type,resource_id,version)
);
CREATE INDEX idx_invoice_verification_due ON invoice_verification_job(status,lease_until,created_at,id);
CREATE INDEX idx_invoice_verification_history ON invoice_verification_job(tenant_id,invoice_id,id);

-- 原始结果和每次状态转换保留；不保存远端错误正文和凭据。
CREATE TABLE invoice_verification_revision (
    tenant_id VARCHAR(64) NOT NULL,
    job_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL,
    state_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,job_id,version),
    CONSTRAINT fk_invoice_verification_revision FOREIGN KEY (tenant_id,job_id) REFERENCES invoice_verification_job(tenant_id,id)
);
