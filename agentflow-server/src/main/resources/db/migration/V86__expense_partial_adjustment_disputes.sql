-- 裁决计数与具名证明相互核验；旧调整未裁决，原 JSON、操作和完成凭据保持。
ALTER TABLE expense_partial_adjustment ADD COLUMN resolution_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE expense_partial_adjustment ADD CONSTRAINT ck_partial_resolution_count CHECK (resolution_count>=0 AND resolution_count<version);
ALTER TABLE expense_partial_adjustment_operation ADD CONSTRAINT uq_partial_operation_binding UNIQUE (tenant_id,id,adjustment_id,side);
CREATE TABLE expense_partial_adjustment_dispute (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    adjustment_id VARCHAR(36) NOT NULL,
    side VARCHAR(16) NOT NULL CHECK (side IN ('BUDGET','ACCRUAL')),
    operation_id VARCHAR(36) NOT NULL,
    sequence_no INTEGER NOT NULL CHECK (sequence_no>0),
    before_version BIGINT NOT NULL CHECK (before_version>0),
    after_version BIGINT NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    resolved_by VARCHAR(128) NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE NOT NULL,
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_partial_dispute_sequence UNIQUE (tenant_id,adjustment_id,sequence_no),
    CONSTRAINT uq_partial_dispute_before UNIQUE (tenant_id,adjustment_id,before_version),
    CONSTRAINT uq_partial_dispute_after UNIQUE (tenant_id,adjustment_id,after_version),
    CONSTRAINT fk_partial_dispute_before FOREIGN KEY (tenant_id,adjustment_id,before_version) REFERENCES expense_partial_adjustment_revision(tenant_id,adjustment_id,version),
    CONSTRAINT fk_partial_dispute_after FOREIGN KEY (tenant_id,adjustment_id,after_version) REFERENCES expense_partial_adjustment_revision(tenant_id,adjustment_id,version),
    CONSTRAINT fk_partial_dispute_operation FOREIGN KEY (tenant_id,operation_id,adjustment_id,side) REFERENCES expense_partial_adjustment_operation(tenant_id,id,adjustment_id,side),
    CONSTRAINT ck_partial_dispute_adjacent CHECK (after_version=before_version+1),
    CONSTRAINT ck_partial_dispute_outcome CHECK ((side='BUDGET' AND outcome IN ('APPLIED','REJECTED')) OR (side='ACCRUAL' AND outcome IN ('POSTED','FAILED'))),
    CONSTRAINT ck_partial_dispute_time CHECK (resolved_at>=observed_at)
);
CREATE INDEX idx_partial_dispute_latest ON expense_partial_adjustment_dispute(tenant_id,adjustment_id,side,after_version DESC);
