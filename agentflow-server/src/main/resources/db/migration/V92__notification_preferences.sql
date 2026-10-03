CREATE TABLE notification_preferences (
    tenant_id VARCHAR(64) NOT NULL,
    recipient_id VARCHAR(128) NOT NULL,
    email_enabled BOOLEAN NOT NULL,
    enterprise_im_enabled BOOLEAN NOT NULL,
    version BIGINT NOT NULL,
    email_generation BIGINT NOT NULL,
    enterprise_im_generation BIGINT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id,recipient_id)
);

CREATE TABLE notification_preference_change (
    tenant_id VARCHAR(64) NOT NULL,
    recipient_id VARCHAR(128) NOT NULL,
    version BIGINT NOT NULL,
    email_enabled BOOLEAN NOT NULL,
    enterprise_im_enabled BOOLEAN NOT NULL,
    email_generation BIGINT NOT NULL,
    enterprise_im_generation BIGINT NOT NULL,
    changed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id,recipient_id,version)
);

CREATE TABLE notification_dispatch (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    recipient_id VARCHAR(128) NOT NULL,
    inbox_id VARCHAR(36) NOT NULL REFERENCES notification_inbox(id),
    channel VARCHAR(32) NOT NULL,
    consent_generation BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_notification_dispatch UNIQUE (tenant_id,inbox_id,channel)
);
CREATE INDEX idx_notification_dispatch_recipient ON notification_dispatch (tenant_id,recipient_id,status);
CREATE INDEX idx_notification_dispatch_status ON notification_dispatch (status,created_at,id);
