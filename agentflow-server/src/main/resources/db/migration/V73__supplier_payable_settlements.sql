-- 财务明确的日期先保存；准备只读，核销命令绑定实际成功银行修订和原本地占用。
CREATE TABLE supplier_settlement_preparation (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    payment_id VARCHAR(36) NOT NULL,
    payment_version BIGINT NOT NULL CHECK (payment_version>0),
    finance_actor VARCHAR(128) NOT NULL,
    accounting_date DATE NOT NULL,
    input_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','RUNNING','READY','BLOCKED','VOIDED')),
    attempts INTEGER NOT NULL CHECK (attempts>=0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    lease_until TIMESTAMP WITH TIME ZONE,
    active_payment_id VARCHAR(36),
    registered_operation_id VARCHAR(36),
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_supplier_settlement_preparation_source UNIQUE (tenant_id,id,payment_id,payment_version),
    CONSTRAINT uq_supplier_settlement_preparation_active UNIQUE (tenant_id,active_payment_id),
    CONSTRAINT fk_supplier_settlement_preparation_payment FOREIGN KEY (tenant_id,payment_id,payment_version)
        REFERENCES supplier_payment_revision(tenant_id,operation_id,version),
    CONSTRAINT ck_supplier_settlement_preparation_time CHECK (updated_at>=created_at),
    CONSTRAINT ck_supplier_settlement_preparation_active CHECK (
        (status IN ('QUEUED','RUNNING') AND active_payment_id IS NOT NULL AND active_payment_id=payment_id)
        OR (status NOT IN ('QUEUED','RUNNING') AND active_payment_id IS NULL)),
    CONSTRAINT ck_supplier_settlement_preparation_registered CHECK (
        (status='READY' AND registered_operation_id IS NOT NULL AND registered_operation_id=id AND attempts>0)
        OR (status<>'READY' AND registered_operation_id IS NULL)),
    CONSTRAINT ck_supplier_settlement_preparation_due CHECK (
        (status='QUEUED' AND next_attempt_at IS NOT NULL AND next_attempt_at>=updated_at)
        OR (status<>'QUEUED' AND next_attempt_at IS NULL)),
    CONSTRAINT ck_supplier_settlement_preparation_lease CHECK (
        (status='RUNNING' AND lease_until IS NOT NULL AND lease_until>updated_at AND attempts>0)
        OR (status<>'RUNNING' AND lease_until IS NULL))
);
CREATE INDEX idx_supplier_settlement_preparation_due ON supplier_settlement_preparation(status,next_attempt_at,lease_until,created_at,id);
CREATE INDEX idx_supplier_settlement_preparation_history ON supplier_settlement_preparation(tenant_id,payment_id,created_at,id);

CREATE TABLE supplier_settlement_preparation_revision (
    tenant_id VARCHAR(64) NOT NULL,
    preparation_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    state_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,preparation_id,version),
    CONSTRAINT fk_supplier_settlement_preparation_revision FOREIGN KEY (tenant_id,preparation_id)
        REFERENCES supplier_settlement_preparation(tenant_id,id)
);

CREATE TABLE supplier_payable_settlement_operation (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    preparation_version BIGINT NOT NULL CHECK (preparation_version>0),
    payment_id VARCHAR(36) NOT NULL,
    payment_version BIGINT NOT NULL CHECK (payment_version>0),
    reservation_id VARCHAR(36) NOT NULL,
    command_json TEXT NOT NULL,
    command_digest VARCHAR(64) NOT NULL CHECK (LENGTH(command_digest)=64),
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','CHECKING','SETTLING','UNKNOWN','QUERYING','SETTLED','REJECTED','NOT_FOUND','RECONCILING','VOIDED')),
    attempts INTEGER NOT NULL CHECK (attempts>=0),
    dispatches INTEGER NOT NULL CHECK (dispatches>=0 AND dispatches<=attempts),
    highest_revision BIGINT NOT NULL CHECK (highest_revision>=0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    lease_until TIMESTAMP WITH TIME ZONE,
    active_payment_id VARCHAR(36),
    retired_version BIGINT,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_supplier_settlement_active UNIQUE (tenant_id,active_payment_id),
    CONSTRAINT uq_supplier_settlement_source UNIQUE (tenant_id,id,payment_id,reservation_id),
    CONSTRAINT fk_supplier_settlement_preparation FOREIGN KEY (tenant_id,id,payment_id,payment_version)
        REFERENCES supplier_settlement_preparation(tenant_id,id,payment_id,payment_version),
    CONSTRAINT fk_supplier_settlement_preparation_version FOREIGN KEY (tenant_id,id,preparation_version)
        REFERENCES supplier_settlement_preparation_revision(tenant_id,preparation_id,version),
    CONSTRAINT fk_supplier_settlement_payment FOREIGN KEY (tenant_id,payment_id,payment_version)
        REFERENCES supplier_payment_revision(tenant_id,operation_id,version),
    CONSTRAINT fk_supplier_settlement_reservation FOREIGN KEY (tenant_id,reservation_id)
        REFERENCES procurement_payable_reservation(tenant_id,id),
    CONSTRAINT ck_supplier_settlement_active CHECK (
        (active_payment_id IS NOT NULL AND active_payment_id=payment_id AND retired_version IS NULL)
        OR (active_payment_id IS NULL AND retired_version IS NOT NULL AND retired_version=version AND status IN ('VOIDED','REJECTED'))),
    CONSTRAINT ck_supplier_settlement_time CHECK (updated_at>=created_at),
    CONSTRAINT ck_supplier_settlement_due CHECK (
        (status IN ('QUEUED','UNKNOWN') AND next_attempt_at IS NOT NULL AND next_attempt_at>=updated_at)
        OR (status NOT IN ('QUEUED','UNKNOWN') AND next_attempt_at IS NULL)),
    CONSTRAINT ck_supplier_settlement_lease CHECK (
        (status IN ('CHECKING','SETTLING','QUERYING') AND lease_until IS NOT NULL AND lease_until>updated_at AND attempts>0)
        OR (status NOT IN ('CHECKING','SETTLING','QUERYING') AND lease_until IS NULL)),
    CONSTRAINT ck_supplier_settlement_dispatch CHECK (status NOT IN ('SETTLING','UNKNOWN','QUERYING','SETTLED','REJECTED','NOT_FOUND','RECONCILING') OR dispatches>0)
);
CREATE INDEX idx_supplier_settlement_due ON supplier_payable_settlement_operation(status,next_attempt_at,lease_until,created_at,id);
CREATE INDEX idx_supplier_settlement_history ON supplier_payable_settlement_operation(tenant_id,payment_id,created_at,id);
ALTER TABLE supplier_settlement_preparation ADD CONSTRAINT fk_supplier_settlement_preparation_registered
    FOREIGN KEY (tenant_id,registered_operation_id) REFERENCES supplier_payable_settlement_operation(tenant_id,id);

CREATE TABLE supplier_payable_settlement_revision (
    tenant_id VARCHAR(64) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    state_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,operation_id,version),
    CONSTRAINT fk_supplier_settlement_revision FOREIGN KEY (tenant_id,operation_id)
        REFERENCES supplier_payable_settlement_operation(tenant_id,id)
);

