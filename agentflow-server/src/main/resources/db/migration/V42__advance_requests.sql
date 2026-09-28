-- 借款申请有独立业务绑定，批准记录只供结算准备，不生成已到账余额。
ALTER TABLE approval_application DROP CONSTRAINT ck_application_business;
ALTER TABLE approval_application ADD CONSTRAINT ck_application_business CHECK (
    (business_type IS NULL AND business_id IS NULL) OR
    (business_type IS NOT NULL AND business_id IS NOT NULL AND business_type IN ('EXPENSE','EXPENSE_PLAN','ADVANCE_REQUEST'))
);

CREATE TABLE advance_request (
    id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    business_type VARCHAR(32) NOT NULL DEFAULT 'ADVANCE_REQUEST' CHECK (business_type='ADVANCE_REQUEST'),
    employee_id VARCHAR(128) NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    state_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_advance_request_application UNIQUE (tenant_id,application_id),
    CONSTRAINT fk_advance_request_application_binding FOREIGN KEY (tenant_id,business_type,id,application_id)
        REFERENCES approval_application(tenant_id,business_type,business_id,id)
);
CREATE INDEX idx_advance_request_employee ON advance_request(tenant_id,employee_id,updated_at,id);

CREATE TABLE advance_request_revision (
    tenant_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    request_version BIGINT NOT NULL CHECK (request_version > 0),
    actor_id VARCHAR(128) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    state_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,request_id,request_version),
    CONSTRAINT fk_advance_request_revision FOREIGN KEY (tenant_id,request_id) REFERENCES advance_request(tenant_id,id)
);

-- 预检绑定真实单据归属和已存在的财务版本，不写入审批或财务通过标记。
ALTER TABLE advance_request ADD CONSTRAINT uq_advance_request_check_owner UNIQUE (tenant_id,id,application_id,employee_id);
CREATE TABLE advance_request_check_job (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    employee_id VARCHAR(128) NOT NULL,
    application_version BIGINT NOT NULL CHECK (application_version > 0),
    request_version BIGINT NOT NULL CHECK (request_version > 0),
    attempt_no BIGINT NOT NULL CHECK (attempt_no > 0),
    input_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version BETWEEN 1 AND 3),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','RUNNING','READY','BLOCKED','UNAVAILABLE')),
    active_request_id VARCHAR(36),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_advance_request_check_attempt UNIQUE (tenant_id,request_id,attempt_no),
    CONSTRAINT uq_advance_request_check_active UNIQUE (tenant_id,active_request_id),
    CONSTRAINT ck_advance_request_check_active CHECK (
        (status IN ('QUEUED','RUNNING') AND active_request_id IS NOT NULL AND active_request_id=request_id AND completed_at IS NULL)
        OR (status IN ('READY','BLOCKED','UNAVAILABLE') AND active_request_id IS NULL AND completed_at IS NOT NULL)),
    CONSTRAINT ck_advance_request_check_lease CHECK ((status='QUEUED' AND lease_until IS NULL AND version=1)
        OR (status='RUNNING' AND lease_until IS NOT NULL AND version=2)
        OR (status IN ('READY','BLOCKED','UNAVAILABLE') AND lease_until IS NOT NULL AND version=3)),
    CONSTRAINT fk_advance_request_check_owner FOREIGN KEY (tenant_id,request_id,application_id,employee_id)
        REFERENCES advance_request(tenant_id,id,application_id,employee_id),
    CONSTRAINT fk_advance_request_check_version FOREIGN KEY (tenant_id,request_id,request_version)
        REFERENCES advance_request_revision(tenant_id,request_id,request_version)
);
CREATE INDEX idx_advance_request_check_due ON advance_request_check_job(status,lease_until,created_at,id);
CREATE INDEX idx_advance_request_check_history ON advance_request_check_job(tenant_id,request_id,id);

CREATE TABLE advance_request_check_revision (
    tenant_id VARCHAR(64) NOT NULL,
    job_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL,
    state_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,job_id,version),
    CONSTRAINT fk_advance_request_check_revision FOREIGN KEY (tenant_id,job_id) REFERENCES advance_request_check_job(tenant_id,id)
);
