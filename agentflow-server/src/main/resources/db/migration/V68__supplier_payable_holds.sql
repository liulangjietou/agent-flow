-- 财务授权先绑定实际批准采购及其原本地占用，不将 ERP 应付读取当作外部预留。
ALTER TABLE procurement_payable_reservation ADD CONSTRAINT uq_procurement_reservation_payment_source
    UNIQUE (tenant_id,id,request_id,application_id,employee_id,round_no);

CREATE TABLE supplier_payment_authorization (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    employee_id VARCHAR(128) NOT NULL,
    round_no INTEGER NOT NULL CHECK (round_no>0),
    application_version BIGINT NOT NULL CHECK (application_version>0),
    request_version BIGINT NOT NULL CHECK (request_version>0),
    reservation_id VARCHAR(36) NOT NULL,
    legal_entity_id VARCHAR(36) NOT NULL,
    authorized_by VARCHAR(128) NOT NULL,
    authorized_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_supplier_authorization_request UNIQUE (tenant_id,request_id),
    CONSTRAINT fk_supplier_authorization_source FOREIGN KEY (tenant_id,reservation_id,request_id,application_id,employee_id,round_no)
        REFERENCES procurement_payable_reservation(tenant_id,id,request_id,application_id,employee_id,round_no),
    CONSTRAINT fk_supplier_authorization_version FOREIGN KEY (tenant_id,request_id,request_version)
        REFERENCES procurement_payment_revision(tenant_id,request_id,request_version),
    CONSTRAINT ck_supplier_authorization_time CHECK (expires_at>authorized_at),
    CONSTRAINT ck_supplier_authorization_separation CHECK (authorized_by<>employee_id)
);
CREATE INDEX idx_supplier_authorization_application ON supplier_payment_authorization(tenant_id,application_id,round_no);

-- 同一授权只有一个不可替换的原预留命令，所有领取和回执另追加版本。
CREATE TABLE supplier_payable_hold_operation (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    command_json TEXT NOT NULL,
    command_digest VARCHAR(64) NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','RESERVING','UNKNOWN','QUERYING','HELD','REJECTED','NOT_FOUND','EXPIRED','VOIDED','RECONCILING')),
    attempts INTEGER NOT NULL CHECK (attempts>=0),
    dispatches INTEGER NOT NULL CHECK (dispatches>=0 AND dispatches<=attempts),
    highest_revision BIGINT NOT NULL CHECK (highest_revision>=0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    lease_until TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT fk_supplier_hold_authorization FOREIGN KEY (tenant_id,id) REFERENCES supplier_payment_authorization(tenant_id,id),
    CONSTRAINT ck_supplier_hold_schedule CHECK (
        (status IN ('QUEUED','UNKNOWN') AND next_attempt_at IS NOT NULL AND lease_until IS NULL AND next_attempt_at>=updated_at)
        OR (status IN ('RESERVING','QUERYING') AND next_attempt_at IS NULL AND lease_until IS NOT NULL AND lease_until>updated_at AND attempts>0)
        OR (status IN ('HELD','REJECTED','NOT_FOUND','EXPIRED','VOIDED','RECONCILING') AND next_attempt_at IS NULL AND lease_until IS NULL)),
    CONSTRAINT ck_supplier_hold_dispatch CHECK (status NOT IN ('RESERVING','QUERYING','UNKNOWN','HELD','REJECTED','NOT_FOUND','RECONCILING') OR dispatches>0),
    CONSTRAINT ck_supplier_hold_time CHECK (updated_at>=created_at)
);
CREATE INDEX idx_supplier_hold_due ON supplier_payable_hold_operation(status,next_attempt_at,lease_until,created_at,id);

CREATE TABLE supplier_payable_hold_revision (
    tenant_id VARCHAR(64) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    state_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,operation_id,version),
    CONSTRAINT fk_supplier_hold_revision FOREIGN KEY (tenant_id,operation_id) REFERENCES supplier_payable_hold_operation(tenant_id,id)
);
