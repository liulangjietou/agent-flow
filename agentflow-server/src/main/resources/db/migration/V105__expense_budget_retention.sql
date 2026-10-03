-- 已释放预算允许新审批轮次携带原释放凭据重新冻结；已核销仍为终态。
ALTER TABLE budget_occupation DROP CONSTRAINT ck_budget_terminal;
ALTER TABLE budget_occupation ADD CONSTRAINT ck_budget_terminal
    CHECK (status <> 'CONSUMED' OR pending_operation_id IS NULL);
ALTER TABLE budget_operation ADD CONSTRAINT uq_budget_operation_report UNIQUE (tenant_id,id,report_id);
ALTER TABLE expense_report ADD CONSTRAINT uq_expense_retention_binding UNIQUE (tenant_id,id,application_id);

-- 只登记实际结束的新轮次，不为历史退回记录补造期限。
CREATE TABLE expense_budget_retention (
    tenant_id VARCHAR(64) NOT NULL,
    report_id VARCHAR(36) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    round_no INTEGER NOT NULL CHECK (round_no > 0),
    stopped_status VARCHAR(16) NOT NULL CHECK (stopped_status IN ('RETURNED','WITHDRAWN')),
    retained_at TIMESTAMP WITH TIME ZONE NOT NULL,
    retention_days INTEGER NOT NULL CHECK (retention_days BETWEEN 1 AND 3660),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('RETAINED','RECONCILING','RELEASE_QUEUED','RELEASED','SUPERSEDED','NO_FROZEN_BUDGET','RELEASE_REJECTED')),
    release_operation_id VARCHAR(36),
    version BIGINT NOT NULL CHECK (version > 0),
    state_json TEXT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id,report_id,round_no),
    CONSTRAINT fk_budget_retention_report FOREIGN KEY (tenant_id,report_id,application_id) REFERENCES expense_report(tenant_id,id,application_id),
    CONSTRAINT fk_budget_retention_round FOREIGN KEY (tenant_id,application_id,round_no)
        REFERENCES approval_submission_round(tenant_id,application_id,round_no),
    CONSTRAINT fk_budget_retention_operation FOREIGN KEY (tenant_id,release_operation_id,report_id)
        REFERENCES budget_operation(tenant_id,id,report_id),
    CONSTRAINT ck_budget_retention_deadline CHECK (expires_at > retained_at AND updated_at >= retained_at),
    CONSTRAINT ck_budget_retention_operation CHECK (
        (status IN ('RELEASE_QUEUED','RELEASED','RELEASE_REJECTED') AND release_operation_id IS NOT NULL)
        OR (status IN ('RETAINED','RECONCILING','SUPERSEDED','NO_FROZEN_BUDGET') AND release_operation_id IS NULL))
);
CREATE INDEX idx_budget_retention_due ON expense_budget_retention(status,expires_at,tenant_id,report_id,round_no);
CREATE TABLE expense_budget_retention_revision (
    tenant_id VARCHAR(64) NOT NULL,
    report_id VARCHAR(36) NOT NULL,
    round_no INTEGER NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    state_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,report_id,round_no,version),
    CONSTRAINT fk_budget_retention_revision FOREIGN KEY (tenant_id,report_id,round_no)
        REFERENCES expense_budget_retention(tenant_id,report_id,round_no)
);
