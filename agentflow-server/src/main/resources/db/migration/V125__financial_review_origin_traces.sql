-- 诊断来源独立保存；历史记录保持空值，不改变业务快照、授权和幂等命令。
ALTER TABLE budget_adjustment_check_job ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE budget_adjustment_review ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE budget_adjustment_operation ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE disbursement_return_check ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE repayment_review_check ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE advance_repayment_check ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE advance_request_check_job ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE expense_payment_return_check ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE expense_plan_check_job ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE voucher_reversal_check ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE procurement_payment_check_job ADD COLUMN trace_id VARCHAR(36);
