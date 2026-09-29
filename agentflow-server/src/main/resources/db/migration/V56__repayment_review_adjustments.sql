-- 原还款复核独立排队，所有金融历史及旧余额快照保持不变。
CREATE TABLE repayment_review_check (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    advance_id VARCHAR(36) NOT NULL,
    repayment_id VARCHAR(36) NOT NULL,
    requested_by VARCHAR(128) NOT NULL,
    input_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','RUNNING','CHECKED','RESOLVED','UNAVAILABLE','VOIDED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE NULL,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT fk_repayment_review_original FOREIGN KEY (tenant_id,repayment_id) REFERENCES advance_repayment(tenant_id,id),
    CONSTRAINT ck_repayment_review_time CHECK (updated_at>=created_at),
    CONSTRAINT ck_repayment_review_lease CHECK ((status='RUNNING' AND lease_until IS NOT NULL AND lease_until>updated_at) OR (status<>'RUNNING' AND lease_until IS NULL))
);
CREATE INDEX idx_repayment_review_due ON repayment_review_check(status,lease_until,created_at,id);
CREATE INDEX idx_repayment_review_latest ON repayment_review_check(tenant_id,repayment_id,requested_by,created_at DESC,id DESC);
CREATE INDEX idx_repayment_review_history ON repayment_review_check(tenant_id,repayment_id,updated_at DESC,id DESC);
CREATE TABLE repayment_review_check_revision (
    tenant_id VARCHAR(64) NOT NULL,
    check_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,check_id,version),
    CONSTRAINT fk_repayment_review_revision FOREIGN KEY (tenant_id,check_id) REFERENCES repayment_review_check(tenant_id,id)
);

-- 逐笔裁决关联原查询和实际余额版本，不覆盖原还款事实。
CREATE TABLE advance_repayment_resolution (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    advance_id VARCHAR(36) NOT NULL,
    resource_type VARCHAR(32) NOT NULL DEFAULT 'ADVANCE' CHECK (resource_type='ADVANCE'),
    advance_version BIGINT NOT NULL CHECK (advance_version>1),
    repayment_id VARCHAR(36) NOT NULL,
    check_id VARCHAR(36) NOT NULL,
    check_version BIGINT NOT NULL CHECK (check_version>1),
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('CONFIRMED','RETURNED')),
    resolved_by VARCHAR(128) NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE NOT NULL,
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_repayment_resolution_check UNIQUE (tenant_id,check_id),
    CONSTRAINT fk_repayment_resolution_original FOREIGN KEY (tenant_id,repayment_id) REFERENCES advance_repayment(tenant_id,id),
    CONSTRAINT fk_repayment_resolution_balance FOREIGN KEY (tenant_id,resource_type,advance_id,advance_version) REFERENCES finance_resource_revision(tenant_id,resource_type,resource_id,version),
    CONSTRAINT fk_repayment_resolution_check FOREIGN KEY (tenant_id,check_id,check_version) REFERENCES repayment_review_check_revision(tenant_id,check_id,version),
    CONSTRAINT ck_repayment_resolution_time CHECK (resolved_at>=observed_at)
);
CREATE INDEX idx_repayment_resolution_latest ON advance_repayment_resolution(tenant_id,repayment_id,advance_version DESC);

-- 真正退回仅追加一次；后续复核同一退回不重复增加欠款或占用同一外部资金/分录。
CREATE TABLE advance_repayment_return (
    tenant_id VARCHAR(64) NOT NULL,
    repayment_id VARCHAR(36) NOT NULL,
    resolution_id VARCHAR(36) NOT NULL,
    legal_entity_id VARCHAR(36) NOT NULL,
    channel VARCHAR(16) NOT NULL CHECK (channel IN ('BANK_TRANSFER','CASH','PAYROLL')),
    transaction_reference VARCHAR(128) NOT NULL,
    voucher_reference VARCHAR(128) NOT NULL,
    entry_reference VARCHAR(128) NOT NULL,
    amount DECIMAL(17,2) NOT NULL CHECK (amount>0),
    currency VARCHAR(3) NOT NULL,
    PRIMARY KEY (tenant_id,repayment_id),
    CONSTRAINT uq_repayment_return_decision UNIQUE (tenant_id,resolution_id),
    CONSTRAINT uq_repayment_return_funds UNIQUE (tenant_id,legal_entity_id,channel,transaction_reference),
    CONSTRAINT uq_repayment_return_entry UNIQUE (tenant_id,legal_entity_id,voucher_reference,entry_reference),
    CONSTRAINT fk_repayment_return_original FOREIGN KEY (tenant_id,repayment_id) REFERENCES advance_repayment(tenant_id,id),
    CONSTRAINT fk_repayment_return_decision FOREIGN KEY (tenant_id,resolution_id) REFERENCES advance_repayment_resolution(tenant_id,id)
);
