-- 允许租户管理员按员工与渠道动态维护通知收件绑定，避免新增员工或修改邮箱时重启服务。
CREATE TABLE notification_recipient_binding (
    id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    recipient_id VARCHAR(128) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    server_id VARCHAR(64) NOT NULL,
    address VARCHAR(254) NOT NULL,
    enabled BOOLEAN NOT NULL,
    version BIGINT NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id, recipient_id, channel),
    CONSTRAINT uq_notification_binding_id UNIQUE (tenant_id, id)
);
CREATE INDEX idx_notification_binding_tenant ON notification_recipient_binding (tenant_id, enabled, channel);
