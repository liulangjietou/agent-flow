-- 独立冲销的只读准备与实际写命令分别保存，所有来源绑定原不可变修订。
CREATE TABLE voucher_reversal_preparation (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    original_version BIGINT NOT NULL CHECK (original_version>0),
    requested_by VARCHAR(128) NOT NULL,
    input_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','RUNNING','READY','AUTHORIZED','UNAVAILABLE','VOIDED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE NULL,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT fk_reverse_preparation_original FOREIGN KEY (tenant_id,operation_id,original_version) REFERENCES voucher_operation_revision(tenant_id,operation_id,version),
    CONSTRAINT ck_reverse_preparation_time CHECK (updated_at>=created_at),
    CONSTRAINT ck_reverse_preparation_lease CHECK ((status='RUNNING' AND lease_until IS NOT NULL AND lease_until>updated_at) OR (status<>'RUNNING' AND lease_until IS NULL))
);
CREATE INDEX idx_reverse_preparation_due ON voucher_reversal_preparation(status,lease_until,created_at,id);
CREATE INDEX idx_reverse_preparation_latest ON voucher_reversal_preparation(tenant_id,operation_id,requested_by,created_at DESC,id DESC);
CREATE TABLE voucher_reversal_preparation_revision (
    tenant_id VARCHAR(64) NOT NULL,
    preparation_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,preparation_id,version),
    CONSTRAINT fk_reverse_preparation_revision FOREIGN KEY (tenant_id,preparation_id) REFERENCES voucher_reversal_preparation(tenant_id,id)
);
CREATE TABLE voucher_reversal_operation (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    original_version BIGINT NOT NULL CHECK (original_version>0),
    preparation_version BIGINT NOT NULL CHECK (preparation_version>1),
    input_json TEXT NOT NULL,
    command_digest VARCHAR(64) NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','POSTING','QUERYING','UNKNOWN','POSTED','FAILED','NOT_FOUND','EXPIRED','VOIDED','RECONCILING')),
    attempts INTEGER NOT NULL CHECK (attempts>=0),
    highest_revision BIGINT NOT NULL CHECK (highest_revision>=0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE NULL,
    lease_until TIMESTAMP WITH TIME ZONE NULL,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_reverse_original_operation UNIQUE (tenant_id,operation_id),
    CONSTRAINT uq_reverse_original_binding UNIQUE (tenant_id,operation_id,id),
    CONSTRAINT fk_reverse_execution_original FOREIGN KEY (tenant_id,operation_id,original_version) REFERENCES voucher_operation_revision(tenant_id,operation_id,version),
    CONSTRAINT fk_reverse_execution_preparation FOREIGN KEY (tenant_id,id,preparation_version) REFERENCES voucher_reversal_preparation_revision(tenant_id,preparation_id,version),
    CONSTRAINT ck_reverse_execution_time CHECK (updated_at>=created_at),
    CONSTRAINT ck_reverse_execution_lease CHECK ((status IN ('POSTING','QUERYING') AND lease_until IS NOT NULL AND lease_until>updated_at) OR (status NOT IN ('POSTING','QUERYING') AND lease_until IS NULL)),
    CONSTRAINT ck_reverse_execution_due CHECK ((status IN ('QUEUED','UNKNOWN') AND next_attempt_at IS NOT NULL AND next_attempt_at>=updated_at) OR (status NOT IN ('QUEUED','UNKNOWN') AND next_attempt_at IS NULL))
);
CREATE INDEX idx_reverse_execution_due ON voucher_reversal_operation(status,next_attempt_at,lease_until,created_at,id);
CREATE TABLE voucher_reversal_operation_revision (
    tenant_id VARCHAR(64) NOT NULL,
    reversal_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,reversal_id,version),
    CONSTRAINT fk_reverse_execution_revision FOREIGN KEY (tenant_id,reversal_id) REFERENCES voucher_reversal_operation(tenant_id,id)
);
ALTER TABLE voucher_operation ADD COLUMN reversal_id VARCHAR(36) NULL;
ALTER TABLE voucher_operation ADD CONSTRAINT fk_voucher_reverse_execution FOREIGN KEY (tenant_id,id,reversal_id) REFERENCES voucher_reversal_operation(tenant_id,operation_id,id);
