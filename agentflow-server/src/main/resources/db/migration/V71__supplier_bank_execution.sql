-- 出纳选择先持久化；只读准备、唯一银行命令及逐版恢复事实分别保存。
CREATE TABLE supplier_payment_execution_request (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    authorization_id VARCHAR(36) NOT NULL,
    hold_version BIGINT NOT NULL CHECK (hold_version>0),
    cashier_id VARCHAR(128) NOT NULL,
    input_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','RUNNING','READY','BLOCKED','VOIDED','EXPIRED')),
    attempts INTEGER NOT NULL CHECK (attempts>=0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    lease_until TIMESTAMP WITH TIME ZONE,
    active_authorization_id VARCHAR(36),
    registered_authorization_id VARCHAR(36),
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_supplier_execution_source UNIQUE (tenant_id,id,authorization_id),
    CONSTRAINT uq_supplier_execution_active UNIQUE (tenant_id,active_authorization_id),
    CONSTRAINT fk_supplier_execution_authorization FOREIGN KEY (tenant_id,authorization_id) REFERENCES supplier_payment_authorization(tenant_id,id),
    CONSTRAINT fk_supplier_execution_hold FOREIGN KEY (tenant_id,authorization_id,hold_version) REFERENCES supplier_payable_hold_revision(tenant_id,operation_id,version),
    CONSTRAINT ck_supplier_execution_time CHECK (updated_at>=created_at),
    CONSTRAINT ck_supplier_execution_active CHECK (
        (status IN ('QUEUED','RUNNING','READY') AND active_authorization_id IS NOT NULL AND active_authorization_id=authorization_id)
        OR (status NOT IN ('QUEUED','RUNNING','READY') AND active_authorization_id IS NULL)),
    CONSTRAINT ck_supplier_execution_registered CHECK (
        (status='READY' AND registered_authorization_id IS NOT NULL AND registered_authorization_id=authorization_id AND attempts>0)
        OR (status<>'READY' AND registered_authorization_id IS NULL)),
    CONSTRAINT ck_supplier_execution_due CHECK (
        (status='QUEUED' AND next_attempt_at IS NOT NULL AND next_attempt_at>=updated_at)
        OR (status<>'QUEUED' AND next_attempt_at IS NULL)),
    CONSTRAINT ck_supplier_execution_lease CHECK (
        (status='RUNNING' AND lease_until IS NOT NULL AND lease_until>updated_at AND attempts>0)
        OR (status<>'RUNNING' AND lease_until IS NULL))
);
CREATE INDEX idx_supplier_execution_due ON supplier_payment_execution_request(status,next_attempt_at,lease_until,created_at,id);
CREATE INDEX idx_supplier_execution_history ON supplier_payment_execution_request(tenant_id,authorization_id,created_at,id);

CREATE TABLE supplier_payment_execution_revision (
    tenant_id VARCHAR(64) NOT NULL,
    execution_request_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    state_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,execution_request_id,version),
    CONSTRAINT fk_supplier_execution_revision FOREIGN KEY (tenant_id,execution_request_id) REFERENCES supplier_payment_execution_request(tenant_id,id)
);

CREATE TABLE supplier_payment_operation (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    execution_request_id VARCHAR(36) NOT NULL,
    execution_request_version BIGINT NOT NULL CHECK (execution_request_version>0),
    hold_version BIGINT NOT NULL CHECK (hold_version>0),
    command_json TEXT NOT NULL,
    command_digest VARCHAR(64) NOT NULL CHECK (LENGTH(command_digest)=64),
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','CHECKING','SENDING','UNKNOWN','QUERYING','SUCCEEDED','FAILED','REVERSED','NOT_FOUND','RECONCILING','EXPIRED','VOIDED')),
    attempts INTEGER NOT NULL CHECK (attempts>=0),
    dispatches INTEGER NOT NULL CHECK (dispatches>=0 AND dispatches<=attempts),
    highest_revision BIGINT NOT NULL CHECK (highest_revision>=0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    lease_until TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_supplier_payment_execution UNIQUE (tenant_id,execution_request_id,id),
    CONSTRAINT fk_supplier_payment_authorization FOREIGN KEY (tenant_id,id) REFERENCES supplier_payment_authorization(tenant_id,id),
    CONSTRAINT fk_supplier_payment_execution_source FOREIGN KEY (tenant_id,execution_request_id,id) REFERENCES supplier_payment_execution_request(tenant_id,id,authorization_id),
    CONSTRAINT fk_supplier_payment_execution_version FOREIGN KEY (tenant_id,execution_request_id,execution_request_version) REFERENCES supplier_payment_execution_revision(tenant_id,execution_request_id,version),
    CONSTRAINT fk_supplier_payment_hold FOREIGN KEY (tenant_id,id,hold_version) REFERENCES supplier_payable_hold_revision(tenant_id,operation_id,version),
    CONSTRAINT ck_supplier_payment_time CHECK (updated_at>=created_at),
    CONSTRAINT ck_supplier_payment_due CHECK (
        (status IN ('QUEUED','UNKNOWN') AND next_attempt_at IS NOT NULL AND next_attempt_at>=updated_at)
        OR (status NOT IN ('QUEUED','UNKNOWN') AND next_attempt_at IS NULL)),
    CONSTRAINT ck_supplier_payment_lease CHECK (
        (status IN ('CHECKING','SENDING','QUERYING') AND lease_until IS NOT NULL AND lease_until>updated_at AND attempts>0)
        OR (status NOT IN ('CHECKING','SENDING','QUERYING') AND lease_until IS NULL)),
    CONSTRAINT ck_supplier_payment_dispatch CHECK (status NOT IN ('SENDING','UNKNOWN','QUERYING','SUCCEEDED','FAILED','REVERSED','NOT_FOUND','RECONCILING') OR dispatches>0)
);
CREATE INDEX idx_supplier_payment_due ON supplier_payment_operation(status,next_attempt_at,lease_until,created_at,id);

ALTER TABLE supplier_payment_execution_request ADD CONSTRAINT fk_supplier_execution_registered
    FOREIGN KEY (tenant_id,id,registered_authorization_id) REFERENCES supplier_payment_operation(tenant_id,execution_request_id,id);

CREATE TABLE supplier_payment_revision (
    tenant_id VARCHAR(64) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    state_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,operation_id,version),
    CONSTRAINT fk_supplier_payment_revision FOREIGN KEY (tenant_id,operation_id) REFERENCES supplier_payment_operation(tenant_id,id)
);
