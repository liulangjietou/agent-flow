-- 每轮每类凭证保留一个原始命令，未知结果不能通过换号绕过原交易对账。
CREATE TABLE voucher_operation (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    business_type VARCHAR(32) NOT NULL CHECK (business_type IN ('ADVANCE_REQUEST','EXPENSE')),
    business_id VARCHAR(36) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    round_no INTEGER NOT NULL CHECK (round_no > 0),
    kind VARCHAR(32) NOT NULL CHECK (kind IN ('EMPLOYEE_ADVANCE','EXPENSE_ACCRUAL','PAYMENT')),
    application_version BIGINT NOT NULL CHECK (application_version > 0),
    business_version BIGINT NOT NULL CHECK (business_version > 0),
    input_json TEXT NOT NULL,
    command_digest VARCHAR(64) NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','POSTING','QUERYING','UNKNOWN','POSTED','FAILED','NOT_FOUND','EXPIRED','VOIDED','RECONCILING','REVERSED')),
    attempts INTEGER NOT NULL CHECK (attempts >= 0),
    highest_revision BIGINT NOT NULL CHECK (highest_revision >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    lease_until TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_voucher_business_round_kind UNIQUE (tenant_id,business_type,business_id,round_no,kind),
    CONSTRAINT fk_voucher_application_binding FOREIGN KEY (tenant_id,business_type,business_id,application_id)
        REFERENCES approval_application(tenant_id,business_type,business_id,id),
    CONSTRAINT ck_voucher_operation_schedule CHECK (
        (status IN ('QUEUED','UNKNOWN') AND next_attempt_at IS NOT NULL AND lease_until IS NULL)
        OR (status IN ('POSTING','QUERYING') AND next_attempt_at IS NULL AND lease_until IS NOT NULL AND attempts>0)
        OR (status IN ('POSTED','FAILED','NOT_FOUND','EXPIRED','VOIDED','RECONCILING','REVERSED') AND next_attempt_at IS NULL AND lease_until IS NULL))
);
CREATE INDEX idx_voucher_operation_due ON voucher_operation(status,next_attempt_at,lease_until,created_at,id);
CREATE INDEX idx_voucher_operation_application ON voucher_operation(tenant_id,application_id,round_no,kind);

CREATE TABLE voucher_operation_revision (
    tenant_id VARCHAR(64) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    state_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,operation_id,version),
    CONSTRAINT fk_voucher_operation_revision FOREIGN KEY (tenant_id,operation_id) REFERENCES voucher_operation(tenant_id,id)
);
