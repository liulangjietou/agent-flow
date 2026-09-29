-- 查询固定首次接受的过账修订；独立财务登记不改写原凭证或业务资源。
CREATE TABLE voucher_reversal_check (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    original_version BIGINT NOT NULL CHECK (original_version>0),
    requested_by VARCHAR(128) NOT NULL,
    input_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','RUNNING','CHECKED','RECORDED','UNAVAILABLE','VOIDED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE NULL,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT fk_voucher_reversal_original FOREIGN KEY (tenant_id,operation_id,original_version) REFERENCES voucher_operation_revision(tenant_id,operation_id,version),
    CONSTRAINT ck_voucher_reversal_check_time CHECK (updated_at>=created_at),
    CONSTRAINT ck_voucher_reversal_check_lease CHECK ((status='RUNNING' AND lease_until IS NOT NULL AND lease_until>updated_at) OR (status<>'RUNNING' AND lease_until IS NULL))
);
CREATE INDEX idx_voucher_reversal_due ON voucher_reversal_check(status,lease_until,created_at,id);
CREATE INDEX idx_voucher_reversal_latest ON voucher_reversal_check(tenant_id,operation_id,requested_by,created_at DESC,id DESC);
CREATE INDEX idx_voucher_reversal_history ON voucher_reversal_check(tenant_id,operation_id,updated_at DESC,id DESC);
CREATE TABLE voucher_reversal_check_revision (
    tenant_id VARCHAR(64) NOT NULL,
    check_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,check_id,version),
    CONSTRAINT fk_voucher_reversal_revision FOREIGN KEY (tenant_id,check_id) REFERENCES voucher_reversal_check(tenant_id,id)
);
CREATE TABLE voucher_reversal_record (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    operation_version BIGINT NOT NULL CHECK (operation_version>0),
    check_id VARCHAR(36) NOT NULL,
    check_version BIGINT NOT NULL CHECK (check_version>1),
    legal_entity_id VARCHAR(36) NOT NULL,
    reversal_posting_reference VARCHAR(128) NOT NULL,
    reversal_voucher_reference VARCHAR(128) NOT NULL,
    recorded_by VARCHAR(128) NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_voucher_reversal_operation UNIQUE (tenant_id,operation_id),
    CONSTRAINT uq_voucher_reversal_check UNIQUE (tenant_id,check_id),
    CONSTRAINT uq_voucher_reversal_posting UNIQUE (tenant_id,legal_entity_id,reversal_posting_reference),
    CONSTRAINT uq_voucher_reversal_voucher UNIQUE (tenant_id,legal_entity_id,reversal_voucher_reference),
    CONSTRAINT fk_voucher_reversal_operation FOREIGN KEY (tenant_id,operation_id,operation_version) REFERENCES voucher_operation_revision(tenant_id,operation_id,version),
    CONSTRAINT fk_voucher_reversal_consumed_check FOREIGN KEY (tenant_id,check_id,check_version) REFERENCES voucher_reversal_check_revision(tenant_id,check_id,version),
    CONSTRAINT ck_voucher_reversal_record_time CHECK (recorded_at>=observed_at)
);
