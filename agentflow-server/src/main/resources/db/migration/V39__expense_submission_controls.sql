-- 财务控制只能引用同一报销的真实预检、正式财务版本和实际审批轮次。
ALTER TABLE expense_precheck_job ADD CONSTRAINT uq_expense_precheck_report UNIQUE (tenant_id,report_id,id);
CREATE TABLE expense_submission_control (
    tenant_id VARCHAR(64) NOT NULL,
    report_id VARCHAR(36) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    employee_id VARCHAR(128) NOT NULL,
    round_no INTEGER NOT NULL CHECK (round_no > 0),
    submitted_financial_version BIGINT NOT NULL CHECK (submitted_financial_version >= 2),
    precheck_id VARCHAR(36) NOT NULL,
    paper_required BOOLEAN NOT NULL,
    receipt_received BOOLEAN NOT NULL,
    version BIGINT NOT NULL CHECK (version IN (1,2)),
    input_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id,report_id,round_no),
    CONSTRAINT uq_expense_control_precheck UNIQUE (tenant_id,precheck_id),
    CONSTRAINT fk_expense_control_owner FOREIGN KEY (tenant_id,report_id,application_id,employee_id)
        REFERENCES expense_report(tenant_id,id,application_id,employee_id),
    CONSTRAINT fk_expense_control_round FOREIGN KEY (tenant_id,application_id,round_no)
        REFERENCES approval_submission_round(tenant_id,application_id,round_no),
    CONSTRAINT fk_expense_control_financial_version FOREIGN KEY (tenant_id,report_id,submitted_financial_version)
        REFERENCES expense_report_revision(tenant_id,report_id,financial_version),
    CONSTRAINT fk_expense_control_precheck FOREIGN KEY (tenant_id,report_id,precheck_id)
        REFERENCES expense_precheck_job(tenant_id,report_id,id),
    CONSTRAINT ck_expense_control_receipt CHECK ((version=1 AND receipt_received=FALSE)
        OR (version=2 AND receipt_received=TRUE AND paper_required=TRUE))
);
CREATE TABLE expense_submission_control_revision (
    tenant_id VARCHAR(64) NOT NULL,
    report_id VARCHAR(36) NOT NULL,
    round_no INTEGER NOT NULL,
    version BIGINT NOT NULL,
    state_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,report_id,round_no,version),
    CONSTRAINT fk_expense_control_revision FOREIGN KEY (tenant_id,report_id,round_no)
        REFERENCES expense_submission_control(tenant_id,report_id,round_no)
);
