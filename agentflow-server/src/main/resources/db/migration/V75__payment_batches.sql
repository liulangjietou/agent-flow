-- 批次只组合真实执行请求，资金状态继续由原单笔操作维护。
ALTER TABLE payment_execution_request ADD CONSTRAINT uq_payment_request_batch_binding
    UNIQUE (tenant_id,id,authorization_id,authorization_version);

CREATE TABLE payment_batch (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    legal_entity_id VARCHAR(36) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    cashier_id VARCHAR(128) NOT NULL,
    item_count INTEGER NOT NULL CHECK (item_count BETWEEN 1 AND 25),
    total_value DECIMAL(20,2) NOT NULL CHECK (total_value>0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,id)
);
CREATE INDEX idx_payment_batch_scope ON payment_batch(tenant_id,legal_entity_id,created_at,id);

CREATE TABLE payment_batch_item (
    tenant_id VARCHAR(64) NOT NULL,
    batch_id VARCHAR(36) NOT NULL,
    line_no INTEGER NOT NULL CHECK (line_no BETWEEN 1 AND 25),
    authorization_id VARCHAR(36) NOT NULL,
    authorization_version BIGINT NOT NULL CHECK (authorization_version=1),
    request_id VARCHAR(36) NOT NULL,
    request_version BIGINT NOT NULL CHECK (request_version=1),
    PRIMARY KEY (tenant_id,batch_id,line_no),
    CONSTRAINT uq_payment_batch_authorization UNIQUE (tenant_id,authorization_id),
    CONSTRAINT uq_payment_batch_request UNIQUE (tenant_id,request_id),
    CONSTRAINT fk_payment_batch_item FOREIGN KEY (tenant_id,batch_id) REFERENCES payment_batch(tenant_id,id),
    CONSTRAINT fk_payment_batch_authorization_revision FOREIGN KEY (tenant_id,authorization_id,authorization_version)
        REFERENCES payment_authorization_revision(tenant_id,authorization_id,version),
    CONSTRAINT fk_payment_batch_request_binding FOREIGN KEY (tenant_id,request_id,authorization_id,authorization_version)
        REFERENCES payment_execution_request(tenant_id,id,authorization_id,authorization_version),
    CONSTRAINT fk_payment_batch_request_revision FOREIGN KEY (tenant_id,request_id,request_version)
        REFERENCES payment_execution_request_revision(tenant_id,request_id,version)
);
