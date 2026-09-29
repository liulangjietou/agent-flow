-- 全额取消报销另立调整账本，原消费、结算、付款与归档均不改写。
CREATE TABLE expense_resource_adjustment_preparation (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    report_id VARCHAR(36) NOT NULL,
    settlement_version BIGINT NOT NULL CHECK (settlement_version>0),
    consumption_id VARCHAR(36) NOT NULL,
    consumed_version BIGINT NOT NULL CHECK (consumed_version>0),
    accrual_reversal_id VARCHAR(36) NOT NULL,
    payment_returns_version BIGINT NULL,
    payment_voucher_id VARCHAR(36) NULL,
    payment_voucher_version BIGINT NULL,
    payment_voucher_reversal_id VARCHAR(36) NULL,
    requested_by VARCHAR(128) NOT NULL,
    input_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','RUNNING','READY','AUTHORIZED','UNAVAILABLE','VOIDED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE NULL,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT fk_resource_prepare_settlement FOREIGN KEY (tenant_id,report_id,settlement_version) REFERENCES expense_settlement_revision(tenant_id,report_id,version),
    CONSTRAINT fk_resource_prepare_consumption FOREIGN KEY (tenant_id,consumption_id,consumed_version) REFERENCES budget_operation_revision(tenant_id,operation_id,version),
    CONSTRAINT fk_resource_prepare_accrual FOREIGN KEY (tenant_id,accrual_reversal_id) REFERENCES voucher_reversal_record(tenant_id,id),
    CONSTRAINT fk_resource_prepare_returns FOREIGN KEY (tenant_id,report_id,payment_returns_version) REFERENCES expense_payment_returns_revision(tenant_id,report_id,version),
    CONSTRAINT fk_resource_prepare_payment_voucher FOREIGN KEY (tenant_id,payment_voucher_id,payment_voucher_version) REFERENCES voucher_operation_revision(tenant_id,operation_id,version),
    CONSTRAINT fk_resource_prepare_payment_reversal FOREIGN KEY (tenant_id,payment_voucher_reversal_id) REFERENCES voucher_reversal_record(tenant_id,id),
    CONSTRAINT ck_resource_prepare_payment CHECK ((payment_returns_version IS NULL AND payment_voucher_id IS NULL AND payment_voucher_version IS NULL AND payment_voucher_reversal_id IS NULL)
        OR (payment_returns_version IS NOT NULL AND payment_returns_version>0 AND payment_voucher_id IS NOT NULL AND payment_voucher_version IS NOT NULL AND payment_voucher_version>0)),
    CONSTRAINT ck_resource_prepare_time CHECK (updated_at>=created_at),
    CONSTRAINT ck_resource_prepare_lease CHECK ((status='RUNNING' AND lease_until IS NOT NULL AND lease_until>updated_at) OR (status<>'RUNNING' AND lease_until IS NULL))
);
CREATE INDEX idx_resource_prepare_due ON expense_resource_adjustment_preparation(status,lease_until,created_at,id);
CREATE INDEX idx_resource_prepare_latest ON expense_resource_adjustment_preparation(tenant_id,report_id,requested_by,created_at DESC,id DESC);
CREATE TABLE expense_resource_adjustment_preparation_revision (
    tenant_id VARCHAR(64) NOT NULL,
    preparation_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,preparation_id,version),
    CONSTRAINT fk_resource_prepare_revision FOREIGN KEY (tenant_id,preparation_id) REFERENCES expense_resource_adjustment_preparation(tenant_id,id)
);
CREATE TABLE expense_resource_adjustment (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    report_id VARCHAR(36) NOT NULL,
    round_no INTEGER NOT NULL CHECK (round_no>0),
    preparation_version BIGINT NOT NULL CHECK (preparation_version>1),
    input_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    status VARCHAR(24) NOT NULL CHECK (status IN ('WAITING_BUDGET','READY','APPLIED','REVIEW_REQUIRED','RETIRED')),
    active_report_id VARCHAR(36) NULL,
    budget_reversal_version BIGINT NULL CHECK (budget_reversal_version>=3),
    resources_reversed BOOLEAN NOT NULL,
    issue VARCHAR(64) NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_resource_adjustment_active UNIQUE (tenant_id,active_report_id),
    CONSTRAINT uq_resource_adjustment_source UNIQUE (tenant_id,id,report_id,round_no),
    CONSTRAINT fk_resource_adjustment_prepare FOREIGN KEY (tenant_id,id,preparation_version) REFERENCES expense_resource_adjustment_preparation_revision(tenant_id,preparation_id,version),
    CONSTRAINT fk_resource_adjustment_report FOREIGN KEY (tenant_id,report_id) REFERENCES expense_settlement(tenant_id,report_id),
    CONSTRAINT ck_resource_adjustment_time CHECK (updated_at>=created_at),
    CONSTRAINT ck_resource_adjustment_active CHECK ((status='RETIRED' AND active_report_id IS NULL) OR (status<>'RETIRED' AND active_report_id IS NOT NULL AND active_report_id=report_id)),
    CONSTRAINT ck_resource_adjustment_effect CHECK ((resources_reversed=FALSE OR (status IN ('APPLIED','REVIEW_REQUIRED') AND budget_reversal_version IS NOT NULL))
        AND (status<>'APPLIED' OR resources_reversed=TRUE) AND (status NOT IN ('READY','APPLIED') OR budget_reversal_version IS NOT NULL)
        AND (status NOT IN ('WAITING_BUDGET','RETIRED') OR budget_reversal_version IS NULL)),
    CONSTRAINT ck_resource_adjustment_issue CHECK ((status='REVIEW_REQUIRED' AND issue IS NOT NULL) OR (status<>'REVIEW_REQUIRED' AND issue IS NULL))
);
CREATE INDEX idx_resource_adjustment_ready ON expense_resource_adjustment(status,updated_at,id);
CREATE INDEX idx_resource_adjustment_history ON expense_resource_adjustment(tenant_id,report_id,created_at DESC,id DESC);
CREATE TABLE expense_resource_adjustment_revision (
    tenant_id VARCHAR(64) NOT NULL,
    adjustment_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,adjustment_id,version),
    CONSTRAINT fk_resource_adjustment_revision FOREIGN KEY (tenant_id,adjustment_id) REFERENCES expense_resource_adjustment(tenant_id,id)
);
CREATE TABLE budget_consumption_reversal_operation (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    consumption_id VARCHAR(36) NOT NULL,
    consumed_version BIGINT NOT NULL CHECK (consumed_version>0),
    input_json TEXT NOT NULL,
    command_digest VARCHAR(64) NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','EXECUTING','QUERYING','UNKNOWN','APPLIED','REJECTED','NOT_FOUND','EXPIRED','VOIDED','RECONCILING')),
    attempts INTEGER NOT NULL CHECK (attempts>=0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE NULL,
    lease_until TIMESTAMP WITH TIME ZONE NULL,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT fk_budget_reversal_adjustment FOREIGN KEY (tenant_id,id) REFERENCES expense_resource_adjustment(tenant_id,id),
    CONSTRAINT fk_budget_reversal_consumption FOREIGN KEY (tenant_id,consumption_id,consumed_version) REFERENCES budget_operation_revision(tenant_id,operation_id,version),
    CONSTRAINT ck_budget_reversal_time CHECK (updated_at>=created_at),
    CONSTRAINT ck_budget_reversal_lease CHECK ((status IN ('EXECUTING','QUERYING') AND lease_until IS NOT NULL AND lease_until>updated_at) OR (status NOT IN ('EXECUTING','QUERYING') AND lease_until IS NULL)),
    CONSTRAINT ck_budget_reversal_due CHECK ((status IN ('QUEUED','UNKNOWN') AND next_attempt_at IS NOT NULL AND next_attempt_at>=updated_at) OR (status NOT IN ('QUEUED','UNKNOWN') AND next_attempt_at IS NULL))
);
CREATE INDEX idx_budget_reversal_due ON budget_consumption_reversal_operation(status,next_attempt_at,lease_until,created_at,id);
CREATE TABLE budget_consumption_reversal_operation_revision (
    tenant_id VARCHAR(64) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,operation_id,version),
    CONSTRAINT fk_budget_reversal_revision FOREIGN KEY (tenant_id,operation_id) REFERENCES budget_consumption_reversal_operation(tenant_id,id)
);
ALTER TABLE expense_resource_adjustment ADD CONSTRAINT fk_resource_adjustment_budget_result FOREIGN KEY (tenant_id,id,budget_reversal_version)
    REFERENCES budget_consumption_reversal_operation_revision(tenant_id,operation_id,version);
