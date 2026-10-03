-- 准备只获取会计依据；与实际凭证共享编号，成功绑定必须属于同一审批轮次和凭证类型。
ALTER TABLE voucher_operation ADD CONSTRAINT uq_voucher_preparation_binding UNIQUE (tenant_id,id,application_id,round_no,kind);
CREATE TABLE voucher_preparation (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    business_type VARCHAR(32) NOT NULL CHECK (business_type IN ('ADVANCE_REQUEST','EXPENSE')),
    business_id VARCHAR(36) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    round_no INTEGER NOT NULL CHECK (round_no>0),
    kind VARCHAR(32) NOT NULL CHECK (kind IN ('EMPLOYEE_ADVANCE','EXPENSE_ACCRUAL')),
    application_version BIGINT NOT NULL CHECK (application_version>0),
    business_version BIGINT NOT NULL CHECK (business_version>0),
    employee_id VARCHAR(128) NOT NULL,
    attempt_no BIGINT NOT NULL CHECK (attempt_no>0),
    input_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version BETWEEN 1 AND 3),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','RUNNING','READY','BLOCKED','UNAVAILABLE','VOIDED','NOT_REQUIRED')),
    active_application_id VARCHAR(36),
    operation_id VARCHAR(36),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    lease_until TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_voucher_preparation_attempt UNIQUE (tenant_id,application_id,round_no,kind,attempt_no),
    CONSTRAINT uq_voucher_preparation_active UNIQUE (tenant_id,active_application_id,round_no,kind),
    CONSTRAINT fk_voucher_preparation_application FOREIGN KEY (tenant_id,business_type,business_id,application_id)
        REFERENCES approval_application(tenant_id,business_type,business_id,id),
    CONSTRAINT fk_voucher_preparation_operation FOREIGN KEY (tenant_id,operation_id,application_id,round_no,kind)
        REFERENCES voucher_operation(tenant_id,id,application_id,round_no,kind),
    CONSTRAINT ck_voucher_preparation_kind CHECK ((business_type='ADVANCE_REQUEST' AND kind='EMPLOYEE_ADVANCE') OR (business_type='EXPENSE' AND kind='EXPENSE_ACCRUAL')),
    CONSTRAINT ck_voucher_preparation_active CHECK (
        (status IN ('QUEUED','RUNNING') AND active_application_id IS NOT NULL AND active_application_id=application_id AND completed_at IS NULL)
        OR (status NOT IN ('QUEUED','RUNNING') AND active_application_id IS NULL AND completed_at IS NOT NULL)),
    CONSTRAINT ck_voucher_preparation_operation CHECK ((status='READY' AND operation_id IS NOT NULL AND operation_id=id)
        OR (status<>'READY' AND operation_id IS NULL)),
    CONSTRAINT ck_voucher_preparation_lease CHECK (
        (status='QUEUED' AND version=1 AND started_at IS NULL AND lease_until IS NULL)
        OR (status='RUNNING' AND version=2 AND started_at IS NOT NULL AND lease_until IS NOT NULL)
        OR (status NOT IN ('QUEUED','RUNNING') AND version=3 AND started_at IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE INDEX idx_voucher_preparation_due ON voucher_preparation(status,lease_until,created_at,id);
CREATE TABLE voucher_preparation_revision (
    tenant_id VARCHAR(64) NOT NULL,
    preparation_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version BETWEEN 1 AND 3),
    state_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,preparation_id,version),
    CONSTRAINT fk_voucher_preparation_revision FOREIGN KEY (tenant_id,preparation_id) REFERENCES voucher_preparation(tenant_id,id)
);
