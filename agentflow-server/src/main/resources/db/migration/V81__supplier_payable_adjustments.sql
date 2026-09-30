-- 独立调整保存已登记回款版本、办理财务和日期，准备与命令分开恢复。
CREATE TABLE supplier_adjustment_preparation (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    payment_id VARCHAR(36) NOT NULL,
    return_version BIGINT NOT NULL CHECK (return_version>0),
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
    CONSTRAINT uq_supplier_adjustment_preparation_source UNIQUE (tenant_id,id,payment_id,return_version),
    CONSTRAINT uq_supplier_adjustment_preparation_active UNIQUE (tenant_id,active_payment_id),
    CONSTRAINT fk_supplier_adjustment_preparation_payment FOREIGN KEY (tenant_id,payment_id,return_version)
        REFERENCES supplier_payment_returns_revision(tenant_id,payment_id,version),
    CONSTRAINT ck_supplier_adjustment_preparation_time CHECK (updated_at>=created_at),
    CONSTRAINT ck_supplier_adjustment_preparation_active CHECK (
        (status IN ('QUEUED','RUNNING') AND active_payment_id IS NOT NULL AND active_payment_id=payment_id)
        OR (status NOT IN ('QUEUED','RUNNING') AND active_payment_id IS NULL)),
    CONSTRAINT ck_supplier_adjustment_preparation_registered CHECK (
        (status='READY' AND registered_operation_id IS NOT NULL AND registered_operation_id=id AND attempts>0)
        OR (status<>'READY' AND registered_operation_id IS NULL)),
    CONSTRAINT ck_supplier_adjustment_preparation_due CHECK (
        (status='QUEUED' AND next_attempt_at IS NOT NULL AND next_attempt_at>=updated_at)
        OR (status<>'QUEUED' AND next_attempt_at IS NULL)),
    CONSTRAINT ck_supplier_adjustment_preparation_lease CHECK (
        (status='RUNNING' AND lease_until IS NOT NULL AND lease_until>updated_at AND attempts>0)
        OR (status<>'RUNNING' AND lease_until IS NULL))
);
CREATE INDEX idx_supplier_adjustment_preparation_due ON supplier_adjustment_preparation(status,next_attempt_at,lease_until,created_at,id);
CREATE INDEX idx_supplier_adjustment_preparation_history ON supplier_adjustment_preparation(tenant_id,payment_id,created_at,id);

CREATE TABLE supplier_adjustment_preparation_revision (
    tenant_id VARCHAR(64) NOT NULL,
    preparation_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    state_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,preparation_id,version),
    CONSTRAINT fk_supplier_adjustment_preparation_revision FOREIGN KEY (tenant_id,preparation_id)
        REFERENCES supplier_adjustment_preparation(tenant_id,id)
);

