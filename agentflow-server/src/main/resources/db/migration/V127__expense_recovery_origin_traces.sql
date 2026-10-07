-- 独立保存恢复来源；部分调整两侧保留各自授权来源，旧记录保持空值，不改变业务快照。
ALTER TABLE expense_partial_adjustment_preparation ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE expense_partial_adjustment ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE expense_partial_adjustment_operation ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE expense_resource_adjustment_preparation ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE expense_resource_adjustment ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE expense_settlement ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE expense_budget_review ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE budget_consumption_reversal_operation ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE voucher_reversal_preparation ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE voucher_reversal_operation ADD COLUMN trace_id VARCHAR(36);
