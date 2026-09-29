-- V44 的类型列含数据库自动命名的检查约束；替换该列以兼容 H2 和 PostgreSQL，保留所有原始 JSON 和修订。
ALTER TABLE voucher_preparation ADD COLUMN preparation_kind VARCHAR(32);
UPDATE voucher_preparation SET preparation_kind=kind;
ALTER TABLE voucher_preparation ALTER COLUMN preparation_kind SET NOT NULL;
ALTER TABLE voucher_preparation DROP CONSTRAINT uq_voucher_preparation_attempt;
ALTER TABLE voucher_preparation DROP CONSTRAINT uq_voucher_preparation_active;
ALTER TABLE voucher_preparation DROP CONSTRAINT fk_voucher_preparation_operation;
ALTER TABLE voucher_preparation DROP CONSTRAINT ck_voucher_preparation_kind;
ALTER TABLE voucher_preparation DROP COLUMN kind;
ALTER TABLE voucher_preparation RENAME COLUMN preparation_kind TO kind;
ALTER TABLE voucher_preparation ADD CONSTRAINT uq_voucher_preparation_attempt UNIQUE (tenant_id,application_id,round_no,kind,attempt_no);
ALTER TABLE voucher_preparation ADD CONSTRAINT uq_voucher_preparation_active UNIQUE (tenant_id,active_application_id,round_no,kind);
ALTER TABLE voucher_preparation ADD CONSTRAINT fk_voucher_preparation_operation FOREIGN KEY (tenant_id,operation_id,application_id,round_no,kind)
    REFERENCES voucher_operation(tenant_id,id,application_id,round_no,kind);

-- 付款准备固定到同租户、同业务轮次的实际支付修订，不能借用其他单据的成功回单。
ALTER TABLE payment_authorization ADD CONSTRAINT uq_payment_voucher_source UNIQUE (tenant_id,id,business_type,business_id,application_id,round_no);
ALTER TABLE voucher_preparation ADD COLUMN payment_operation_id VARCHAR(36);
ALTER TABLE voucher_preparation ADD COLUMN payment_version BIGINT;
ALTER TABLE voucher_preparation ADD CONSTRAINT fk_voucher_preparation_payment FOREIGN KEY (tenant_id,payment_operation_id,business_type,business_id,application_id,round_no)
    REFERENCES payment_authorization(tenant_id,id,business_type,business_id,application_id,round_no);
ALTER TABLE voucher_preparation ADD CONSTRAINT fk_voucher_preparation_payment_revision FOREIGN KEY (tenant_id,payment_operation_id,payment_version)
    REFERENCES payment_operation_revision(tenant_id,operation_id,version);
ALTER TABLE voucher_preparation ADD CONSTRAINT ck_voucher_preparation_kind CHECK (
    (kind='PAYMENT' AND payment_operation_id IS NOT NULL AND payment_version IS NOT NULL AND payment_version>0)
    OR (payment_operation_id IS NULL AND payment_version IS NULL AND
        ((business_type='ADVANCE_REQUEST' AND kind='EMPLOYEE_ADVANCE') OR (business_type='EXPENSE' AND kind='EXPENSE_ACCRUAL'))));
CREATE INDEX idx_voucher_preparation_payment ON voucher_preparation(tenant_id,payment_operation_id);
