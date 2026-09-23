CREATE TABLE webhook_delivery (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    destination_digest VARCHAR(64) NOT NULL,
    event_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    payload_json TEXT NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL,
    attempts INT NOT NULL,
    cycle_attempts INT NOT NULL,
    next_attempt_at TIMESTAMP NULL,
    lease_until TIMESTAMP NULL,
    lease_token VARCHAR(36) NULL,
    http_status INT NULL,
    error_code VARCHAR(64) NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_webhook_target_event UNIQUE (tenant_id,target_id,event_id)
);
CREATE INDEX idx_webhook_due ON webhook_delivery (status,next_attempt_at,lease_until);
CREATE INDEX idx_webhook_tenant_time ON webhook_delivery (tenant_id,occurred_at DESC,id DESC);

CREATE TABLE webhook_attempt (
    delivery_id VARCHAR(36) NOT NULL,
    attempt_no INT NOT NULL,
    lease_token VARCHAR(36) NOT NULL,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP NULL,
    result VARCHAR(32) NOT NULL,
    http_status INT NULL,
    error_code VARCHAR(64) NULL,
    PRIMARY KEY (delivery_id,attempt_no),
    CONSTRAINT fk_webhook_attempt_delivery FOREIGN KEY (delivery_id) REFERENCES webhook_delivery(id)
);

CREATE TABLE webhook_retry_request (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    delivery_id VARCHAR(36) NOT NULL,
    requested_by VARCHAR(128) NOT NULL,
    requested_at TIMESTAMP NOT NULL,
    previous_status VARCHAR(32) NOT NULL,
    previous_version BIGINT NOT NULL,
    CONSTRAINT fk_webhook_retry_delivery FOREIGN KEY (delivery_id) REFERENCES webhook_delivery(id)
);
CREATE INDEX idx_webhook_retry_time ON webhook_retry_request (delivery_id,requested_at DESC,id DESC);
