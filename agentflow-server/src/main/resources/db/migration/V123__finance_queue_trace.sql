-- 追踪来源独立于财务输入、摘要和状态；旧记录不回填虚构的原请求。
ALTER TABLE expense_precheck_job ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE invoice_verification_job ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE budget_operation ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE voucher_operation ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE payment_operation ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE voucher_preparation ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE payment_execution_request ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE payment_payee_review ADD COLUMN trace_id VARCHAR(36);
