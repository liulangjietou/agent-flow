-- 草稿建议独立保存，不改变旧摘要及审批申请；同租户外键约束所有来源。
CREATE TABLE agent_draft_assist_run (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    application_version BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL,
    context_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_draft_assist_tenant UNIQUE (tenant_id,id),
    CONSTRAINT fk_draft_assist_application FOREIGN KEY (tenant_id,application_id) REFERENCES approval_application(tenant_id,id),
    CONSTRAINT ck_draft_assist_input_version CHECK (application_version > 0),
    CONSTRAINT ck_draft_assist_stage CHECK (
        (status='QUEUED' AND version=1) OR (status='RUNNING' AND version=2)
        OR (status IN ('COMPLETED','FAILED') AND version=3)
        OR (status IN ('ADOPTED','DISMISSED') AND version=4)
    )
);
CREATE INDEX idx_draft_assist_due ON agent_draft_assist_run(status,created_at,id);
CREATE INDEX idx_draft_assist_application ON agent_draft_assist_run(tenant_id,application_id,created_at,id);

CREATE TABLE agent_draft_assist_transition (
    tenant_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    run_version BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,run_id,run_version),
    CONSTRAINT fk_draft_assist_transition FOREIGN KEY (tenant_id,run_id) REFERENCES agent_draft_assist_run(tenant_id,id)
);
