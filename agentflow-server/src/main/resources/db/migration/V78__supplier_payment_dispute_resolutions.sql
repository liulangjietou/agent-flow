-- 裁决前后均引用原银行修订；不得用人工说明替换付款原件或删除冲突历史。
CREATE TABLE supplier_payment_dispute_resolution (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    payment_id VARCHAR(36) NOT NULL,
    disputed_version BIGINT NOT NULL CHECK (disputed_version>0),
    resolved_version BIGINT NOT NULL,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('SUCCEEDED','FAILED','REVERSED')),
    resolved_by VARCHAR(128) NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE NOT NULL,
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_supplier_bank_dispute_version UNIQUE (tenant_id,payment_id,disputed_version),
    CONSTRAINT uq_supplier_bank_resolution_version UNIQUE (tenant_id,payment_id,resolved_version),
    CONSTRAINT fk_supplier_bank_dispute_before FOREIGN KEY (tenant_id,payment_id,disputed_version) REFERENCES supplier_payment_revision(tenant_id,operation_id,version),
    CONSTRAINT fk_supplier_bank_dispute_after FOREIGN KEY (tenant_id,payment_id,resolved_version) REFERENCES supplier_payment_revision(tenant_id,operation_id,version),
    CONSTRAINT ck_supplier_bank_dispute_version CHECK (resolved_version=disputed_version+1),
    CONSTRAINT ck_supplier_bank_dispute_time CHECK (resolved_at>=observed_at)
);
CREATE INDEX idx_supplier_bank_dispute_latest ON supplier_payment_dispute_resolution(tenant_id,payment_id,resolved_version DESC);
