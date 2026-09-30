-- 原供应商付款与原核销保留，真实回款另建累计账本和具名登记。
CREATE TABLE supplier_payment_returns (
    tenant_id VARCHAR(64) NOT NULL,
    payment_id VARCHAR(36) NOT NULL,
    payment_version BIGINT NOT NULL CHECK (payment_version>0),
    request_id VARCHAR(36) NOT NULL,
    legal_entity_id VARCHAR(36) NOT NULL,
    supplier_reference VARCHAR(128) NOT NULL,
    payable_reference VARCHAR(128) NOT NULL,
    input_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    review_required BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id,payment_id),
    CONSTRAINT fk_supplier_return_original FOREIGN KEY (tenant_id,payment_id,payment_version) REFERENCES supplier_payment_revision(tenant_id,operation_id,version),
    CONSTRAINT ck_supplier_return_time CHECK (updated_at>=created_at)
);
CREATE INDEX idx_supplier_return_payable ON supplier_payment_returns(tenant_id,legal_entity_id,supplier_reference,payable_reference,review_required);

CREATE TABLE supplier_payment_returns_revision (
    tenant_id VARCHAR(64) NOT NULL,
    payment_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,payment_id,version),
    CONSTRAINT fk_supplier_return_revision FOREIGN KEY (tenant_id,payment_id) REFERENCES supplier_payment_returns(tenant_id,payment_id)
);

CREATE TABLE supplier_payment_return_check (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    payment_id VARCHAR(36) NOT NULL,
    payment_version BIGINT NOT NULL CHECK (payment_version>0),
    requested_by VARCHAR(128) NOT NULL,
    input_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','RUNNING','CHECKED','RESOLVED','UNAVAILABLE','VOIDED')),
    active_marker BOOLEAN NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE NULL,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT fk_supplier_return_check_ledger FOREIGN KEY (tenant_id,payment_id) REFERENCES supplier_payment_returns(tenant_id,payment_id),
    CONSTRAINT fk_supplier_return_check_bank FOREIGN KEY (tenant_id,payment_id,payment_version) REFERENCES supplier_payment_revision(tenant_id,operation_id,version),
    CONSTRAINT uq_supplier_return_active_check UNIQUE (tenant_id,payment_id,requested_by,active_marker),
    CONSTRAINT ck_supplier_return_check_active CHECK ((status IN ('QUEUED','RUNNING') AND active_marker IS NOT NULL AND active_marker=TRUE) OR (status NOT IN ('QUEUED','RUNNING') AND active_marker IS NULL)),
    CONSTRAINT ck_supplier_return_check_lease CHECK ((status='RUNNING' AND lease_until IS NOT NULL AND lease_until>updated_at) OR (status<>'RUNNING' AND lease_until IS NULL)),
    CONSTRAINT ck_supplier_return_check_time CHECK (updated_at>=created_at)
);
CREATE INDEX idx_supplier_return_check_due ON supplier_payment_return_check(status,lease_until,created_at);
CREATE INDEX idx_supplier_return_check_latest ON supplier_payment_return_check(tenant_id,payment_id,requested_by,created_at DESC);

CREATE TABLE supplier_payment_return_check_revision (
    tenant_id VARCHAR(64) NOT NULL,
    check_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,check_id,version),
    CONSTRAINT fk_supplier_return_check_revision FOREIGN KEY (tenant_id,check_id) REFERENCES supplier_payment_return_check(tenant_id,id)
);

CREATE TABLE supplier_payment_return_registration (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    payment_id VARCHAR(36) NOT NULL,
    before_version BIGINT NOT NULL CHECK (before_version>0),
    return_version BIGINT NOT NULL,
    check_id VARCHAR(36) NOT NULL,
    check_version BIGINT NOT NULL CHECK (check_version>1),
    outcome VARCHAR(24) NOT NULL CHECK (outcome IN ('CONFIRMED','PARTIALLY_RETURNED','RETURNED')),
    registered_by VARCHAR(128) NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    registered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_supplier_return_registration_version UNIQUE (tenant_id,payment_id,return_version),
    CONSTRAINT uq_supplier_return_registration_check UNIQUE (tenant_id,check_id),
    CONSTRAINT fk_supplier_return_registration_before FOREIGN KEY (tenant_id,payment_id,before_version) REFERENCES supplier_payment_returns_revision(tenant_id,payment_id,version),
    CONSTRAINT fk_supplier_return_registration_after FOREIGN KEY (tenant_id,payment_id,return_version) REFERENCES supplier_payment_returns_revision(tenant_id,payment_id,version),
    CONSTRAINT fk_supplier_return_registration_check FOREIGN KEY (tenant_id,check_id,check_version) REFERENCES supplier_payment_return_check_revision(tenant_id,check_id,version),
    CONSTRAINT ck_supplier_return_registration_version CHECK (return_version=before_version+1),
    CONSTRAINT ck_supplier_return_registration_time CHECK (registered_at>=observed_at)
);

-- 银行资金先登记，供应商 ERP 调整仍是独立事实；旧业务仍强制保留完整贷方分录。
ALTER TABLE finance_receipt_credit ADD COLUMN supplier_registration_id VARCHAR(36) NULL;
ALTER TABLE finance_receipt_credit ALTER COLUMN voucher_reference DROP NOT NULL;
ALTER TABLE finance_receipt_credit ALTER COLUMN entry_reference DROP NOT NULL;
ALTER TABLE finance_receipt_credit DROP CONSTRAINT ck_finance_receipt_credit_origin;
ALTER TABLE finance_receipt_credit ADD CONSTRAINT fk_finance_credit_supplier FOREIGN KEY (tenant_id,supplier_registration_id) REFERENCES supplier_payment_return_registration(tenant_id,id);
ALTER TABLE finance_receipt_credit ADD CONSTRAINT ck_finance_receipt_credit_origin CHECK (
    (supplier_registration_id IS NULL AND voucher_reference IS NOT NULL AND entry_reference IS NOT NULL AND (
        (repayment_id IS NOT NULL AND disbursement_resolution_id IS NULL AND expense_registration_id IS NULL)
        OR (repayment_id IS NULL AND disbursement_resolution_id IS NOT NULL AND expense_registration_id IS NULL AND channel='BANK_TRANSFER')
        OR (repayment_id IS NULL AND disbursement_resolution_id IS NULL AND expense_registration_id IS NOT NULL AND channel='BANK_TRANSFER')))
    OR (supplier_registration_id IS NOT NULL AND repayment_id IS NULL AND disbursement_resolution_id IS NULL AND expense_registration_id IS NULL
        AND channel='BANK_TRANSFER' AND voucher_reference IS NULL AND entry_reference IS NULL)
);
