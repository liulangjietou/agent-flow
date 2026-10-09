-- 关联人工确认、费用新版本和新预检，旧解释与旧检查仍然保留。
ALTER TABLE agent_precheck_explanation_run
    ADD CONSTRAINT uq_explanation_correction_report UNIQUE (tenant_id,id,report_id);
ALTER TABLE expense_precheck_job
    ADD CONSTRAINT uq_precheck_correction_versions UNIQUE (tenant_id,id,report_id,application_version,financial_version);
CREATE TABLE expense_correction (
    tenant_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    report_id VARCHAR(36) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    application_version BIGINT NOT NULL CHECK (application_version > 1),
    financial_version BIGINT NOT NULL CHECK (financial_version > 1),
    precheck_id VARCHAR(36) NOT NULL,
    applied_by VARCHAR(128) NOT NULL,
    applied_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id,run_id),
    UNIQUE (tenant_id,precheck_id),
    FOREIGN KEY (tenant_id,run_id,report_id) REFERENCES agent_precheck_explanation_run(tenant_id,id,report_id),
    FOREIGN KEY (tenant_id,report_id,application_id,applied_by) REFERENCES expense_report(tenant_id,id,application_id,employee_id),
    FOREIGN KEY (tenant_id,report_id,financial_version) REFERENCES expense_report_revision(tenant_id,report_id,financial_version),
    FOREIGN KEY (tenant_id,precheck_id,report_id,application_version,financial_version)
        REFERENCES expense_precheck_job(tenant_id,id,report_id,application_version,financial_version)
);
