-- 报销退回有独立账本，原付款及原资源核销不重写。
CREATE TABLE expense_payment_returns (
    tenant_id VARCHAR(64) NOT NULL,
    report_id VARCHAR(36) NOT NULL,
    payment_id VARCHAR(36) NOT NULL,
    input_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    review_required BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id,report_id),
    CONSTRAINT uq_expense_returns_payment UNIQUE (tenant_id,payment_id),
    CONSTRAINT fk_expense_returns_settlement FOREIGN KEY (tenant_id,report_id) REFERENCES expense_settlement(tenant_id,report_id),
    CONSTRAINT fk_expense_returns_payment FOREIGN KEY (tenant_id,payment_id) REFERENCES payment_operation(tenant_id,id),
    CONSTRAINT ck_expense_returns_time CHECK (updated_at>=created_at)
);
CREATE TABLE expense_payment_returns_revision (
    tenant_id VARCHAR(64) NOT NULL,
    report_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,report_id,version),
    CONSTRAINT fk_expense_returns_revision FOREIGN KEY (tenant_id,report_id) REFERENCES expense_payment_returns(tenant_id,report_id)
);
CREATE TABLE expense_payment_return_check (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    report_id VARCHAR(36) NOT NULL,
    payment_id VARCHAR(36) NOT NULL,
    payment_version BIGINT NOT NULL CHECK (payment_version>0),
    requested_by VARCHAR(128) NOT NULL,
    input_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','RUNNING','CHECKED','RESOLVED','UNAVAILABLE','VOIDED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE NULL,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT fk_expense_return_check_report FOREIGN KEY (tenant_id,report_id) REFERENCES expense_payment_returns(tenant_id,report_id),
    CONSTRAINT fk_expense_return_check_payment FOREIGN KEY (tenant_id,payment_id,payment_version) REFERENCES payment_operation_revision(tenant_id,operation_id,version),
    CONSTRAINT ck_expense_return_check_time CHECK (updated_at>=created_at),
    CONSTRAINT ck_expense_return_check_lease CHECK ((status='RUNNING' AND lease_until IS NOT NULL AND lease_until>updated_at) OR (status<>'RUNNING' AND lease_until IS NULL))
);
CREATE INDEX idx_expense_return_check_due ON expense_payment_return_check(status,lease_until,created_at,id);
CREATE INDEX idx_expense_return_check_latest ON expense_payment_return_check(tenant_id,report_id,requested_by,created_at DESC,id DESC);
CREATE INDEX idx_expense_return_check_history ON expense_payment_return_check(tenant_id,report_id,updated_at DESC,id DESC);
CREATE TABLE expense_payment_return_check_revision (
    tenant_id VARCHAR(64) NOT NULL,
    check_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,check_id,version),
    CONSTRAINT fk_expense_return_check_revision FOREIGN KEY (tenant_id,check_id) REFERENCES expense_payment_return_check(tenant_id,id)
);
CREATE TABLE expense_payment_return_registration (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    report_id VARCHAR(36) NOT NULL,
    return_version BIGINT NOT NULL CHECK (return_version>1),
    settlement_version BIGINT NOT NULL CHECK (settlement_version>0),
    check_id VARCHAR(36) NOT NULL,
    check_version BIGINT NOT NULL CHECK (check_version>1),
    outcome VARCHAR(24) NOT NULL CHECK (outcome IN ('CONFIRMED','PARTIALLY_RETURNED','RETURNED')),
    registered_by VARCHAR(128) NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    registered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_expense_return_registration_check UNIQUE (tenant_id,check_id),
    CONSTRAINT uq_expense_return_registration_version UNIQUE (tenant_id,report_id,return_version),
    CONSTRAINT fk_expense_return_registration_ledger FOREIGN KEY (tenant_id,report_id,return_version) REFERENCES expense_payment_returns_revision(tenant_id,report_id,version),
    CONSTRAINT fk_expense_return_registration_settlement FOREIGN KEY (tenant_id,report_id,settlement_version) REFERENCES expense_settlement_revision(tenant_id,report_id,version),
    CONSTRAINT fk_expense_return_registration_check FOREIGN KEY (tenant_id,check_id,check_version) REFERENCES expense_payment_return_check_revision(tenant_id,check_id,version),
    CONSTRAINT ck_expense_return_registration_time CHECK (registered_at>=observed_at)
);
-- 原借款账本保留，新增共用索引防止同一公司入款跨业务重复登记。
CREATE TABLE finance_receipt_credit (
    tenant_id VARCHAR(64) NOT NULL,
    legal_entity_id VARCHAR(36) NOT NULL,
    business_id VARCHAR(36) NOT NULL,
    channel VARCHAR(16) NOT NULL CHECK (channel IN ('BANK_TRANSFER','CASH','PAYROLL')),
    transaction_reference VARCHAR(128) NOT NULL,
    voucher_reference VARCHAR(128) NOT NULL,
    entry_reference VARCHAR(128) NOT NULL,
    amount DECIMAL(17,2) NOT NULL CHECK (amount>0),
    currency VARCHAR(3) NOT NULL,
    repayment_id VARCHAR(36) NULL,
    disbursement_resolution_id VARCHAR(36) NULL,
    expense_registration_id VARCHAR(36) NULL,
    PRIMARY KEY (tenant_id,legal_entity_id,channel,transaction_reference),
    CONSTRAINT uq_finance_receipt_credit_posting UNIQUE (tenant_id,legal_entity_id,voucher_reference,entry_reference),
    CONSTRAINT uq_finance_receipt_credit_repayment UNIQUE (tenant_id,repayment_id),
    CONSTRAINT fk_finance_credit_repayment FOREIGN KEY (tenant_id,repayment_id) REFERENCES advance_repayment(tenant_id,id),
    CONSTRAINT fk_finance_credit_disbursement FOREIGN KEY (tenant_id,disbursement_resolution_id) REFERENCES advance_disbursement_resolution(tenant_id,id),
    CONSTRAINT fk_finance_credit_expense FOREIGN KEY (tenant_id,expense_registration_id) REFERENCES expense_payment_return_registration(tenant_id,id),
    CONSTRAINT ck_finance_receipt_credit_origin CHECK (
        (repayment_id IS NOT NULL AND disbursement_resolution_id IS NULL AND expense_registration_id IS NULL)
        OR (repayment_id IS NULL AND disbursement_resolution_id IS NOT NULL AND expense_registration_id IS NULL AND channel='BANK_TRANSFER')
        OR (repayment_id IS NULL AND disbursement_resolution_id IS NULL AND expense_registration_id IS NOT NULL AND channel='BANK_TRANSFER'))
);
INSERT INTO finance_receipt_credit(tenant_id,legal_entity_id,business_id,channel,transaction_reference,voucher_reference,entry_reference,amount,currency,repayment_id,disbursement_resolution_id)
SELECT tenant_id,legal_entity_id,advance_id,channel,transaction_reference,voucher_reference,entry_reference,amount,currency,repayment_id,disbursement_resolution_id FROM advance_receipt_credit;
