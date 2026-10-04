-- 风险提示保留自己的运行及来源版本，不改写费用、审批或付款记录。
CREATE TABLE agent_expense_risk_run (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    report_id VARCHAR(36) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    round_no INT NOT NULL CHECK (round_no > 0),
    requested_by VARCHAR(128) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    authentication_kind VARCHAR(32) NOT NULL,
    login_reference VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL,
    active_report_id VARCHAR(36),
    context_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_expense_risk_active UNIQUE (tenant_id,active_report_id),
    CONSTRAINT fk_expense_risk_report FOREIGN KEY (tenant_id,report_id,application_id)
        REFERENCES expense_report(tenant_id,id,application_id),
    CONSTRAINT fk_expense_risk_round FOREIGN KEY (tenant_id,application_id,round_no)
        REFERENCES approval_submission_round(tenant_id,application_id,round_no),
    CONSTRAINT ck_expense_risk_stage CHECK (
        (status='QUEUED' AND version=1) OR (status='RUNNING' AND version=2)
        OR (status IN ('COMPLETED','FAILED') AND version=3)
        OR (status IN ('ADOPTED','DISMISSED') AND version=4)),
    CONSTRAINT ck_expense_risk_active CHECK (
        (status IN ('QUEUED','RUNNING') AND active_report_id IS NOT NULL AND active_report_id=report_id)
        OR (status IN ('COMPLETED','FAILED','ADOPTED','DISMISSED') AND active_report_id IS NULL)),
    CONSTRAINT ck_expense_risk_lease CHECK (
        (status='RUNNING' AND lease_until IS NOT NULL) OR (status<>'RUNNING' AND lease_until IS NULL)),
    CONSTRAINT ck_expense_risk_login CHECK (
        (authentication_kind='DEMO_LOGIN' AND LENGTH(login_reference)=64)
        OR (authentication_kind='OIDC_SESSION' AND LENGTH(login_reference)=36))
);
CREATE INDEX idx_expense_risk_due ON agent_expense_risk_run(status,lease_until,created_at,id);
CREATE INDEX idx_expense_risk_history ON agent_expense_risk_run(tenant_id,report_id,round_no,created_at,id);

-- 来源单据必须保留原财务版本和原审批轮次，不能通过替换 JSON 借用另一份单据。
CREATE TABLE agent_expense_risk_document (
    tenant_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    ordinal INT NOT NULL CHECK (ordinal BETWEEN 1 AND 20),
    report_id VARCHAR(36) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    application_version BIGINT NOT NULL CHECK (application_version > 0),
    financial_version BIGINT NOT NULL CHECK (financial_version > 0),
    round_no INT NOT NULL CHECK (round_no > 0),
    snapshot_digest VARCHAR(64) NOT NULL CHECK (LENGTH(snapshot_digest)=64),
    PRIMARY KEY (tenant_id,run_id,ordinal),
    CONSTRAINT uq_expense_risk_document UNIQUE (tenant_id,run_id,report_id),
    CONSTRAINT fk_expense_risk_document_run FOREIGN KEY (tenant_id,run_id) REFERENCES agent_expense_risk_run(tenant_id,id),
    CONSTRAINT fk_expense_risk_document_report FOREIGN KEY (tenant_id,report_id,application_id)
        REFERENCES expense_report(tenant_id,id,application_id),
    CONSTRAINT fk_expense_risk_document_version FOREIGN KEY (tenant_id,report_id,financial_version)
        REFERENCES expense_report_revision(tenant_id,report_id,financial_version),
    CONSTRAINT fk_expense_risk_document_round FOREIGN KEY (tenant_id,application_id,round_no)
        REFERENCES approval_submission_round(tenant_id,application_id,round_no)
);

CREATE TABLE agent_expense_risk_transition (
    tenant_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    run_version BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,run_id,run_version),
    CONSTRAINT fk_expense_risk_transition FOREIGN KEY (tenant_id,run_id) REFERENCES agent_expense_risk_run(tenant_id,id)
);
