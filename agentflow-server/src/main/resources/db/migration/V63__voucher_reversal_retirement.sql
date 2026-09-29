-- 已结束命令仍保留原件身份，只有当前未结束命令占用原凭证。
ALTER TABLE voucher_reversal_operation ADD COLUMN active_operation_id VARCHAR(36) NULL;
ALTER TABLE voucher_reversal_operation ADD COLUMN retired_at TIMESTAMP WITH TIME ZONE NULL;
UPDATE voucher_reversal_operation SET active_operation_id=operation_id;
ALTER TABLE voucher_reversal_operation DROP CONSTRAINT uq_reverse_original_operation;
ALTER TABLE voucher_reversal_operation ADD CONSTRAINT uq_reverse_active_original UNIQUE (tenant_id,active_operation_id);
ALTER TABLE voucher_reversal_operation ADD CONSTRAINT ck_reverse_active_original CHECK (
    (retired_at IS NULL AND active_operation_id IS NOT NULL AND active_operation_id=operation_id)
    OR (retired_at IS NOT NULL AND active_operation_id IS NULL AND retired_at>=updated_at));

CREATE TABLE voucher_reversal_retirement (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    reversal_id VARCHAR(36) NOT NULL,
    original_version BIGINT NOT NULL CHECK (original_version>0),
    released_version BIGINT NOT NULL CHECK (released_version=original_version+1),
    reversal_version BIGINT NOT NULL CHECK (reversal_version>0),
    stopped_version BIGINT NOT NULL CHECK (stopped_version>=reversal_version AND stopped_version<=reversal_version+1),
    basis VARCHAR(24) NOT NULL CHECK (basis IN ('NEVER_DISPATCHED','CONFIRMED_FAILED')),
    retired_by VARCHAR(128) NOT NULL,
    retired_at TIMESTAMP WITH TIME ZONE NOT NULL,
    retirement_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_reverse_retired_command UNIQUE (tenant_id,reversal_id),
    CONSTRAINT fk_reverse_retired_binding FOREIGN KEY (tenant_id,operation_id,reversal_id) REFERENCES voucher_reversal_operation(tenant_id,operation_id,id),
    CONSTRAINT fk_reverse_retired_before FOREIGN KEY (tenant_id,reversal_id,reversal_version) REFERENCES voucher_reversal_operation_revision(tenant_id,reversal_id,version),
    CONSTRAINT fk_reverse_retired_stopped FOREIGN KEY (tenant_id,reversal_id,stopped_version) REFERENCES voucher_reversal_operation_revision(tenant_id,reversal_id,version),
    CONSTRAINT fk_reverse_retired_original FOREIGN KEY (tenant_id,operation_id,original_version) REFERENCES voucher_operation_revision(tenant_id,operation_id,version),
    CONSTRAINT fk_reverse_retired_released FOREIGN KEY (tenant_id,operation_id,released_version) REFERENCES voucher_operation_revision(tenant_id,operation_id,version)
);
CREATE INDEX idx_reverse_retired_history ON voucher_reversal_retirement(tenant_id,operation_id,retired_at,id);
