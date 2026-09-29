-- 原授权安全结束后重新读取本人账户，复核事实与新授权单次绑定。
CREATE TABLE payment_payee_review (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    original_authorization_id VARCHAR(36) NOT NULL,
    original_authorization_version BIGINT NOT NULL CHECK (original_authorization_version IN (2,3)),
    voucher_operation_id VARCHAR(36) NOT NULL,
    voucher_version BIGINT NOT NULL CHECK (voucher_version>0),
    requested_by VARCHAR(128) NOT NULL,
    input_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','RUNNING','READY','CONSUMED','UNAVAILABLE','BLOCKED','VOIDED')),
    attempts INTEGER NOT NULL CHECK (attempts>=0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE,
    checked_at TIMESTAMP WITH TIME ZONE,
    valid_until TIMESTAMP WITH TIME ZONE,
    consumed_authorization_id VARCHAR(36),
    issue VARCHAR(32),
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT fk_payee_review_original FOREIGN KEY (tenant_id,original_authorization_id,original_authorization_version)
        REFERENCES payment_authorization_revision(tenant_id,authorization_id,version),
    CONSTRAINT fk_payee_review_voucher FOREIGN KEY (tenant_id,voucher_operation_id,voucher_version)
        REFERENCES voucher_operation_revision(tenant_id,operation_id,version),
    CONSTRAINT fk_payee_review_new_authorization FOREIGN KEY (tenant_id,consumed_authorization_id)
        REFERENCES payment_authorization(tenant_id,id),
    CONSTRAINT uq_payee_review_consumed UNIQUE (tenant_id,consumed_authorization_id),
    CONSTRAINT ck_payee_review_lease CHECK (
        (status='RUNNING' AND lease_until IS NOT NULL AND lease_until>updated_at AND attempts>0)
        OR (status<>'RUNNING' AND lease_until IS NULL)),
    CONSTRAINT ck_payee_review_time CHECK (updated_at>=created_at),
    CONSTRAINT ck_payee_review_evidence CHECK (
        (status IN ('READY','CONSUMED') AND checked_at IS NOT NULL AND valid_until IS NOT NULL
            AND checked_at>=created_at AND checked_at<=updated_at AND valid_until>updated_at AND attempts>0 AND issue IS NULL)
        OR (status NOT IN ('READY','CONSUMED') AND checked_at IS NULL AND valid_until IS NULL)),
    CONSTRAINT ck_payee_review_binding CHECK (
        (status='CONSUMED' AND consumed_authorization_id IS NOT NULL AND consumed_authorization_id<>original_authorization_id)
        OR (status<>'CONSUMED' AND consumed_authorization_id IS NULL)),
    CONSTRAINT ck_payee_review_issue CHECK (status NOT IN ('UNAVAILABLE','BLOCKED','VOIDED') OR issue IS NOT NULL)
);
CREATE INDEX idx_payee_review_latest ON payment_payee_review(tenant_id,original_authorization_id,requested_by,created_at DESC,id DESC);
CREATE INDEX idx_payee_review_due ON payment_payee_review(status,lease_until,created_at,id);
CREATE TABLE payment_payee_review_revision (
    tenant_id VARCHAR(64) NOT NULL,
    review_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    state_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,review_id,version),
    CONSTRAINT fk_payee_review_revision FOREIGN KEY (tenant_id,review_id) REFERENCES payment_payee_review(tenant_id,id)
);