CREATE TABLE supplier_payable_adjustment_operation (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    preparation_version BIGINT NOT NULL CHECK (preparation_version>0),
    payment_id VARCHAR(36) NOT NULL,
    return_version BIGINT NOT NULL CHECK (return_version>0),
    reservation_id VARCHAR(36) NOT NULL,
    command_json TEXT NOT NULL,
    command_digest VARCHAR(64) NOT NULL CHECK (LENGTH(command_digest)=64),
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','CHECKING','ADJUSTING','UNKNOWN','QUERYING','ADJUSTED','REJECTED','NOT_FOUND','RECONCILING','VOIDED')),
    attempts INTEGER NOT NULL CHECK (attempts>=0),
    dispatches INTEGER NOT NULL CHECK (dispatches>=0 AND dispatches<=attempts),
    highest_revision BIGINT NOT NULL CHECK (highest_revision>=0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    lease_until TIMESTAMP WITH TIME ZONE,
    active_payment_id VARCHAR(36),
    retired_version BIGINT,
    completed_version BIGINT,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_supplier_adjustment_active UNIQUE (tenant_id,active_payment_id),
    CONSTRAINT uq_supplier_adjustment_source UNIQUE (tenant_id,id,payment_id,reservation_id),
    CONSTRAINT fk_supplier_adjustment_preparation FOREIGN KEY (tenant_id,id,payment_id,return_version)
        REFERENCES supplier_adjustment_preparation(tenant_id,id,payment_id,return_version),
    CONSTRAINT fk_supplier_adjustment_preparation_version FOREIGN KEY (tenant_id,id,preparation_version)
        REFERENCES supplier_adjustment_preparation_revision(tenant_id,preparation_id,version),
    CONSTRAINT fk_supplier_adjustment_payment FOREIGN KEY (tenant_id,payment_id,return_version)
        REFERENCES supplier_payment_returns_revision(tenant_id,payment_id,version),
    CONSTRAINT fk_supplier_adjustment_reservation FOREIGN KEY (tenant_id,reservation_id)
        REFERENCES procurement_payable_reservation(tenant_id,id),
    CONSTRAINT ck_supplier_adjustment_active CHECK (
        (active_payment_id IS NOT NULL AND active_payment_id=payment_id AND retired_version IS NULL AND completed_version IS NULL)
        OR (active_payment_id IS NULL AND completed_version IS NULL AND retired_version IS NOT NULL AND retired_version=version AND status IN ('VOIDED','REJECTED'))
        OR (active_payment_id IS NULL AND retired_version IS NULL AND completed_version IS NOT NULL AND completed_version>0 AND completed_version<=version
            AND status IN ('ADJUSTED','UNKNOWN','QUERYING','RECONCILING'))),
    CONSTRAINT ck_supplier_adjustment_time CHECK (updated_at>=created_at),
    CONSTRAINT ck_supplier_adjustment_due CHECK (
        (status IN ('QUEUED','UNKNOWN') AND next_attempt_at IS NOT NULL AND next_attempt_at>=updated_at)
        OR (status NOT IN ('QUEUED','UNKNOWN') AND next_attempt_at IS NULL)),
    CONSTRAINT ck_supplier_adjustment_lease CHECK (
        (status IN ('CHECKING','ADJUSTING','QUERYING') AND lease_until IS NOT NULL AND lease_until>updated_at AND attempts>0)
        OR (status NOT IN ('CHECKING','ADJUSTING','QUERYING') AND lease_until IS NULL)),
    CONSTRAINT ck_supplier_adjustment_dispatch CHECK (status NOT IN ('ADJUSTING','UNKNOWN','QUERYING','ADJUSTED','REJECTED','NOT_FOUND','RECONCILING') OR dispatches>0)
);
CREATE INDEX idx_supplier_adjustment_due ON supplier_payable_adjustment_operation(status,next_attempt_at,lease_until,created_at,id);
CREATE INDEX idx_supplier_adjustment_history ON supplier_payable_adjustment_operation(tenant_id,payment_id,created_at,id);
ALTER TABLE supplier_adjustment_preparation ADD CONSTRAINT fk_supplier_adjustment_preparation_registered
    FOREIGN KEY (tenant_id,registered_operation_id) REFERENCES supplier_payable_adjustment_operation(tenant_id,id);

CREATE TABLE supplier_payable_adjustment_revision (
    tenant_id VARCHAR(64) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    state_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,operation_id,version),
    CONSTRAINT fk_supplier_adjustment_revision FOREIGN KEY (tenant_id,operation_id)
        REFERENCES supplier_payable_adjustment_operation(tenant_id,id)
);

-- 未知、查无和争议继续独占原银行；安全结束或完整本地记账后才能登记下一次调整。
CREATE TABLE supplier_adjustment_retirement (
    tenant_id VARCHAR(64) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    payment_id VARCHAR(36) NOT NULL,
    operation_version BIGINT NOT NULL CHECK (operation_version>0),
    basis VARCHAR(32) NOT NULL CHECK (basis IN ('NEVER_DISPATCHED','CONFIRMED_REJECTED')),
    retired_by VARCHAR(128) NOT NULL,
    retired_at TIMESTAMP WITH TIME ZONE NOT NULL,
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,operation_id),
    CONSTRAINT uq_supplier_adjustment_retirement UNIQUE (tenant_id,operation_id,operation_version),
    CONSTRAINT fk_supplier_adjustment_retirement_evidence FOREIGN KEY (tenant_id,operation_id,operation_version)
        REFERENCES supplier_payable_adjustment_revision(tenant_id,operation_id,version),
    CONSTRAINT fk_supplier_adjustment_retirement_payment FOREIGN KEY (tenant_id,payment_id) REFERENCES supplier_payment_operation(tenant_id,id)
);
ALTER TABLE supplier_payable_adjustment_operation ADD CONSTRAINT fk_supplier_adjustment_retirement
    FOREIGN KEY (tenant_id,id,retired_version) REFERENCES supplier_adjustment_retirement(tenant_id,operation_id,operation_version);

