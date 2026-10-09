-- 工具先登记后外呼；主键绑定原请求，状态变更只追加。
CREATE TABLE agent_handling_read (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    handling_id VARCHAR(36) NOT NULL,
    input_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK(version > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(tenant_id,id),
    FOREIGN KEY(tenant_id,handling_id) REFERENCES agent_expense_handling(tenant_id,id)
);
CREATE INDEX idx_handling_read_task ON agent_handling_read(tenant_id,handling_id,created_at);
CREATE TABLE agent_handling_read_transition (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL,
    state_json TEXT NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(tenant_id,id,version),
    FOREIGN KEY(tenant_id,id) REFERENCES agent_handling_read(tenant_id,id)
);
