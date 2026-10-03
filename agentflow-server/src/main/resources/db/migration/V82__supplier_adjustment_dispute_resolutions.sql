-- 独立 ERP 调整裁决关联连续修订，原入款分录、银行和既有完成记录保持。
CREATE TABLE supplier_adjustment_dispute_resolution (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    adjustment_id VARCHAR(36) NOT NULL,
    disputed_version BIGINT NOT NULL CHECK (disputed_version>0),
    resolved_version BIGINT NOT NULL,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('ADJUSTED','REJECTED')),
    resolved_by VARCHAR(128) NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE NOT NULL,
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_supplier_adj_dispute_version UNIQUE (tenant_id,adjustment_id,disputed_version),
    CONSTRAINT uq_supplier_adj_resolution_version UNIQUE (tenant_id,adjustment_id,resolved_version),
    CONSTRAINT fk_supplier_adj_dispute_before FOREIGN KEY (tenant_id,adjustment_id,disputed_version) REFERENCES supplier_payable_adjustment_revision(tenant_id,operation_id,version),
    CONSTRAINT fk_supplier_adj_dispute_after FOREIGN KEY (tenant_id,adjustment_id,resolved_version) REFERENCES supplier_payable_adjustment_revision(tenant_id,operation_id,version),
    CONSTRAINT ck_supplier_adj_dispute_version CHECK (resolved_version=disputed_version+1),
    CONSTRAINT ck_supplier_adj_dispute_time CHECK (resolved_at>=observed_at)
);
CREATE INDEX idx_supplier_adj_dispute_latest ON supplier_adjustment_dispute_resolution(tenant_id,adjustment_id,resolved_version DESC);
