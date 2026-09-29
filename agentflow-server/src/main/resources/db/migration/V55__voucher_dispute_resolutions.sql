-- 人工裁决保留原冲突与结果修订；每个冲突版本至多裁决一次。
CREATE TABLE voucher_dispute_resolution (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    disputed_version BIGINT NOT NULL CHECK (disputed_version>0),
    resolved_version BIGINT NOT NULL,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('POSTED','FAILED','REVERSED')),
    resolved_by VARCHAR(128) NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE NOT NULL,
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_voucher_dispute_version UNIQUE (tenant_id,operation_id,disputed_version),
    CONSTRAINT uq_voucher_resolution_version UNIQUE (tenant_id,operation_id,resolved_version),
    CONSTRAINT fk_voucher_dispute_before FOREIGN KEY (tenant_id,operation_id,disputed_version) REFERENCES voucher_operation_revision(tenant_id,operation_id,version),
    CONSTRAINT fk_voucher_dispute_after FOREIGN KEY (tenant_id,operation_id,resolved_version) REFERENCES voucher_operation_revision(tenant_id,operation_id,version),
    CONSTRAINT ck_voucher_dispute_version CHECK (resolved_version=disputed_version+1),
    CONSTRAINT ck_voucher_dispute_time CHECK (resolved_at>=observed_at)
);
CREATE INDEX idx_voucher_dispute_latest ON voucher_dispute_resolution(tenant_id,operation_id,resolved_version DESC);
