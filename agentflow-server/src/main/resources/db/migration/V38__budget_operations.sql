-- 单据台账与外部操作分开保存；未知副作用不能通过删除活动任务再次冻结。
CREATE TABLE budget_occupation (
    tenant_id VARCHAR(64) NOT NULL,
    report_id VARCHAR(36) NOT NULL,
    employee_id VARCHAR(128) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    target_digest VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('UNFUNDED','FROZEN','RELEASED','CONSUMED')),
    pending_operation_id VARCHAR(36),
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,report_id),
    CONSTRAINT fk_budget_report_owner FOREIGN KEY (tenant_id,report_id,application_id,employee_id)
        REFERENCES expense_report(tenant_id,id,application_id,employee_id),
    CONSTRAINT ck_budget_terminal CHECK (status NOT IN ('RELEASED','CONSUMED') OR pending_operation_id IS NULL)
);
CREATE TABLE budget_occupation_revision (
    tenant_id VARCHAR(64) NOT NULL,
    report_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL,
    state_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,report_id,version),
    CONSTRAINT fk_budget_occupation_revision FOREIGN KEY (tenant_id,report_id) REFERENCES budget_occupation(tenant_id,report_id)
);

CREATE TABLE budget_operation (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    report_id VARCHAR(36) NOT NULL,
    financial_version BIGINT NOT NULL CHECK (financial_version > 0),
    input_json TEXT NOT NULL,
    command_digest VARCHAR(64) NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','EXECUTING','UNKNOWN','QUERYING','APPLIED','REJECTED')),
    attempts INTEGER NOT NULL CHECK (attempts >= 0),
    active_report_id VARCHAR(36),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    lease_until TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_budget_active UNIQUE (tenant_id,active_report_id),
    CONSTRAINT fk_budget_operation_occupation FOREIGN KEY (tenant_id,report_id) REFERENCES budget_occupation(tenant_id,report_id),
    CONSTRAINT fk_budget_operation_version FOREIGN KEY (tenant_id,report_id,financial_version)
        REFERENCES expense_report_revision(tenant_id,report_id,financial_version),
    CONSTRAINT ck_budget_operation_active CHECK (
        (status IN ('QUEUED','EXECUTING','UNKNOWN','QUERYING') AND active_report_id IS NOT NULL AND active_report_id=report_id)
        OR (status IN ('APPLIED','REJECTED') AND active_report_id IS NULL)),
    CONSTRAINT ck_budget_operation_schedule CHECK (
        (status IN ('QUEUED','UNKNOWN') AND next_attempt_at IS NOT NULL AND lease_until IS NULL)
        OR (status IN ('EXECUTING','QUERYING') AND next_attempt_at IS NULL AND lease_until IS NOT NULL AND attempts>0)
        OR (status IN ('APPLIED','REJECTED') AND next_attempt_at IS NULL AND lease_until IS NULL))
);
CREATE INDEX idx_budget_operation_due ON budget_operation(status,next_attempt_at,lease_until,created_at,id);
CREATE INDEX idx_budget_operation_history ON budget_operation(tenant_id,report_id,created_at,id);
CREATE TABLE budget_operation_revision (
    tenant_id VARCHAR(64) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL,
    state_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,operation_id,version),
    CONSTRAINT fk_budget_operation_revision FOREIGN KEY (tenant_id,operation_id) REFERENCES budget_operation(tenant_id,id)
);
