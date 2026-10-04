-- 只记录原提交已回滚后的明确票据占用拒绝，不补造此前失败请求。
CREATE TABLE expense_submission_rejection (
    tenant_id VARCHAR(64) NOT NULL,
    attempt_digest VARCHAR(64) NOT NULL,
    report_id VARCHAR(36) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    employee_id VARCHAR(128) NOT NULL,
    precheck_id VARCHAR(36) NOT NULL,
    application_version BIGINT NOT NULL CHECK (application_version>0),
    financial_version BIGINT NOT NULL CHECK (financial_version>0),
    round_no INTEGER NOT NULL CHECK (round_no>0),
    legal_entity_id VARCHAR(36) NOT NULL,
    department_id VARCHAR(36) NOT NULL,
    reason_code VARCHAR(64) NOT NULL CHECK (reason_code='INVOICE_OCCUPIED'),
    public_error_code VARCHAR(64) NOT NULL CHECK (public_error_code IN ('INVOICE_OCCUPIED','RESOURCES_CHANGED')),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,attempt_digest),
    CONSTRAINT fk_expense_rejection_report FOREIGN KEY (tenant_id,report_id,application_id,employee_id)
        REFERENCES expense_report(tenant_id,id,application_id,employee_id),
    CONSTRAINT fk_expense_rejection_precheck FOREIGN KEY (tenant_id,precheck_id) REFERENCES expense_precheck_job(tenant_id,id),
    CONSTRAINT fk_expense_rejection_revision FOREIGN KEY (tenant_id,report_id,financial_version)
        REFERENCES expense_report_revision(tenant_id,report_id,financial_version)
);
CREATE INDEX idx_expense_rejection_round ON expense_submission_rejection(tenant_id,report_id,round_no,occurred_at);

-- 迁移启用时点用于披露覆盖范围，旧时期没有记录不能被展示为零次拦截。
CREATE TABLE expense_reporting_coverage (
    fact_type VARCHAR(64) PRIMARY KEY,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO expense_reporting_coverage(fact_type) VALUES('DUPLICATE_SUBMISSION');
