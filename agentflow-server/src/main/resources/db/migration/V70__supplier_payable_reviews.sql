-- 财务读取原应付与明确授权分开保存，只有已存在的原授权能消费复核证据。
ALTER TABLE supplier_payment_authorization ADD CONSTRAINT uq_supplier_authorization_review_binding
    UNIQUE (tenant_id,id,request_id,application_id,round_no,request_version);

CREATE TABLE supplier_payable_review (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    employee_id VARCHAR(128) NOT NULL,
    round_no INTEGER NOT NULL CHECK (round_no>0),
    application_version BIGINT NOT NULL CHECK (application_version>0),
    request_version BIGINT NOT NULL CHECK (request_version>0),
    reservation_id VARCHAR(36) NOT NULL,
    requested_by VARCHAR(128) NOT NULL,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    input_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','RUNNING','READY','CONSUMED','BLOCKED','UNAVAILABLE','VOIDED')),
    attempts INTEGER NOT NULL CHECK (attempts>=0),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE,
    checked_at TIMESTAMP WITH TIME ZONE,
    active_request_id VARCHAR(36),
    consumed_authorization_id VARCHAR(36),
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_supplier_review_active UNIQUE (tenant_id,requested_by,active_request_id),
    CONSTRAINT uq_supplier_review_consumption UNIQUE (tenant_id,consumed_authorization_id),
    CONSTRAINT fk_supplier_review_source FOREIGN KEY (tenant_id,reservation_id,request_id,application_id,employee_id,round_no)
        REFERENCES procurement_payable_reservation(tenant_id,id,request_id,application_id,employee_id,round_no),
    CONSTRAINT fk_supplier_review_version FOREIGN KEY (tenant_id,request_id,request_version)
        REFERENCES procurement_payment_revision(tenant_id,request_id,request_version),
    CONSTRAINT fk_supplier_review_consumed_authorization FOREIGN KEY (tenant_id,consumed_authorization_id,request_id,application_id,round_no,request_version)
        REFERENCES supplier_payment_authorization(tenant_id,id,request_id,application_id,round_no,request_version),
    CONSTRAINT ck_supplier_review_separation CHECK (requested_by<>employee_id),
    CONSTRAINT ck_supplier_review_time CHECK (updated_at>=requested_at),
    CONSTRAINT ck_supplier_review_active CHECK (
        (status IN ('QUEUED','RUNNING') AND active_request_id IS NOT NULL AND active_request_id=request_id)
        OR (status NOT IN ('QUEUED','RUNNING') AND active_request_id IS NULL)),
    CONSTRAINT ck_supplier_review_lease CHECK (
        (status='RUNNING' AND lease_until IS NOT NULL AND lease_until>updated_at AND attempts>0)
        OR (status<>'RUNNING' AND lease_until IS NULL)),
    CONSTRAINT ck_supplier_review_evidence CHECK (
        (status IN ('READY','CONSUMED') AND checked_at IS NOT NULL AND checked_at>=requested_at AND checked_at<=updated_at AND attempts>0)
        OR (status NOT IN ('READY','CONSUMED') AND checked_at IS NULL)),
    CONSTRAINT ck_supplier_review_consumed CHECK (
        (status='CONSUMED' AND consumed_authorization_id IS NOT NULL)
        OR (status<>'CONSUMED' AND consumed_authorization_id IS NULL))
);
CREATE INDEX idx_supplier_review_due ON supplier_payable_review(status,lease_until,requested_at,id);
CREATE INDEX idx_supplier_review_history ON supplier_payable_review(tenant_id,request_id,requested_by,requested_at,id);

CREATE TABLE supplier_payable_review_revision (
    tenant_id VARCHAR(64) NOT NULL,
    review_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    state_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,review_id,version),
    CONSTRAINT fk_supplier_review_revision FOREIGN KEY (tenant_id,review_id) REFERENCES supplier_payable_review(tenant_id,id)
);
