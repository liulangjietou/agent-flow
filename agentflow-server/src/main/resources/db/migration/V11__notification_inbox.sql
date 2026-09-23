CREATE TABLE notification_inbox (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    recipient_id VARCHAR(128) NOT NULL,
    event_key VARCHAR(256) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    title VARCHAR(256) NOT NULL,
    business_no VARCHAR(128) NOT NULL,
    kind VARCHAR(48) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128),
    node_name VARCHAR(255),
    round_no INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    read_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_notification_recipient_event UNIQUE (tenant_id, recipient_id, event_key)
);
CREATE INDEX idx_notification_inbox_recipient ON notification_inbox (tenant_id, recipient_id, created_at DESC, id DESC);
CREATE INDEX idx_notification_inbox_unread ON notification_inbox (tenant_id, recipient_id, read_at, created_at DESC, id DESC);
