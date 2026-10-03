-- 幂等事务先保存出纳选择，事务外复查后才能生成付款命令；同一授权不可选择两次。
CREATE TABLE payment_execution_request (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    authorization_id VARCHAR(36) NOT NULL,
    authorization_version BIGINT NOT NULL CHECK (authorization_version=1),
    cashier_id VARCHAR(128) NOT NULL,
    input_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','RUNNING','READY','BLOCKED','VOIDED','EXPIRED')),
    attempts INTEGER NOT NULL CHECK (attempts>=0),
    operation_id VARCHAR(36),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    lease_until TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_payment_execution_request_authorization UNIQUE (tenant_id,authorization_id),
    CONSTRAINT fk_payment_execution_request_authorization FOREIGN KEY (tenant_id,authorization_id) REFERENCES payment_authorization(tenant_id,id),
    CONSTRAINT fk_payment_execution_request_operation FOREIGN KEY (tenant_id,operation_id) REFERENCES payment_operation(tenant_id,id),
    CONSTRAINT ck_payment_execution_request_operation CHECK ((status='READY' AND operation_id IS NOT NULL AND operation_id=authorization_id)
        OR (status<>'READY' AND operation_id IS NULL)),
    CONSTRAINT ck_payment_execution_request_schedule CHECK (
        (status='QUEUED' AND next_attempt_at IS NOT NULL AND lease_until IS NULL)
        OR (status='RUNNING' AND next_attempt_at IS NULL AND lease_until IS NOT NULL AND attempts>0)
        OR (status IN ('READY','BLOCKED','VOIDED','EXPIRED') AND next_attempt_at IS NULL AND lease_until IS NULL)),
    CONSTRAINT ck_payment_execution_request_time CHECK (updated_at>=created_at)
);
CREATE INDEX idx_payment_execution_request_due ON payment_execution_request(status,next_attempt_at,lease_until,created_at,id);
CREATE TABLE payment_execution_request_revision (
    tenant_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    state_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,request_id,version),
    CONSTRAINT fk_payment_execution_request_revision FOREIGN KEY (tenant_id,request_id) REFERENCES payment_execution_request(tenant_id,id)
);
