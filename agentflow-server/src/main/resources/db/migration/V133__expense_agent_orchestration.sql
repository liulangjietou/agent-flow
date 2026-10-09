CREATE TABLE agent_expense_orchestration (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    handling_id VARCHAR(36) NOT NULL,
    report_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK(version > 0),
    status VARCHAR(32) NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE,
    context_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    checked_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(tenant_id,id),
    UNIQUE(tenant_id,handling_id),
    FOREIGN KEY(tenant_id,handling_id) REFERENCES agent_expense_handling(tenant_id,id)
);
-- 旧 CHECK 未命名，使用可移植重建保留所有观测，增加办理决策用途。
ALTER TABLE agent_execution_usage RENAME TO agent_execution_usage_legacy;
CREATE TABLE agent_execution_usage (
    tenant_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    kind VARCHAR(32) NOT NULL CONSTRAINT ck_agent_usage_kind CHECK(kind IN ('SUMMARY','DRAFT','INVOICE','EXPENSE_DRAFT','PRECHECK_EXPLANATION','EXPENSE_RISK','HANDLING')),
    owner_id VARCHAR(128) NOT NULL,
    subject_id VARCHAR(36) NOT NULL,
    state_json TEXT NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(tenant_id,kind,run_id)
);
INSERT INTO agent_execution_usage SELECT * FROM agent_execution_usage_legacy;
DROP TABLE agent_execution_usage_legacy;
CREATE INDEX idx_agent_usage_owner ON agent_execution_usage(tenant_id,owner_id,recorded_at,run_id);
CREATE INDEX idx_expense_orchestration_poll ON agent_expense_orchestration(status,lease_until,created_at);
CREATE TABLE agent_expense_orchestration_transition (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL,
    state_json TEXT NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(tenant_id,id,version),
    FOREIGN KEY(tenant_id,id) REFERENCES agent_expense_orchestration(tenant_id,id)
);
CREATE TABLE agent_handling_model_call (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    orchestration_id VARCHAR(36) NOT NULL,
    report_id VARCHAR(36) NOT NULL,
    input_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY(tenant_id,id),
    FOREIGN KEY(tenant_id,orchestration_id) REFERENCES agent_expense_orchestration(tenant_id,id)
);
CREATE TABLE agent_handling_child (
    tenant_id VARCHAR(64) NOT NULL,
    handling_id VARCHAR(36) NOT NULL,
    child_id VARCHAR(36) NOT NULL,
    kind VARCHAR(32) NOT NULL CHECK(kind IN ('INVOICE_EXTRACTION','DRAFT')),
    report_id VARCHAR(36) NOT NULL,
    application_version BIGINT NOT NULL CHECK(application_version > 0),
    financial_version BIGINT NOT NULL CHECK(financial_version > 0),
    PRIMARY KEY(tenant_id,child_id,kind),
    FOREIGN KEY(tenant_id,handling_id) REFERENCES agent_expense_handling(tenant_id,id)
);
