-- 已认证事件按租户和来源去重；原信封仅含等待身份，不保存密钥、签名或业务正文。
CREATE TABLE event_inbox (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    source_key VARCHAR(64) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    contract_key VARCHAR(64) NOT NULL,
    contract_version BIGINT NOT NULL CHECK (contract_version > 0),
    input_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    status VARCHAR(24) NOT NULL CHECK (status IN ('RECEIVED','WAITING','CONSUMED','IGNORED','REVIEW_REQUIRED')),
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    failures INTEGER NOT NULL CHECK (failures >= 0 AND failures <= 10),
    reason VARCHAR(32),
    error_code VARCHAR(80),
    requested_by VARCHAR(128),
    request_reason VARCHAR(500),
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_event_inbox_identity UNIQUE (tenant_id,source_key,event_id),
    CONSTRAINT fk_event_inbox_application FOREIGN KEY (tenant_id,application_id) REFERENCES approval_application(tenant_id,id),
    CONSTRAINT fk_event_inbox_contract FOREIGN KEY (tenant_id,contract_key,contract_version) REFERENCES event_contract_version(tenant_id,contract_key,contract_version),
    CONSTRAINT ck_event_inbox_time CHECK (updated_at >= received_at),
    CONSTRAINT ck_event_inbox_due CHECK (
        (status IN ('RECEIVED','WAITING') AND next_attempt_at IS NOT NULL AND next_attempt_at >= updated_at)
        OR (status NOT IN ('RECEIVED','WAITING') AND next_attempt_at IS NULL)),
    CONSTRAINT ck_event_inbox_reason CHECK (
        (status='RECEIVED' AND reason IS NULL)
        OR (reason IS NOT NULL AND ((status='WAITING' AND reason IN ('PAUSED','CONTRACT_DISABLED','SOURCE_DISABLED','PROCESSING_FAILED'))
        OR (status='CONSUMED' AND reason='MATCHED')
        OR (status='IGNORED' AND reason IN ('TARGET_STALE','CONTRACT_MISMATCH'))
        OR (status='REVIEW_REQUIRED' AND reason IN ('SOURCE_CHANGED','PROCESSING_FAILED'))))),
    CONSTRAINT ck_event_inbox_error CHECK (
        (reason IS NOT NULL AND reason='PROCESSING_FAILED' AND error_code IS NOT NULL AND error_code='EVENT_PROCESSING_FAILED')
        OR ((reason IS NULL OR reason<>'PROCESSING_FAILED') AND error_code IS NULL)),
    CONSTRAINT ck_event_inbox_retry CHECK ((requested_by IS NULL AND request_reason IS NULL)
        OR (requested_by IS NOT NULL AND request_reason IS NOT NULL AND LENGTH(requested_by)>0 AND LENGTH(request_reason)>0))
);
CREATE INDEX idx_event_inbox_due ON event_inbox(next_attempt_at,received_at,id);
CREATE INDEX idx_event_inbox_page ON event_inbox(tenant_id,received_at,id);

CREATE TABLE event_inbox_revision (
    tenant_id VARCHAR(64) NOT NULL,
    inbox_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    state_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id,inbox_id,version),
    CONSTRAINT fk_event_inbox_revision FOREIGN KEY (tenant_id,inbox_id) REFERENCES event_inbox(tenant_id,id)
);
