-- 单侧只读准备保留原调整修订，明确授权消费才登记新的独立财务操作。
CREATE TABLE expense_partial_adjustment_preparation (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    adjustment_id VARCHAR(36) NOT NULL,
    adjustment_version BIGINT NOT NULL CHECK (adjustment_version>0),
    report_id VARCHAR(36) NOT NULL,
    side VARCHAR(16) NOT NULL CHECK (side IN ('BUDGET','ACCRUAL')),
    requested_by VARCHAR(128) NOT NULL,
    input_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    status VARCHAR(24) NOT NULL CHECK (status IN ('QUEUED','RUNNING','READY','AUTHORIZED','UNAVAILABLE','VOIDED')),
    active_slot SMALLINT,
    lease_until TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_partial_preparation_binding UNIQUE (tenant_id,id,adjustment_id),
    CONSTRAINT uq_partial_preparation_active UNIQUE (tenant_id,adjustment_id,side,requested_by,active_slot),
    CONSTRAINT fk_partial_preparation_source FOREIGN KEY (tenant_id,adjustment_id,adjustment_version)
        REFERENCES expense_partial_adjustment_revision(tenant_id,adjustment_id,version),
    CONSTRAINT ck_partial_preparation_active CHECK ((status IN ('QUEUED','RUNNING') AND active_slot IS NOT NULL AND active_slot=1) OR (status NOT IN ('QUEUED','RUNNING') AND active_slot IS NULL)),
    CONSTRAINT ck_partial_preparation_lease CHECK ((status='RUNNING' AND lease_until IS NOT NULL AND lease_until>updated_at) OR (status<>'RUNNING' AND lease_until IS NULL)),
    CONSTRAINT ck_partial_preparation_time CHECK (updated_at>=created_at)
);
CREATE INDEX idx_partial_preparation_due ON expense_partial_adjustment_preparation(status,lease_until,created_at,id);
CREATE INDEX idx_partial_preparation_history ON expense_partial_adjustment_preparation(tenant_id,adjustment_id,side,requested_by,created_at,id);
CREATE TABLE expense_partial_adjustment_preparation_revision (
    tenant_id VARCHAR(64) NOT NULL,
    preparation_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,preparation_id,version),
    CONSTRAINT fk_partial_preparation_revision FOREIGN KEY (tenant_id,preparation_id) REFERENCES expense_partial_adjustment_preparation(tenant_id,id)
);
CREATE TABLE expense_partial_adjustment_authorization (
    tenant_id VARCHAR(64) NOT NULL,
    preparation_id VARCHAR(36) NOT NULL,
    adjustment_id VARCHAR(36) NOT NULL,
    preparation_before BIGINT NOT NULL,
    preparation_after BIGINT NOT NULL,
    adjustment_before BIGINT NOT NULL,
    adjustment_after BIGINT NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    authorized_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id,preparation_id),
    CONSTRAINT uq_partial_authorization_operation UNIQUE (tenant_id,operation_id),
    CONSTRAINT fk_partial_authorization_binding FOREIGN KEY (tenant_id,preparation_id,adjustment_id) REFERENCES expense_partial_adjustment_preparation(tenant_id,id,adjustment_id),
    CONSTRAINT fk_partial_authorization_preparation_before FOREIGN KEY (tenant_id,preparation_id,preparation_before) REFERENCES expense_partial_adjustment_preparation_revision(tenant_id,preparation_id,version),
    CONSTRAINT fk_partial_authorization_preparation_after FOREIGN KEY (tenant_id,preparation_id,preparation_after) REFERENCES expense_partial_adjustment_preparation_revision(tenant_id,preparation_id,version),
    CONSTRAINT fk_partial_authorization_adjustment_before FOREIGN KEY (tenant_id,adjustment_id,adjustment_before) REFERENCES expense_partial_adjustment_revision(tenant_id,adjustment_id,version),
    CONSTRAINT fk_partial_authorization_adjustment_after FOREIGN KEY (tenant_id,adjustment_id,adjustment_after) REFERENCES expense_partial_adjustment_revision(tenant_id,adjustment_id,version),
    CONSTRAINT fk_partial_authorization_operation FOREIGN KEY (tenant_id,operation_id) REFERENCES expense_partial_adjustment_operation(tenant_id,id),
    CONSTRAINT ck_partial_authorization_adjacent CHECK (preparation_after=preparation_before+1 AND adjustment_after=adjustment_before+1 AND operation_id=preparation_id)
);