-- 未知、查无、已结算和争议继续独占原银行；只有持久安全结束可以另行确认新日期。
CREATE TABLE supplier_settlement_retirement (
    tenant_id VARCHAR(64) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    payment_id VARCHAR(36) NOT NULL,
    operation_version BIGINT NOT NULL CHECK (operation_version>0),
    basis VARCHAR(32) NOT NULL CHECK (basis IN ('NEVER_DISPATCHED','CONFIRMED_REJECTED')),
    retired_by VARCHAR(128) NOT NULL,
    retired_at TIMESTAMP WITH TIME ZONE NOT NULL,
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,operation_id),
    CONSTRAINT uq_supplier_settlement_retirement UNIQUE (tenant_id,operation_id,operation_version),
    CONSTRAINT fk_supplier_settlement_retirement_evidence FOREIGN KEY (tenant_id,operation_id,operation_version)
        REFERENCES supplier_payable_settlement_revision(tenant_id,operation_id,version),
    CONSTRAINT fk_supplier_settlement_retirement_payment FOREIGN KEY (tenant_id,payment_id) REFERENCES supplier_payment_operation(tenant_id,id)
);
ALTER TABLE supplier_payable_settlement_operation ADD CONSTRAINT fk_supplier_settlement_retirement
    FOREIGN KEY (tenant_id,id,retired_version) REFERENCES supplier_settlement_retirement(tenant_id,operation_id,operation_version);

-- 完成证据与原占用的第二版同事务保存，不挪用取消释放，也不改动旧已释放记录。
CREATE TABLE supplier_settlement_completion (
    tenant_id VARCHAR(64) NOT NULL,
    reservation_id VARCHAR(36) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    operation_version BIGINT NOT NULL CHECK (operation_version>0),
    payment_id VARCHAR(36) NOT NULL,
    payment_version BIGINT NOT NULL CHECK (payment_version>0),
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id,reservation_id),
    CONSTRAINT uq_supplier_settlement_completion UNIQUE (tenant_id,reservation_id,operation_id,operation_version),
    CONSTRAINT fk_supplier_settlement_completion_source FOREIGN KEY (tenant_id,operation_id,payment_id,reservation_id)
        REFERENCES supplier_payable_settlement_operation(tenant_id,id,payment_id,reservation_id),
    CONSTRAINT fk_supplier_settlement_completion_evidence FOREIGN KEY (tenant_id,operation_id,operation_version)
        REFERENCES supplier_payable_settlement_revision(tenant_id,operation_id,version),
    CONSTRAINT fk_supplier_settlement_completion_bank FOREIGN KEY (tenant_id,payment_id,payment_version)
        REFERENCES supplier_payment_revision(tenant_id,operation_id,version)
);
ALTER TABLE procurement_payable_reservation ADD COLUMN settled_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE procurement_payable_reservation ADD COLUMN settlement_id VARCHAR(36);
ALTER TABLE procurement_payable_reservation ADD COLUMN settlement_version BIGINT;
ALTER TABLE procurement_payable_reservation DROP CONSTRAINT ck_procurement_reservation_active;
ALTER TABLE procurement_payable_reservation ADD CONSTRAINT ck_procurement_reservation_active CHECK (
    (version=1 AND released_at IS NULL AND settled_at IS NULL AND settlement_id IS NULL AND settlement_version IS NULL
        AND active_request_id IS NOT NULL AND active_request_id=request_id AND active_payable_reference IS NOT NULL AND active_payable_reference=payable_reference)
    OR (version=2 AND active_request_id IS NULL AND active_payable_reference IS NULL AND (
        (released_at IS NOT NULL AND released_at>=held_at AND settled_at IS NULL AND settlement_id IS NULL AND settlement_version IS NULL)
        OR (released_at IS NULL AND settled_at IS NOT NULL AND settled_at>=held_at AND settlement_id IS NOT NULL AND settlement_version IS NOT NULL AND settlement_version>0)))
);
ALTER TABLE procurement_payable_reservation ADD CONSTRAINT fk_procurement_reservation_settlement
    FOREIGN KEY (tenant_id,id,settlement_id,settlement_version)
    REFERENCES supplier_settlement_completion(tenant_id,reservation_id,operation_id,operation_version);
