-- 来源独立保存，派生队列沿用当前工作器作用域；历史记录保持空值，不改变命令与凭据。
ALTER TABLE supplier_adjustment_preparation ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE supplier_payable_adjustment_operation ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE supplier_payable_hold_operation ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE supplier_payable_review ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE supplier_payment_execution_request ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE supplier_payment_return_check ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE supplier_payment_operation ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE supplier_settlement_preparation ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE supplier_payable_settlement_operation ADD COLUMN trace_id VARCHAR(36);
