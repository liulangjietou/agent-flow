-- 放款退回只读取固定原成功修订，查询与独立财务决定分开持久化。
CREATE TABLE disbursement_return_check (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    advance_id VARCHAR(36) NOT NULL,
    resource_type VARCHAR(32) NOT NULL DEFAULT 'ADVANCE' CHECK (resource_type='ADVANCE'),
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
    CONSTRAINT fk_disbursement_check_advance FOREIGN KEY (tenant_id,resource_type,advance_id) REFERENCES finance_resource(tenant_id,resource_type,id),
    CONSTRAINT fk_disbursement_check_payment FOREIGN KEY (tenant_id,payment_id,payment_version) REFERENCES payment_operation_revision(tenant_id,operation_id,version),
    CONSTRAINT ck_disbursement_check_time CHECK (updated_at>=created_at),
    CONSTRAINT ck_disbursement_check_lease CHECK ((status='RUNNING' AND lease_until IS NOT NULL AND lease_until>updated_at) OR (status<>'RUNNING' AND lease_until IS NULL))
);
CREATE INDEX idx_disbursement_check_due ON disbursement_return_check(status,lease_until,created_at,id);
CREATE INDEX idx_disbursement_check_latest ON disbursement_return_check(tenant_id,advance_id,requested_by,created_at DESC,id DESC);
CREATE INDEX idx_disbursement_check_history ON disbursement_return_check(tenant_id,advance_id,updated_at DESC,id DESC);
CREATE TABLE disbursement_return_check_revision (
    tenant_id VARCHAR(64) NOT NULL,
    check_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,check_id,version),
    CONSTRAINT fk_disbursement_check_revision FOREIGN KEY (tenant_id,check_id) REFERENCES disbursement_return_check(tenant_id,id)
);
CREATE TABLE advance_disbursement_resolution (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    advance_id VARCHAR(36) NOT NULL,
    resource_type VARCHAR(32) NOT NULL DEFAULT 'ADVANCE' CHECK (resource_type='ADVANCE'),
    advance_version BIGINT NOT NULL CHECK (advance_version>1),
    check_id VARCHAR(36) NOT NULL,
    check_version BIGINT NOT NULL CHECK (check_version>1),
    outcome VARCHAR(24) NOT NULL CHECK (outcome IN ('CONFIRMED','PARTIALLY_RETURNED','RETURNED')),
    resolved_by VARCHAR(128) NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE NOT NULL,
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_disbursement_resolution_check UNIQUE (tenant_id,check_id),
    CONSTRAINT uq_disbursement_resolution_balance UNIQUE (tenant_id,advance_id,advance_version),
    CONSTRAINT fk_disbursement_resolution_balance FOREIGN KEY (tenant_id,resource_type,advance_id,advance_version) REFERENCES finance_resource_revision(tenant_id,resource_type,resource_id,version),
    CONSTRAINT fk_disbursement_resolution_check FOREIGN KEY (tenant_id,check_id,check_version) REFERENCES disbursement_return_check_revision(tenant_id,check_id,version),
    CONSTRAINT ck_disbursement_resolution_time CHECK (resolved_at>=observed_at)
);
CREATE INDEX idx_disbursement_resolution_advance ON advance_disbursement_resolution(tenant_id,advance_id,advance_version DESC);
-- 主动还款与银行退票都减少同一借款债权，共用法人内入款及贷方分录防重。
CREATE TABLE advance_receipt_credit (
    tenant_id VARCHAR(64) NOT NULL,
    legal_entity_id VARCHAR(36) NOT NULL,
    advance_id VARCHAR(36) NOT NULL,
    resource_type VARCHAR(32) NOT NULL DEFAULT 'ADVANCE' CHECK (resource_type='ADVANCE'),
    channel VARCHAR(16) NOT NULL CHECK (channel IN ('BANK_TRANSFER','CASH','PAYROLL')),
    transaction_reference VARCHAR(128) NOT NULL,
    voucher_reference VARCHAR(128) NOT NULL,
    entry_reference VARCHAR(128) NOT NULL,
    amount DECIMAL(17,2) NOT NULL CHECK (amount>0),
    currency VARCHAR(3) NOT NULL,
    repayment_id VARCHAR(36) NULL,
    disbursement_resolution_id VARCHAR(36) NULL,
    PRIMARY KEY (tenant_id,legal_entity_id,channel,transaction_reference),
    CONSTRAINT uq_advance_credit_posting UNIQUE (tenant_id,legal_entity_id,voucher_reference,entry_reference),
    CONSTRAINT uq_advance_credit_repayment UNIQUE (tenant_id,repayment_id),
    CONSTRAINT fk_advance_credit_balance FOREIGN KEY (tenant_id,resource_type,advance_id) REFERENCES finance_resource(tenant_id,resource_type,id),
    CONSTRAINT fk_advance_credit_repayment FOREIGN KEY (tenant_id,repayment_id) REFERENCES advance_repayment(tenant_id,id),
    CONSTRAINT fk_advance_credit_disbursement FOREIGN KEY (tenant_id,disbursement_resolution_id) REFERENCES advance_disbursement_resolution(tenant_id,id),
    CONSTRAINT ck_advance_credit_origin CHECK ((repayment_id IS NOT NULL AND disbursement_resolution_id IS NULL)
        OR (repayment_id IS NULL AND disbursement_resolution_id IS NOT NULL AND channel='BANK_TRANSFER'))
);
CREATE INDEX idx_advance_credit_disbursement ON advance_receipt_credit(tenant_id,disbursement_resolution_id);
INSERT INTO advance_receipt_credit(tenant_id,legal_entity_id,advance_id,channel,transaction_reference,voucher_reference,entry_reference,amount,currency,repayment_id)
SELECT tenant_id,legal_entity_id,advance_id,channel,transaction_reference,voucher_reference,entry_reference,amount,currency,id FROM advance_repayment;
