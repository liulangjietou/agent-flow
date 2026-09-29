-- 还款查询保留原放款修订；后台只读与人工确认分开，旧借款和余额修订不改写。
CREATE TABLE advance_repayment_check (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    advance_id VARCHAR(36) NOT NULL,
    resource_type VARCHAR(32) NOT NULL DEFAULT 'ADVANCE' CHECK (resource_type='ADVANCE'),
    payment_id VARCHAR(36) NOT NULL,
    payment_version BIGINT NOT NULL CHECK (payment_version>0),
    requested_by VARCHAR(128) NOT NULL,
    receipt_reference VARCHAR(128) NOT NULL,
    input_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','RUNNING','CHECKED','RECORDED','UNAVAILABLE','VOIDED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE NULL,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT fk_repayment_check_advance FOREIGN KEY (tenant_id,resource_type,advance_id) REFERENCES finance_resource(tenant_id,resource_type,id),
    CONSTRAINT fk_repayment_check_payment FOREIGN KEY (tenant_id,payment_id,payment_version) REFERENCES payment_operation_revision(tenant_id,operation_id,version),
    CONSTRAINT ck_repayment_check_time CHECK (updated_at>=created_at),
    CONSTRAINT ck_repayment_check_lease CHECK ((status='RUNNING' AND lease_until IS NOT NULL AND lease_until>updated_at) OR (status<>'RUNNING' AND lease_until IS NULL))
);
CREATE INDEX idx_repayment_check_due ON advance_repayment_check(status,lease_until,created_at,id);
CREATE INDEX idx_repayment_check_latest ON advance_repayment_check(tenant_id,advance_id,requested_by,created_at DESC,id DESC);
CREATE INDEX idx_repayment_check_receipt ON advance_repayment_check(tenant_id,advance_id,receipt_reference,updated_at DESC,id DESC);
CREATE TABLE advance_repayment_check_revision (
    tenant_id VARCHAR(64) NOT NULL,
    check_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,check_id,version),
    CONSTRAINT fk_repayment_check_revision FOREIGN KEY (tenant_id,check_id) REFERENCES advance_repayment_check(tenant_id,id)
);
-- 原收款、资金流水与会计分录各自互斥，同一凭据不能更换借款或编号重复冲减。
CREATE TABLE advance_repayment (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    advance_id VARCHAR(36) NOT NULL,
    resource_type VARCHAR(32) NOT NULL DEFAULT 'ADVANCE' CHECK (resource_type='ADVANCE'),
    advance_version BIGINT NOT NULL CHECK (advance_version>1),
    check_id VARCHAR(36) NOT NULL,
    check_version BIGINT NOT NULL CHECK (check_version>1),
    legal_entity_id VARCHAR(36) NOT NULL,
    receipt_reference VARCHAR(128) NOT NULL,
    channel VARCHAR(16) NOT NULL CHECK (channel IN ('BANK_TRANSFER','CASH','PAYROLL')),
    transaction_reference VARCHAR(128) NOT NULL,
    voucher_reference VARCHAR(128) NOT NULL,
    entry_reference VARCHAR(128) NOT NULL,
    amount DECIMAL(17,2) NOT NULL CHECK (amount>0),
    currency VARCHAR(3) NOT NULL,
    recorded_by VARCHAR(128) NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_repayment_check UNIQUE (tenant_id,check_id),
    CONSTRAINT uq_repayment_receipt UNIQUE (tenant_id,legal_entity_id,receipt_reference),
    CONSTRAINT uq_repayment_funding UNIQUE (tenant_id,legal_entity_id,channel,transaction_reference),
    CONSTRAINT uq_repayment_entry UNIQUE (tenant_id,legal_entity_id,voucher_reference,entry_reference),
    CONSTRAINT fk_repayment_advance_revision FOREIGN KEY (tenant_id,resource_type,advance_id,advance_version) REFERENCES finance_resource_revision(tenant_id,resource_type,resource_id,version),
    CONSTRAINT fk_repayment_checked_revision FOREIGN KEY (tenant_id,check_id,check_version) REFERENCES advance_repayment_check_revision(tenant_id,check_id,version)
);
CREATE INDEX idx_repayment_advance ON advance_repayment(tenant_id,advance_id,recorded_at DESC,id DESC);
