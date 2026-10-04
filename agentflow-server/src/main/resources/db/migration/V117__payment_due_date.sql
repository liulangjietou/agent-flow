-- 历史日期保持空值，原决定、状态和资金命令不做回填或重写。
ALTER TABLE payment_authorization ADD COLUMN due_date DATE;
CREATE INDEX payment_authorization_due_idx
    ON payment_authorization(tenant_id,legal_entity_id,due_date,authorized_at,id);
