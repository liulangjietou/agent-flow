-- 只给新执行创建冻结输入及租约，旧运行继续使用原有保守读取权限。
CREATE TABLE agent_assist_job (
    tenant_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    requester_json TEXT NOT NULL,
    sources_json TEXT NOT NULL,
    target_digest VARCHAR(64) NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (tenant_id,run_id),
    CONSTRAINT fk_assist_job_run FOREIGN KEY (tenant_id,run_id) REFERENCES agent_assist_run(tenant_id,id)
);
CREATE INDEX idx_assist_job_lease ON agent_assist_job(lease_until,run_id);
