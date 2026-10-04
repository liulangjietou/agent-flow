CREATE TABLE expense_prior_control (
    tenant_id VARCHAR(64) NOT NULL,
    report_id VARCHAR(36) NOT NULL,
    round_no INTEGER NOT NULL CHECK (round_no > 0),
    application_id VARCHAR(36) NOT NULL,
    application_version BIGINT NOT NULL CHECK (application_version > 0),
    financial_version BIGINT NOT NULL CHECK (financial_version > 1),
    definition_id VARCHAR(36) NOT NULL,
    definition_version BIGINT NOT NULL CHECK (definition_version > 0),
    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    requires_approval BOOLEAN NOT NULL,
    snapshot_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,report_id,round_no),
    CONSTRAINT uq_expense_prior_application UNIQUE (tenant_id,application_id,round_no),
    CONSTRAINT fk_expense_prior_report FOREIGN KEY (tenant_id,report_id,application_id) REFERENCES expense_report(tenant_id,id,application_id),
    CONSTRAINT fk_expense_prior_revision FOREIGN KEY (tenant_id,report_id,financial_version) REFERENCES expense_report_revision(tenant_id,report_id,financial_version),
    CONSTRAINT fk_expense_prior_definition FOREIGN KEY (tenant_id,definition_id) REFERENCES approval_definition(tenant_id,id)
);

CREATE TABLE expense_prior_control_source (
    tenant_id VARCHAR(64) NOT NULL,
    report_id VARCHAR(36) NOT NULL,
    round_no INTEGER NOT NULL,
    resource_type VARCHAR(32) NOT NULL CHECK (resource_type = 'PRIOR_REQUEST'),
    request_id VARCHAR(36) NOT NULL,
    before_version BIGINT NOT NULL CHECK (before_version > 0),
    after_version BIGINT NOT NULL CHECK (after_version > before_version),
    PRIMARY KEY (tenant_id,report_id,round_no,request_id),
    CONSTRAINT fk_expense_prior_source_parent FOREIGN KEY (tenant_id,report_id,round_no) REFERENCES expense_prior_control(tenant_id,report_id,round_no),
    CONSTRAINT fk_expense_prior_source_before FOREIGN KEY (tenant_id,resource_type,request_id,before_version) REFERENCES finance_resource_revision(tenant_id,resource_type,resource_id,version),
    CONSTRAINT fk_expense_prior_source_after FOREIGN KEY (tenant_id,resource_type,request_id,after_version) REFERENCES finance_resource_revision(tenant_id,resource_type,resource_id,version)
);