-- 完成引用独立 ERP 修订、之后复核的银行原件及回款账本前后版本。
CREATE TABLE supplier_adjustment_completion (
    tenant_id VARCHAR(64) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    operation_version BIGINT NOT NULL CHECK (operation_version>0),
    payment_id VARCHAR(36) NOT NULL,
    payment_version BIGINT NOT NULL CHECK (payment_version>0),
    reservation_id VARCHAR(36) NOT NULL,
    before_return_version BIGINT NOT NULL CHECK (before_return_version>0),
    return_version BIGINT NOT NULL,
    accounted_entry_count INTEGER NOT NULL CHECK (accounted_entry_count BETWEEN 1 AND 100),
    proof_json TEXT NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id,operation_id),
    CONSTRAINT uq_supplier_adjustment_completion UNIQUE (tenant_id,operation_id,operation_version),
    CONSTRAINT uq_supplier_adjustment_completion_payment UNIQUE (tenant_id,payment_id,operation_id,operation_version),
    CONSTRAINT uq_supplier_adjustment_completion_reservation UNIQUE (tenant_id,reservation_id,operation_id,operation_version),
    CONSTRAINT uq_supplier_adjustment_completion_returns UNIQUE (tenant_id,payment_id,return_version),
    CONSTRAINT fk_supplier_adjustment_completion_source FOREIGN KEY (tenant_id,operation_id,payment_id,reservation_id)
        REFERENCES supplier_payable_adjustment_operation(tenant_id,id,payment_id,reservation_id),
    CONSTRAINT fk_supplier_adjustment_completion_evidence FOREIGN KEY (tenant_id,operation_id,operation_version)
        REFERENCES supplier_payable_adjustment_revision(tenant_id,operation_id,version),
    CONSTRAINT fk_supplier_adjustment_completion_bank FOREIGN KEY (tenant_id,payment_id,payment_version)
        REFERENCES supplier_payment_revision(tenant_id,operation_id,version),
    CONSTRAINT fk_supplier_adjustment_completion_before FOREIGN KEY (tenant_id,payment_id,before_return_version)
        REFERENCES supplier_payment_returns_revision(tenant_id,payment_id,version),
    CONSTRAINT fk_supplier_adjustment_completion_after FOREIGN KEY (tenant_id,payment_id,return_version)
        REFERENCES supplier_payment_returns_revision(tenant_id,payment_id,version),
    CONSTRAINT ck_supplier_adjustment_completion_version CHECK (return_version=before_return_version+1)
);
ALTER TABLE supplier_payable_adjustment_operation ADD CONSTRAINT fk_supplier_adjustment_completed
    FOREIGN KEY (tenant_id,id,completed_version) REFERENCES supplier_adjustment_completion(tenant_id,operation_id,operation_version);

ALTER TABLE supplier_payment_returns ADD COLUMN accounting_id VARCHAR(36);
ALTER TABLE supplier_payment_returns ADD COLUMN accounting_version BIGINT;
ALTER TABLE supplier_payment_returns ADD COLUMN accounted_entry_count INTEGER;
ALTER TABLE supplier_payment_returns ADD COLUMN accounted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE supplier_payment_returns ADD CONSTRAINT ck_supplier_return_accounting CHECK (
    (accounting_id IS NULL AND accounting_version IS NULL AND accounted_entry_count IS NULL AND accounted_at IS NULL)
    OR (accounting_id IS NOT NULL AND accounting_version IS NOT NULL AND accounting_version>0
        AND accounted_entry_count IS NOT NULL AND accounted_entry_count BETWEEN 1 AND 100
        AND accounted_at IS NOT NULL AND accounted_at>=created_at AND accounted_at<=updated_at)
);
ALTER TABLE supplier_payment_returns ADD CONSTRAINT fk_supplier_return_accounting
    FOREIGN KEY (tenant_id,payment_id,accounting_id,accounting_version)
    REFERENCES supplier_adjustment_completion(tenant_id,payment_id,operation_id,operation_version);

