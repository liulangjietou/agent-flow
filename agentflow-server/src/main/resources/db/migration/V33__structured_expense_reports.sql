-- 旧申请保持无业务绑定，不能仅根据原流程名称把历史表单推断成财务单据。
ALTER TABLE approval_application ADD COLUMN business_type VARCHAR(32);
ALTER TABLE approval_application ADD COLUMN business_id VARCHAR(36);
ALTER TABLE approval_application ADD CONSTRAINT ck_application_business CHECK (
    (business_type IS NULL AND business_id IS NULL) OR
    (business_type IS NOT NULL AND business_id IS NOT NULL AND business_type IN ('EXPENSE'))
);
ALTER TABLE approval_application ADD CONSTRAINT uq_application_business UNIQUE (tenant_id,business_type,business_id);
ALTER TABLE approval_application ADD CONSTRAINT uq_application_business_binding UNIQUE (tenant_id,business_type,business_id,id);

CREATE TABLE expense_report (
    id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    business_type VARCHAR(32) NOT NULL DEFAULT 'EXPENSE' CHECK (business_type='EXPENSE'),
    employee_id VARCHAR(128) NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    state_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_expense_application UNIQUE (tenant_id,application_id),
    CONSTRAINT fk_expense_application_binding FOREIGN KEY (tenant_id,business_type,id,application_id)
        REFERENCES approval_application(tenant_id,business_type,business_id,id)
);
CREATE INDEX idx_expense_employee ON expense_report(tenant_id,employee_id,updated_at,id);

CREATE TABLE expense_report_revision (
    tenant_id VARCHAR(64) NOT NULL,
    report_id VARCHAR(36) NOT NULL,
    financial_version BIGINT NOT NULL CHECK (financial_version > 0),
    actor_id VARCHAR(128) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    state_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,report_id,financial_version),
    CONSTRAINT fk_expense_revision FOREIGN KEY (tenant_id,report_id) REFERENCES expense_report(tenant_id,id)
);
