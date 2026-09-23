-- 请求占位、业务写入与成功响应在同一事务提交；过期记录保留，避免自动重执副作用。
CREATE TABLE request_idempotency (
    tenant_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    roles_hash CHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response_status INT NULL,
    response_body TEXT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_request_idempotency PRIMARY KEY (tenant_id, idempotency_key),
    CONSTRAINT ck_idempotency_response CHECK (
        (response_status IS NULL AND response_body IS NULL)
        OR (response_status BETWEEN 200 AND 299 AND response_body IS NOT NULL)
    )
);
