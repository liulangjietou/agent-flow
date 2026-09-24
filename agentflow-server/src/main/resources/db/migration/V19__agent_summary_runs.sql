-- 仅建立 Agent 摘要运行及追加记录，不启用模型调用，不补造已有申请的 Agent 结果。
CREATE TABLE agent_assist_run (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    application_version BIGINT NOT NULL,
    round_no INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL,
    context_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_agent_run_tenant UNIQUE (tenant_id,id),
    CONSTRAINT fk_agent_run_application FOREIGN KEY (application_id) REFERENCES approval_application(id),
    CONSTRAINT ck_agent_run_context CHECK (application_version > 0 AND round_no > 0),
    CONSTRAINT ck_agent_run_stage CHECK (
        (status='QUEUED' AND version=1)
        OR (status='RUNNING' AND version=2)
        OR (status IN ('COMPLETED','FAILED') AND version=3)
        OR (status IN ('ADOPTED','DISMISSED') AND version=4)
    )
);

CREATE TABLE agent_assist_transition (
    tenant_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    run_version BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    state_json TEXT NOT NULL,
    CONSTRAINT pk_agent_assist_transition PRIMARY KEY (tenant_id,run_id,run_version),
    CONSTRAINT fk_agent_transition_run FOREIGN KEY (tenant_id,run_id) REFERENCES agent_assist_run(tenant_id,id),
    CONSTRAINT ck_agent_transition_version CHECK (run_version > 0)
);
