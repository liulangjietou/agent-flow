-- 诊断来源只供关联，不参与授权、状态摘要或发送同意；历史行不补造 HTTP 来源。
ALTER TABLE agent_assist_job ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE agent_draft_assist_run ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE agent_invoice_extraction_run ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE agent_expense_draft_run ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE agent_precheck_explanation_run ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE agent_expense_risk_run ADD COLUMN trace_id VARCHAR(36);
