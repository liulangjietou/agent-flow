-- 例外只能引用本单本财务版本的原操作，旧操作和旧轮次均不回填。
ALTER TABLE budget_operation ADD CONSTRAINT uq_budget_operation_position UNIQUE (tenant_id,id,report_id,financial_version);

CREATE TABLE expense_budget_review (
    tenant_id VARCHAR(64) NOT NULL,
    report_id VARCHAR(36) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    employee_id VARCHAR(128) NOT NULL,
    round_no INTEGER NOT NULL CHECK (round_no > 0),
    submitted_financial_version BIGINT NOT NULL CHECK (submitted_financial_version >= 2),
    precheck_id VARCHAR(36) NOT NULL,
    original_operation_id VARCHAR(36) NOT NULL,
    target_digest VARCHAR(64) NOT NULL,
    budget_node_id VARCHAR(128),
    policy_reference VARCHAR(128),
    version BIGINT NOT NULL CHECK (version > 0),
    status VARCHAR(32) NOT NULL CHECK (status IN ('WAITING_BUDGET','REVIEW_REQUIRED','AUTHORIZED','CONFIRMED','REJECTED','CLOSED')),
    authorized_operation_id VARCHAR(36),
    decision_audit_id VARCHAR(36),
    automatic_audit_id VARCHAR(36),
    input_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_check_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id,report_id,round_no),
    CONSTRAINT uq_expense_budget_review_original UNIQUE (tenant_id,original_operation_id),
    CONSTRAINT uq_expense_budget_review_authorized UNIQUE (tenant_id,authorized_operation_id),
    CONSTRAINT uq_expense_budget_review_decision UNIQUE (tenant_id,decision_audit_id),
    CONSTRAINT uq_expense_budget_review_automatic UNIQUE (tenant_id,automatic_audit_id),
    CONSTRAINT fk_expense_budget_review_owner FOREIGN KEY (tenant_id,report_id,application_id,employee_id)
        REFERENCES expense_report(tenant_id,id,application_id,employee_id),
    CONSTRAINT fk_expense_budget_review_control FOREIGN KEY (tenant_id,report_id,round_no)
        REFERENCES expense_submission_control(tenant_id,report_id,round_no),
    CONSTRAINT fk_expense_budget_review_precheck FOREIGN KEY (tenant_id,report_id,precheck_id)
        REFERENCES expense_precheck_job(tenant_id,report_id,id),
    CONSTRAINT fk_expense_budget_review_original FOREIGN KEY (tenant_id,original_operation_id,report_id,submitted_financial_version)
        REFERENCES budget_operation(tenant_id,id,report_id,financial_version),
    CONSTRAINT fk_expense_budget_review_authorized FOREIGN KEY (tenant_id,authorized_operation_id,report_id,submitted_financial_version)
        REFERENCES budget_operation(tenant_id,id,report_id,financial_version),
    CONSTRAINT fk_expense_budget_review_decision FOREIGN KEY (tenant_id,decision_audit_id) REFERENCES audit_event(tenant_id,event_id),
    CONSTRAINT fk_expense_budget_review_automatic FOREIGN KEY (tenant_id,automatic_audit_id) REFERENCES audit_event(tenant_id,event_id),
    CONSTRAINT ck_expense_budget_review_policy CHECK (policy_reference IS NULL OR budget_node_id IS NOT NULL),
    CONSTRAINT ck_expense_budget_review_authorized CHECK (
        (authorized_operation_id IS NULL AND decision_audit_id IS NULL) OR
        (authorized_operation_id IS NOT NULL AND decision_audit_id IS NOT NULL AND policy_reference IS NOT NULL
            AND authorized_operation_id<>original_operation_id AND automatic_audit_id IS NULL)),
    CONSTRAINT ck_expense_budget_review_state CHECK (
        (status='WAITING_BUDGET' AND version=1 AND authorized_operation_id IS NULL AND automatic_audit_id IS NULL) OR
        (status='REVIEW_REQUIRED' AND version>=2 AND policy_reference IS NOT NULL AND authorized_operation_id IS NULL AND automatic_audit_id IS NULL) OR
        (status='AUTHORIZED' AND version>=3 AND authorized_operation_id IS NOT NULL AND automatic_audit_id IS NULL) OR
        (status IN ('CONFIRMED','REJECTED','CLOSED') AND version>=2)),
    CONSTRAINT ck_expense_budget_review_automatic CHECK (automatic_audit_id IS NULL OR
        (status IN ('CONFIRMED','CLOSED') AND budget_node_id IS NOT NULL AND authorized_operation_id IS NULL)),
    CONSTRAINT ck_expense_budget_review_time CHECK (updated_at>=submitted_at)
);
CREATE INDEX idx_expense_budget_review_due ON expense_budget_review(next_check_at,status,tenant_id,report_id,round_no);

CREATE TABLE expense_budget_review_revision (
    tenant_id VARCHAR(64) NOT NULL,
    report_id VARCHAR(36) NOT NULL,
    round_no INTEGER NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    state_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,report_id,round_no,version),
    CONSTRAINT fk_expense_budget_review_revision FOREIGN KEY (tenant_id,report_id,round_no)
        REFERENCES expense_budget_review(tenant_id,report_id,round_no)
);
