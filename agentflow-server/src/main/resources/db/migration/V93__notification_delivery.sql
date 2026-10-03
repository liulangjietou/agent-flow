ALTER TABLE notification_dispatch ADD COLUMN binding_id VARCHAR(64);
ALTER TABLE notification_dispatch ADD COLUMN destination_digest VARCHAR(64);
ALTER TABLE notification_dispatch ADD COLUMN version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE notification_dispatch ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE notification_dispatch ADD COLUMN cycle_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE notification_dispatch ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE notification_dispatch ADD COLUMN lease_until TIMESTAMP WITH TIME ZONE;
ALTER TABLE notification_dispatch ADD COLUMN lease_token VARCHAR(36);
ALTER TABLE notification_dispatch ADD COLUMN error_code VARCHAR(64);

-- 原意向没有入队时的收件绑定，不能在升级后补绑到当前地址并补发。
UPDATE notification_dispatch SET status='SUPPRESSED',error_code='BINDING_NOT_CAPTURED',updated_at=CURRENT_TIMESTAMP WHERE status='PENDING';
UPDATE notification_dispatch SET error_code='CONSENT_REVOKED' WHERE status='SUPPRESSED' AND error_code IS NULL;

CREATE TABLE notification_delivery_event (
    delivery_id VARCHAR(36) NOT NULL REFERENCES notification_dispatch(id),
    version BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempts INTEGER NOT NULL,
    cycle_attempts INTEGER NOT NULL,
    error_code VARCHAR(64),
    actor_id VARCHAR(128),
    reason VARCHAR(1000),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (delivery_id,version)
);
INSERT INTO notification_delivery_event (delivery_id,version,status,attempts,cycle_attempts,error_code,occurred_at)
SELECT id,version,status,attempts,cycle_attempts,error_code,updated_at FROM notification_dispatch;
CREATE INDEX idx_notification_dispatch_due ON notification_dispatch (status,next_attempt_at,lease_until);
