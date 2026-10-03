-- 预检解释独立持久化，不修改旧检查、申请、费用金额或审批结果。
ALTER TABLE expense_precheck_job ADD CONSTRAINT uq_precheck_explanation_source UNIQUE (tenant_id,id,report_id);
CREATE TABLE agent_precheck_explanation_run (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    report_id VARCHAR(36) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    requested_by VARCHAR(128) NOT NULL,
    application_version BIGINT NOT NULL CHECK (application_version > 0),
    financial_version BIGINT NOT NULL CHECK (financial_version > 0),
    precheck_id VARCHAR(36) NOT NULL,
    precheck_attempt BIGINT NOT NULL CHECK (precheck_attempt > 0),
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL,
    active_report_id VARCHAR(36),
    context_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_precheck_explanation_active UNIQUE (tenant_id,active_report_id),
    CONSTRAINT fk_precheck_explanation_owner FOREIGN KEY (tenant_id,report_id,application_id,requested_by)
        REFERENCES expense_report(tenant_id,id,application_id,employee_id),
    CONSTRAINT fk_precheck_explanation_check FOREIGN KEY (tenant_id,precheck_id,report_id) REFERENCES expense_precheck_job(tenant_id,id,report_id),
    CONSTRAINT fk_precheck_explanation_version FOREIGN KEY (tenant_id,report_id,financial_version)
        REFERENCES expense_report_revision(tenant_id,report_id,financial_version),
    CONSTRAINT ck_precheck_explanation_stage CHECK (
        (status='QUEUED' AND version=1) OR (status='RUNNING' AND version=2)
        OR (status IN ('COMPLETED','FAILED') AND version=3)
        OR (status IN ('ADOPTED','DISMISSED') AND version=4)),
    CONSTRAINT ck_precheck_explanation_active CHECK (
        (status IN ('QUEUED','RUNNING') AND active_report_id IS NOT NULL AND active_report_id=report_id)
        OR (status IN ('COMPLETED','FAILED','ADOPTED','DISMISSED') AND active_report_id IS NULL)),
    CONSTRAINT ck_precheck_explanation_lease CHECK (
        (status='RUNNING' AND lease_until IS NOT NULL) OR (status<>'RUNNING' AND lease_until IS NULL))
);
CREATE INDEX idx_precheck_explanation_due ON agent_precheck_explanation_run(status,lease_until,created_at,id);
CREATE INDEX idx_precheck_explanation_report ON agent_precheck_explanation_run(tenant_id,report_id,created_at,id);

CREATE TABLE agent_precheck_explanation_transition (
    tenant_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    run_version BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,run_id,run_version),
    CONSTRAINT fk_precheck_explanation_transition FOREIGN KEY (tenant_id,run_id) REFERENCES agent_precheck_explanation_run(tenant_id,id)
);
