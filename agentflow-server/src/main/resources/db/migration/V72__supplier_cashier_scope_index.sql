-- 当前法人任职先过滤后分页，索引沿原授权时间稳定排序，不改变历史资金事实。
CREATE INDEX idx_supplier_authorization_cashier ON supplier_payment_authorization(tenant_id,legal_entity_id,authorized_at,id);