-- 银行资金仍保留首次登记归属，ERP 分录只能在其后绑定同一独立调整的已保存修订。
ALTER TABLE finance_receipt_credit ADD COLUMN supplier_adjustment_id VARCHAR(36);
ALTER TABLE finance_receipt_credit ADD COLUMN supplier_adjustment_version BIGINT;
ALTER TABLE finance_receipt_credit ADD CONSTRAINT fk_finance_credit_supplier_adjustment
    FOREIGN KEY (tenant_id,supplier_adjustment_id,supplier_adjustment_version)
    REFERENCES supplier_adjustment_completion(tenant_id,operation_id,operation_version);
ALTER TABLE finance_receipt_credit DROP CONSTRAINT ck_finance_receipt_credit_origin;
ALTER TABLE finance_receipt_credit ADD CONSTRAINT ck_finance_receipt_credit_origin CHECK (
    (supplier_registration_id IS NULL AND supplier_adjustment_id IS NULL AND supplier_adjustment_version IS NULL
        AND voucher_reference IS NOT NULL AND entry_reference IS NOT NULL AND (
        (repayment_id IS NOT NULL AND disbursement_resolution_id IS NULL AND expense_registration_id IS NULL)
        OR (repayment_id IS NULL AND disbursement_resolution_id IS NOT NULL AND expense_registration_id IS NULL AND channel='BANK_TRANSFER')
        OR (repayment_id IS NULL AND disbursement_resolution_id IS NULL AND expense_registration_id IS NOT NULL AND channel='BANK_TRANSFER')))
    OR (supplier_registration_id IS NOT NULL AND repayment_id IS NULL AND disbursement_resolution_id IS NULL AND expense_registration_id IS NULL
        AND channel='BANK_TRANSFER' AND (
        (supplier_adjustment_id IS NULL AND supplier_adjustment_version IS NULL AND voucher_reference IS NULL AND entry_reference IS NULL)
        OR (supplier_adjustment_id IS NOT NULL AND supplier_adjustment_version IS NOT NULL AND supplier_adjustment_version>0
            AND voucher_reference IS NOT NULL AND entry_reference IS NOT NULL)))
);

-- 未完成的原占用可由独立调整结束，已结算或已释放的旧事实不改写。
ALTER TABLE procurement_payable_reservation ADD COLUMN adjusted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE procurement_payable_reservation ADD COLUMN adjustment_id VARCHAR(36);
ALTER TABLE procurement_payable_reservation ADD COLUMN adjustment_version BIGINT;
ALTER TABLE procurement_payable_reservation DROP CONSTRAINT ck_procurement_reservation_active;
ALTER TABLE procurement_payable_reservation ADD CONSTRAINT ck_procurement_reservation_active CHECK (
    (version=1 AND released_at IS NULL AND settled_at IS NULL AND settlement_id IS NULL AND settlement_version IS NULL
        AND adjusted_at IS NULL AND adjustment_id IS NULL AND adjustment_version IS NULL
        AND active_request_id IS NOT NULL AND active_request_id=request_id AND active_payable_reference IS NOT NULL AND active_payable_reference=payable_reference)
    OR (version=2 AND active_request_id IS NULL AND active_payable_reference IS NULL AND (
        (released_at IS NOT NULL AND released_at>=held_at AND settled_at IS NULL AND settlement_id IS NULL AND settlement_version IS NULL
            AND adjusted_at IS NULL AND adjustment_id IS NULL AND adjustment_version IS NULL)
        OR (released_at IS NULL AND settled_at IS NOT NULL AND settled_at>=held_at AND settlement_id IS NOT NULL AND settlement_version IS NOT NULL AND settlement_version>0
            AND adjusted_at IS NULL AND adjustment_id IS NULL AND adjustment_version IS NULL)
        OR (released_at IS NULL AND settled_at IS NULL AND settlement_id IS NULL AND settlement_version IS NULL
            AND adjusted_at IS NOT NULL AND adjusted_at>=held_at AND adjustment_id IS NOT NULL AND adjustment_version IS NOT NULL AND adjustment_version>0)))
);
ALTER TABLE procurement_payable_reservation ADD CONSTRAINT fk_procurement_reservation_adjustment
    FOREIGN KEY (tenant_id,id,adjustment_id,adjustment_version)
    REFERENCES supplier_adjustment_completion(tenant_id,reservation_id,operation_id,operation_version);