CREATE TABLE expense_resource_adjustment_retirement (
    tenant_id VARCHAR(64) NOT NULL,
    adjustment_id VARCHAR(36) NOT NULL,
    before_version BIGINT NOT NULL CHECK (before_version>0),
    after_version BIGINT NOT NULL,
    stopped_budget_version BIGINT NOT NULL CHECK (stopped_budget_version>0),
    retired_by VARCHAR(128) NOT NULL,
    retired_at TIMESTAMP WITH TIME ZONE NOT NULL,
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,adjustment_id),
    CONSTRAINT ck_resource_retirement_version CHECK (after_version=before_version+1),
    CONSTRAINT fk_resource_retirement_before FOREIGN KEY (tenant_id,adjustment_id,before_version) REFERENCES expense_resource_adjustment_revision(tenant_id,adjustment_id,version),
    CONSTRAINT fk_resource_retirement_after FOREIGN KEY (tenant_id,adjustment_id,after_version) REFERENCES expense_resource_adjustment_revision(tenant_id,adjustment_id,version),
    CONSTRAINT fk_resource_retirement_budget FOREIGN KEY (tenant_id,adjustment_id,stopped_budget_version) REFERENCES budget_consumption_reversal_operation_revision(tenant_id,operation_id,version)
);
-- 原 CONSUMED 归属继续存在，独立反向事实以同租户调整和相邻资源修订绑定。
CREATE TABLE finance_consumption_reversal (
    tenant_id VARCHAR(64) NOT NULL,
    resource_type VARCHAR(32) NOT NULL CHECK (resource_type IN ('INVOICE','ADVANCE','PRIOR_REQUEST')),
    resource_id VARCHAR(36) NOT NULL,
    source_line INTEGER NOT NULL CHECK (source_line>=0),
    report_id VARCHAR(36) NOT NULL,
    round_no INTEGER NOT NULL CHECK (round_no>0),
    report_line INTEGER NOT NULL CHECK (report_line>=0),
    adjustment_id VARCHAR(36) NOT NULL,
    before_version BIGINT NOT NULL CHECK (before_version>0),
    after_version BIGINT NOT NULL,
    amount DECIMAL(17,2) NULL,
    currency VARCHAR(3) NULL,
    reversed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id,resource_type,resource_id,source_line,report_id,round_no,report_line),
    CONSTRAINT ck_consumption_reversal_version CHECK (after_version=before_version+1),
    CONSTRAINT ck_consumption_reversal_amount CHECK ((resource_type='INVOICE' AND source_line=0 AND report_line>0 AND amount IS NULL AND currency IS NULL)
        OR (resource_type='ADVANCE' AND source_line=0 AND report_line=0 AND amount IS NOT NULL AND amount>0 AND currency IS NOT NULL)
        OR (resource_type='PRIOR_REQUEST' AND source_line>0 AND report_line>0 AND amount IS NOT NULL AND amount>0 AND currency IS NOT NULL)),
    CONSTRAINT fk_consumption_reversal_adjustment FOREIGN KEY (tenant_id,adjustment_id,report_id,round_no) REFERENCES expense_resource_adjustment(tenant_id,id,report_id,round_no),
    CONSTRAINT fk_consumption_reversal_before FOREIGN KEY (tenant_id,resource_type,resource_id,before_version) REFERENCES finance_resource_revision(tenant_id,resource_type,resource_id,version),
    CONSTRAINT fk_consumption_reversal_after FOREIGN KEY (tenant_id,resource_type,resource_id,after_version) REFERENCES finance_resource_revision(tenant_id,resource_type,resource_id,version)
);
CREATE INDEX idx_consumption_reversal_adjustment ON finance_consumption_reversal(tenant_id,adjustment_id);
