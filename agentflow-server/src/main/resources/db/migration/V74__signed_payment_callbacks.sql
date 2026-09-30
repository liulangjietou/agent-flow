-- 事件号在租户内唯一；仅保存签名后的最小身份及原始正文摘要，不保存签名密钥或金额账户。
CREATE TABLE payment_callback (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    payload_digest VARCHAR(64) NOT NULL CHECK (LENGTH(payload_digest)=64),
    target_digest VARCHAR(64) NOT NULL CHECK (LENGTH(target_digest)=64),
    payment_kind VARCHAR(16) NOT NULL CHECK (payment_kind IN ('EMPLOYEE','SUPPLIER')),
    authorization_id VARCHAR(36) NOT NULL,
    command_digest VARCHAR(64) NOT NULL CHECK (LENGTH(command_digest)=64),
    source_revision BIGINT NOT NULL CHECK (source_revision>0),
    employee_payment_id VARCHAR(36),
    supplier_payment_id VARCHAR(36),
    version BIGINT NOT NULL CHECK (version>0),
    status VARCHAR(24) NOT NULL CHECK (status IN ('RECEIVED','WAITING','QUERY_QUEUED','REVIEW_REQUIRED')),
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    failures INTEGER NOT NULL CHECK (failures>=0 AND failures<=10),
    query_version BIGINT,
    reason VARCHAR(32),
    requested_by VARCHAR(128),
    request_reason VARCHAR(500),
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_payment_callback_event UNIQUE (tenant_id,event_id),
    CONSTRAINT fk_payment_callback_employee FOREIGN KEY (tenant_id,employee_payment_id) REFERENCES payment_operation(tenant_id,id),
    CONSTRAINT fk_payment_callback_supplier FOREIGN KEY (tenant_id,supplier_payment_id) REFERENCES supplier_payment_operation(tenant_id,id),
    CONSTRAINT fk_payment_callback_employee_query FOREIGN KEY (tenant_id,employee_payment_id,query_version) REFERENCES payment_operation_revision(tenant_id,operation_id,version),
    CONSTRAINT fk_payment_callback_supplier_query FOREIGN KEY (tenant_id,supplier_payment_id,query_version) REFERENCES supplier_payment_revision(tenant_id,operation_id,version),
    CONSTRAINT ck_payment_callback_source CHECK (
        (payment_kind='EMPLOYEE' AND employee_payment_id IS NOT NULL AND employee_payment_id=authorization_id AND supplier_payment_id IS NULL)
        OR (payment_kind='SUPPLIER' AND supplier_payment_id IS NOT NULL AND supplier_payment_id=authorization_id AND employee_payment_id IS NULL)),
    CONSTRAINT ck_payment_callback_time CHECK (updated_at>=received_at),
    CONSTRAINT ck_payment_callback_due CHECK (
        (status IN ('RECEIVED','WAITING') AND next_attempt_at IS NOT NULL AND next_attempt_at>=updated_at)
        OR (status NOT IN ('RECEIVED','WAITING') AND next_attempt_at IS NULL)),
    CONSTRAINT ck_payment_callback_query CHECK (
        (status='QUERY_QUEUED' AND query_version IS NOT NULL AND query_version>0)
        OR (status<>'QUERY_QUEUED' AND query_version IS NULL)),
    CONSTRAINT ck_payment_callback_reason CHECK (
        (status='RECEIVED' AND reason IS NULL) OR (status<>'RECEIVED' AND reason IS NOT NULL)),
    CONSTRAINT ck_payment_callback_retry CHECK ((requested_by IS NULL AND request_reason IS NULL)
        OR (requested_by IS NOT NULL AND request_reason IS NOT NULL AND LENGTH(requested_by)>0 AND LENGTH(request_reason)>0))
);
CREATE INDEX idx_payment_callback_due ON payment_callback(status,next_attempt_at,received_at,id);
CREATE INDEX idx_payment_callback_history ON payment_callback(tenant_id,received_at,id);

-- 每次处理与人工重试追加历史，原事件正文摘要永远保留。
CREATE TABLE payment_callback_revision (
    tenant_id VARCHAR(64) NOT NULL,
    callback_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    state_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id,callback_id,version),
    CONSTRAINT fk_payment_callback_revision FOREIGN KEY (tenant_id,callback_id) REFERENCES payment_callback(tenant_id,id)
);
