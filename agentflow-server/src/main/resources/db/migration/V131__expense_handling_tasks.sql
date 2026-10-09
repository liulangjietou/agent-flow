-- 本人显式开始的办理记录，与原费用及预检、模型运行分别保存。
CREATE TABLE agent_expense_handling (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    report_id VARCHAR(36) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    active_report_id VARCHAR(36),
    version BIGINT NOT NULL CHECK(version > 0),
    context_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(tenant_id,id),
    UNIQUE(tenant_id,active_report_id),
    CHECK(active_report_id IS NULL OR active_report_id=report_id),
    FOREIGN KEY(tenant_id,report_id,application_id,owner_id) REFERENCES expense_report(tenant_id,id,application_id,employee_id)
);
CREATE INDEX idx_expense_handling_report ON agent_expense_handling(tenant_id,report_id,created_at,id);
CREATE TABLE agent_expense_handling_transition (
    tenant_id VARCHAR(64) NOT NULL,
    handling_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK(version > 0),
    state_json TEXT NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(tenant_id,handling_id,version),
    FOREIGN KEY(tenant_id,handling_id) REFERENCES agent_expense_handling(tenant_id,id)
);
