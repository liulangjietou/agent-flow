-- 原 ERP 裁决关联连续修订，原核销凭证和采购占用完成记录继续独立保留。
CREATE TABLE supplier_settlement_dispute_resolution (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    settlement_id VARCHAR(36) NOT NULL,
    disputed_version BIGINT NOT NULL CHECK (disputed_version>0),
    resolved_version BIGINT NOT NULL,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('SETTLED','REJECTED')),
    resolved_by VARCHAR(128) NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE NOT NULL,
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_supplier_erp_dispute_version UNIQUE (tenant_id,settlement_id,disputed_version),
    CONSTRAINT uq_supplier_erp_resolution_version UNIQUE (tenant_id,settlement_id,resolved_version),
    CONSTRAINT fk_supplier_erp_dispute_before FOREIGN KEY (tenant_id,settlement_id,disputed_version) REFERENCES supplier_payable_settlement_revision(tenant_id,operation_id,version),
    CONSTRAINT fk_supplier_erp_dispute_after FOREIGN KEY (tenant_id,settlement_id,resolved_version) REFERENCES supplier_payable_settlement_revision(tenant_id,operation_id,version),
    CONSTRAINT ck_supplier_erp_dispute_version CHECK (resolved_version=disputed_version+1),
    CONSTRAINT ck_supplier_erp_dispute_time CHECK (resolved_at>=observed_at)
);
CREATE INDEX idx_supplier_erp_dispute_latest ON supplier_settlement_dispute_resolution(tenant_id,settlement_id,resolved_version DESC);
