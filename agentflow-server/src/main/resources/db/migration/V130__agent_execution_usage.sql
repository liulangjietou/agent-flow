-- 与业务运行分开的执行观测，不保存模型输入、输出正文、账户或凭据。
CREATE TABLE agent_execution_usage (
    tenant_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    kind VARCHAR(32) NOT NULL CHECK(kind IN ('SUMMARY','DRAFT','INVOICE','EXPENSE_DRAFT','PRECHECK_EXPLANATION','EXPENSE_RISK')),
    owner_id VARCHAR(128) NOT NULL,
    subject_id VARCHAR(36) NOT NULL,
    state_json TEXT NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(tenant_id,kind,run_id)
);
CREATE INDEX idx_agent_usage_owner ON agent_execution_usage(tenant_id,owner_id,recorded_at,run_id);
