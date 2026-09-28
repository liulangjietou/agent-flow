-- 有效授权和已登记执行独占业务单据，换审批轮次也不能绕过既有资金交易。
CREATE TABLE payment_authorization (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    business_type VARCHAR(32) NOT NULL CHECK (business_type IN ('ADVANCE_REQUEST','EXPENSE')),
    business_id VARCHAR(36) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    round_no INTEGER NOT NULL CHECK (round_no>0),
    application_version BIGINT NOT NULL CHECK (application_version>0),
    business_version BIGINT NOT NULL CHECK (business_version>0),
    purpose VARCHAR(32) NOT NULL CHECK (purpose IN ('EMPLOYEE_ADVANCE','EXPENSE_REIMBURSEMENT')),
    voucher_operation_id VARCHAR(36) NOT NULL,
    voucher_kind VARCHAR(32) NOT NULL,
    terms_json TEXT NOT NULL,
    decision_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version IN (1,2)),
    status VARCHAR(24) NOT NULL CHECK (status IN ('AUTHORIZED','EXECUTION_REGISTERED','VOIDED','EXPIRED')),
    active_business_id VARCHAR(36),
    authorized_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_payment_authorization_active UNIQUE (tenant_id,business_type,active_business_id),
    CONSTRAINT fk_payment_authorization_application FOREIGN KEY (tenant_id,business_type,business_id,application_id)
        REFERENCES approval_application(tenant_id,business_type,business_id,id),
    CONSTRAINT fk_payment_authorization_voucher FOREIGN KEY (tenant_id,voucher_operation_id,application_id,round_no,voucher_kind)
        REFERENCES voucher_operation(tenant_id,id,application_id,round_no,kind),
    CONSTRAINT ck_payment_authorization_purpose CHECK (
        (purpose='EMPLOYEE_ADVANCE' AND business_type='ADVANCE_REQUEST' AND voucher_kind='EMPLOYEE_ADVANCE')
        OR (purpose='EXPENSE_REIMBURSEMENT' AND business_type='EXPENSE' AND voucher_kind='EXPENSE_ACCRUAL')),
    CONSTRAINT ck_payment_authorization_active CHECK (
        (status IN ('AUTHORIZED','EXECUTION_REGISTERED') AND active_business_id IS NOT NULL AND active_business_id=business_id)
        OR (status IN ('VOIDED','EXPIRED') AND active_business_id IS NULL)),
    CONSTRAINT ck_payment_authorization_version CHECK ((status='AUTHORIZED' AND version=1) OR (status<>'AUTHORIZED' AND version=2)),
    CONSTRAINT ck_payment_authorization_time CHECK (expires_at>authorized_at AND updated_at>=authorized_at)
);
CREATE INDEX idx_payment_authorization_application ON payment_authorization(tenant_id,application_id,round_no,authorized_at,id);
CREATE TABLE payment_authorization_revision (
    tenant_id VARCHAR(64) NOT NULL,
    authorization_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version IN (1,2)),
    state_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,authorization_id,version),
    CONSTRAINT fk_payment_authorization_revision FOREIGN KEY (tenant_id,authorization_id) REFERENCES payment_authorization(tenant_id,id)
);

-- 只有已登记的原授权可建立执行，检查租约与可能发送租约分开持久化。
CREATE TABLE payment_operation (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    input_json TEXT NOT NULL,
    command_digest VARCHAR(64) NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','CHECKING','SENDING','QUERYING','UNKNOWN','SUCCEEDED','FAILED','NOT_FOUND','EXPIRED','VOIDED','RECONCILING','REVERSED')),
    attempts INTEGER NOT NULL CHECK (attempts>=0),
    dispatches INTEGER NOT NULL CHECK (dispatches>=0 AND dispatches<=attempts),
    highest_revision BIGINT NOT NULL CHECK (highest_revision>=0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    lease_until TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT fk_payment_operation_authorization FOREIGN KEY (tenant_id,id) REFERENCES payment_authorization(tenant_id,id),
    CONSTRAINT ck_payment_operation_schedule CHECK (
        (status IN ('QUEUED','UNKNOWN') AND next_attempt_at IS NOT NULL AND lease_until IS NULL)
        OR (status IN ('CHECKING','SENDING','QUERYING') AND next_attempt_at IS NULL AND lease_until IS NOT NULL AND attempts>0)
        OR (status IN ('SUCCEEDED','FAILED','NOT_FOUND','EXPIRED','VOIDED','RECONCILING','REVERSED') AND next_attempt_at IS NULL AND lease_until IS NULL)),
    CONSTRAINT ck_payment_operation_dispatch CHECK (status NOT IN ('SENDING','QUERYING','UNKNOWN','SUCCEEDED','FAILED','NOT_FOUND','RECONCILING','REVERSED') OR dispatches>0),
    CONSTRAINT ck_payment_operation_time CHECK (updated_at>=created_at)
);
CREATE INDEX idx_payment_operation_due ON payment_operation(status,next_attempt_at,lease_until,created_at,id);
CREATE TABLE payment_operation_revision (
    tenant_id VARCHAR(64) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    state_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,operation_id,version),
    CONSTRAINT fk_payment_operation_revision FOREIGN KEY (tenant_id,operation_id) REFERENCES payment_operation(tenant_id,id)
);
