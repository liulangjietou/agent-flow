-- 部分调整独立保存意图、外部操作和资源完成，旧整单记录与原财务事实保持。
CREATE TABLE expense_partial_adjustment (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    report_id VARCHAR(36) NOT NULL,
    round_no INTEGER NOT NULL CHECK (round_no>0),
    sequence_no BIGINT NOT NULL CHECK (sequence_no>0),
    settlement_version BIGINT NOT NULL,
    consumption_id VARCHAR(36) NOT NULL,
    consumed_version BIGINT NOT NULL,
    accrual_id VARCHAR(36) NOT NULL,
    accrual_version BIGINT NOT NULL,
    previous_id VARCHAR(36),
    previous_version BIGINT,
    input_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    status VARCHAR(24) NOT NULL CHECK (status IN ('WAITING_FINANCE','READY','APPLIED','REVIEW_REQUIRED','RETIRED')),
    active_report_id VARCHAR(36),
    budget_operation_id VARCHAR(36),
    budget_status VARCHAR(24),
    budget_next_at TIMESTAMP WITH TIME ZONE,
    budget_lease_until TIMESTAMP WITH TIME ZONE,
    accrual_operation_id VARCHAR(36),
    accrual_status VARCHAR(24),
    accrual_next_at TIMESTAMP WITH TIME ZONE,
    accrual_lease_until TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    completed_sequence BIGINT,
    retired_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_partial_adjustment_active UNIQUE (tenant_id,active_report_id),
    CONSTRAINT uq_partial_adjustment_sequence UNIQUE (tenant_id,report_id,completed_sequence),
    CONSTRAINT fk_partial_adjustment_report FOREIGN KEY (tenant_id,report_id) REFERENCES expense_report(tenant_id,id),
    CONSTRAINT fk_partial_adjustment_settlement FOREIGN KEY (tenant_id,report_id,settlement_version) REFERENCES expense_settlement_revision(tenant_id,report_id,version),
    CONSTRAINT fk_partial_adjustment_budget FOREIGN KEY (tenant_id,consumption_id,consumed_version) REFERENCES budget_operation_revision(tenant_id,operation_id,version),
    CONSTRAINT fk_partial_adjustment_accrual FOREIGN KEY (tenant_id,accrual_id,accrual_version) REFERENCES voucher_operation_revision(tenant_id,operation_id,version),
    CONSTRAINT ck_partial_adjustment_previous CHECK ((previous_id IS NULL AND previous_version IS NULL AND sequence_no=1) OR (previous_id IS NOT NULL AND previous_version IS NOT NULL AND sequence_no>1)),
    CONSTRAINT ck_partial_adjustment_time CHECK (updated_at>=created_at),
    CONSTRAINT ck_partial_adjustment_active CHECK ((completed_at IS NULL AND retired_at IS NULL AND active_report_id IS NOT NULL AND active_report_id=report_id) OR ((completed_at IS NOT NULL OR retired_at IS NOT NULL) AND active_report_id IS NULL)),
    CONSTRAINT ck_partial_adjustment_completion CHECK ((completed_at IS NULL AND completed_sequence IS NULL AND status<>'APPLIED') OR (completed_at IS NOT NULL AND completed_sequence IS NOT NULL AND completed_sequence=sequence_no AND retired_at IS NULL AND status IN ('APPLIED','REVIEW_REQUIRED'))),
    CONSTRAINT ck_partial_adjustment_retirement CHECK ((retired_at IS NULL AND status<>'RETIRED') OR (retired_at IS NOT NULL AND completed_at IS NULL AND status='RETIRED'))
);
CREATE INDEX idx_partial_adjustment_budget_due ON expense_partial_adjustment(budget_status,budget_next_at,budget_lease_until);
CREATE INDEX idx_partial_adjustment_accrual_due ON expense_partial_adjustment(accrual_status,accrual_next_at,accrual_lease_until);
CREATE INDEX idx_partial_adjustment_history ON expense_partial_adjustment(tenant_id,report_id,sequence_no,created_at,id);
CREATE TABLE expense_partial_adjustment_revision (
    tenant_id VARCHAR(64) NOT NULL,
    adjustment_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,adjustment_id,version),
    CONSTRAINT fk_partial_adjustment_revision FOREIGN KEY (tenant_id,adjustment_id) REFERENCES expense_partial_adjustment(tenant_id,id)
);
ALTER TABLE expense_partial_adjustment ADD CONSTRAINT fk_partial_adjustment_previous FOREIGN KEY (tenant_id,previous_id,previous_version)
    REFERENCES expense_partial_adjustment_revision(tenant_id,adjustment_id,version);
-- 每个写编号永久保留首次注册修订，重新授权只新增编号，不改旧命令。
CREATE TABLE expense_partial_adjustment_operation (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    adjustment_id VARCHAR(36) NOT NULL,
    side VARCHAR(16) NOT NULL CHECK (side IN ('BUDGET','ACCRUAL')),
    adjustment_version BIGINT NOT NULL,
    input_json TEXT NOT NULL,
    authorization_source_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT fk_partial_operation_revision FOREIGN KEY (tenant_id,adjustment_id,adjustment_version) REFERENCES expense_partial_adjustment_revision(tenant_id,adjustment_id,version)
);
-- 先占用完整已登记回款；只有明确安全结束才能释放，历史登记身份保持。
CREATE TABLE expense_partial_adjustment_return (
    tenant_id VARCHAR(64) NOT NULL,
    adjustment_id VARCHAR(36) NOT NULL,
    report_id VARCHAR(36) NOT NULL,
    funds_identity VARCHAR(128) NOT NULL,
    registration_id VARCHAR(36) NOT NULL,
    entry_json TEXT NOT NULL,
    active_funds_identity VARCHAR(128),
    completed_at TIMESTAMP WITH TIME ZONE,
    released_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (tenant_id,adjustment_id,funds_identity),
    CONSTRAINT uq_partial_return_active UNIQUE (tenant_id,report_id,active_funds_identity),
    CONSTRAINT fk_partial_return_adjustment FOREIGN KEY (tenant_id,adjustment_id) REFERENCES expense_partial_adjustment(tenant_id,id),
    CONSTRAINT fk_partial_return_registration FOREIGN KEY (tenant_id,registration_id) REFERENCES expense_payment_return_registration(tenant_id,id),
    CONSTRAINT ck_partial_return_active CHECK ((released_at IS NULL AND active_funds_identity IS NOT NULL AND active_funds_identity=funds_identity) OR (released_at IS NOT NULL AND active_funds_identity IS NULL AND completed_at IS NULL))
);
