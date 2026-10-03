-- 原授权永不覆盖；只有明确结束记录才能解除原申请独占并允许新的财务授权。
ALTER TABLE supplier_payment_authorization ADD COLUMN active_request_id VARCHAR(36);
ALTER TABLE supplier_payment_authorization ADD COLUMN retired_hold_version BIGINT;
UPDATE supplier_payment_authorization SET active_request_id=request_id;
ALTER TABLE supplier_payment_authorization DROP CONSTRAINT uq_supplier_authorization_request;
ALTER TABLE supplier_payment_authorization ADD CONSTRAINT uq_supplier_authorization_active UNIQUE (tenant_id,active_request_id);
ALTER TABLE supplier_payment_authorization ADD CONSTRAINT ck_supplier_authorization_active CHECK (
    (active_request_id IS NOT NULL AND active_request_id=request_id AND retired_hold_version IS NULL)
    OR (active_request_id IS NULL AND retired_hold_version IS NOT NULL AND retired_hold_version>0)
);

CREATE TABLE supplier_authorization_retirement (
    tenant_id VARCHAR(64) NOT NULL,
    authorization_id VARCHAR(36) NOT NULL,
    operation_version BIGINT NOT NULL CHECK (operation_version>0),
    basis VARCHAR(32) NOT NULL CHECK (basis IN ('NEVER_DISPATCHED','CONFIRMED_REJECTED')),
    retired_by VARCHAR(128) NOT NULL,
    retired_at TIMESTAMP WITH TIME ZONE NOT NULL,
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,authorization_id),
    CONSTRAINT uq_supplier_retirement_revision UNIQUE (tenant_id,authorization_id,operation_version),
    CONSTRAINT fk_supplier_retirement_authorization FOREIGN KEY (tenant_id,authorization_id) REFERENCES supplier_payment_authorization(tenant_id,id),
    CONSTRAINT fk_supplier_retirement_evidence FOREIGN KEY (tenant_id,authorization_id,operation_version)
        REFERENCES supplier_payable_hold_revision(tenant_id,operation_id,version)
);
ALTER TABLE supplier_payment_authorization ADD CONSTRAINT fk_supplier_authorization_retirement
    FOREIGN KEY (tenant_id,id,retired_hold_version) REFERENCES supplier_authorization_retirement(tenant_id,authorization_id,operation_version);
