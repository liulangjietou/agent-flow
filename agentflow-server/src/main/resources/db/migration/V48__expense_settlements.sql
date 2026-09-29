-- 银行到账与本地核销分开持久化，资源已核销后预算重试不得再次消耗。
CREATE TABLE expense_settlement (
    tenant_id VARCHAR(64) NOT NULL,
    report_id VARCHAR(36) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    business_type VARCHAR(32) NOT NULL DEFAULT 'EXPENSE' CHECK (business_type='EXPENSE'),
    round_no INTEGER NOT NULL CHECK (round_no>0),
    application_version BIGINT NOT NULL CHECK (application_version>0),
    financial_version BIGINT NOT NULL CHECK (financial_version>0),
    voucher_operation_id VARCHAR(36),
    voucher_kind VARCHAR(32) NOT NULL DEFAULT 'EXPENSE_ACCRUAL' CHECK (voucher_kind='EXPENSE_ACCRUAL'),
    payment_operation_id VARCHAR(36),
    input_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    status VARCHAR(24) NOT NULL CHECK (status IN ('QUEUED','BLOCKED','BUDGET_PENDING','BUDGET_REJECTED','SETTLED','REVIEW_REQUIRED')),
    resources_consumed BOOLEAN NOT NULL,
    budget_operation_id VARCHAR(36),
    issue VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id,report_id),
    CONSTRAINT fk_settlement_report FOREIGN KEY (tenant_id,report_id) REFERENCES expense_report(tenant_id,id),
    CONSTRAINT fk_settlement_application FOREIGN KEY (tenant_id,business_type,report_id,application_id)
        REFERENCES approval_application(tenant_id,business_type,business_id,id),
    CONSTRAINT fk_settlement_voucher FOREIGN KEY (tenant_id,voucher_operation_id,application_id,round_no,voucher_kind)
        REFERENCES voucher_operation(tenant_id,id,application_id,round_no,kind),
    CONSTRAINT fk_settlement_payment FOREIGN KEY (tenant_id,payment_operation_id) REFERENCES payment_operation(tenant_id,id),
    CONSTRAINT fk_settlement_budget FOREIGN KEY (tenant_id,budget_operation_id) REFERENCES budget_operation(tenant_id,id),
    CONSTRAINT ck_settlement_time CHECK (updated_at>=created_at),
    CONSTRAINT ck_settlement_issue CHECK ((status IN ('BLOCKED','BUDGET_REJECTED','REVIEW_REQUIRED') AND issue IS NOT NULL)
        OR (status IN ('QUEUED','BUDGET_PENDING','SETTLED') AND issue IS NULL)),
    CONSTRAINT ck_settlement_budget CHECK (
        (status IN ('QUEUED','BLOCKED') AND budget_operation_id IS NULL)
        OR (status IN ('BUDGET_PENDING','BUDGET_REJECTED','SETTLED') AND resources_consumed=TRUE AND budget_operation_id IS NOT NULL)
        OR (status='REVIEW_REQUIRED' AND (resources_consumed=TRUE OR budget_operation_id IS NULL)))
);
CREATE INDEX idx_expense_settlement_due ON expense_settlement(status,updated_at,tenant_id,report_id);
CREATE TABLE expense_settlement_revision (
    tenant_id VARCHAR(64) NOT NULL,
    report_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    state_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,report_id,version),
    CONSTRAINT fk_settlement_revision FOREIGN KEY (tenant_id,report_id) REFERENCES expense_settlement(tenant_id,report_id)
);
