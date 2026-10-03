-- 原消息保持；仅新生成的代理提醒记录授权来源，不给旧通知补造代理关系。
ALTER TABLE notification_inbox ADD CONSTRAINT uq_notification_tenant_id UNIQUE (tenant_id,id);
CREATE TABLE approval_proxy_notification (
    tenant_id VARCHAR(64) NOT NULL,
    proxy_id VARCHAR(36) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    kind VARCHAR(48) NOT NULL,
    inbox_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (tenant_id,proxy_id,task_id,kind),
    UNIQUE (tenant_id,inbox_id),
    FOREIGN KEY (tenant_id,proxy_id) REFERENCES organization_approval_proxy(tenant_id,id),
    FOREIGN KEY (tenant_id,inbox_id) REFERENCES notification_inbox(tenant_id,id),
    CHECK (kind IN ('TASK_PENDING','TASK_OVERDUE'))
);
