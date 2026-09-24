-- 单申请运行目录采用数据库时间与 ID 倒序；不回填或改写已有运行。
CREATE INDEX idx_agent_run_application ON agent_assist_run(tenant_id,application_id,created_at,id);
CREATE INDEX idx_agent_run_round ON agent_assist_run(tenant_id,application_id,round_no,created_at,id);
