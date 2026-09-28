-- 预检绑定真实单据归属和已存在的财务版本，不写入审批或财务通过标记。
ALTER TABLE expense_report ADD CONSTRAINT uq_expense_precheck_owner UNIQUE (tenant_id,id,application_id,employee_id);
CREATE TABLE expense_precheck_job (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    report_id VARCHAR(36) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    employee_id VARCHAR(128) NOT NULL,
    application_version BIGINT NOT NULL CHECK (application_version > 0),
    financial_version BIGINT NOT NULL CHECK (financial_version > 0),
    attempt_no BIGINT NOT NULL CHECK (attempt_no > 0),
    input_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version BETWEEN 1 AND 3),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','RUNNING','READY','BLOCKED','UNAVAILABLE')),
    active_report_id VARCHAR(36),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_expense_precheck_attempt UNIQUE (tenant_id,report_id,attempt_no),
    CONSTRAINT uq_expense_precheck_active UNIQUE (tenant_id,active_report_id),
    CONSTRAINT ck_expense_precheck_active CHECK (
        (status IN ('QUEUED','RUNNING') AND active_report_id IS NOT NULL AND active_report_id=report_id AND completed_at IS NULL)
        OR (status IN ('READY','BLOCKED','UNAVAILABLE') AND active_report_id IS NULL AND completed_at IS NOT NULL)),
    CONSTRAINT ck_expense_precheck_lease CHECK ((status='QUEUED' AND lease_until IS NULL AND version=1)
        OR (status='RUNNING' AND lease_until IS NOT NULL AND version=2)
        OR (status IN ('READY','BLOCKED','UNAVAILABLE') AND lease_until IS NOT NULL AND version=3)),
    CONSTRAINT fk_expense_precheck_owner FOREIGN KEY (tenant_id,report_id,application_id,employee_id)
        REFERENCES expense_report(tenant_id,id,application_id,employee_id),
    CONSTRAINT fk_expense_precheck_version FOREIGN KEY (tenant_id,report_id,financial_version)
        REFERENCES expense_report_revision(tenant_id,report_id,financial_version)
);
CREATE INDEX idx_expense_precheck_due ON expense_precheck_job(status,lease_until,created_at,id);
CREATE INDEX idx_expense_precheck_history ON expense_precheck_job(tenant_id,report_id,id);
CREATE INDEX idx_invoice_verification_version ON invoice_verification_job(tenant_id,invoice_id,invoice_version,status);

CREATE TABLE expense_precheck_revision (
    tenant_id VARCHAR(64) NOT NULL,
    job_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL,
    state_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,job_id,version),
    CONSTRAINT fk_expense_precheck_revision FOREIGN KEY (tenant_id,job_id) REFERENCES expense_precheck_job(tenant_id,id)
);
