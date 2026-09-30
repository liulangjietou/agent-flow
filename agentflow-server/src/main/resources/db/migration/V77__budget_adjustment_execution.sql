-- 批准后财务读取是独立任务；一次读取只有一次明确消费，原台账不接受客户端替换。
CREATE TABLE budget_adjustment_review (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    employee_id VARCHAR(128) NOT NULL,
    request_version BIGINT NOT NULL CHECK (request_version>0),
    requested_by VARCHAR(128) NOT NULL,
    attempt_no BIGINT NOT NULL CHECK (attempt_no>0),
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    input_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version BETWEEN 1 AND 4),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','RUNNING','READY','CONSUMED','BLOCKED','UNAVAILABLE','VOIDED')),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    lease_until TIMESTAMP WITH TIME ZONE,
    checked_at TIMESTAMP WITH TIME ZONE,
    active_request_id VARCHAR(36),
    consumed_operation_id VARCHAR(36),
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_budget_review_active UNIQUE (tenant_id,requested_by,active_request_id),
    CONSTRAINT uq_budget_review_attempt UNIQUE (tenant_id,request_id,requested_by,attempt_no),
    CONSTRAINT uq_budget_review_consumed UNIQUE (tenant_id,consumed_operation_id),
    CONSTRAINT uq_budget_review_owner UNIQUE (tenant_id,id,request_id,application_id,employee_id,request_version,requested_by),
    CONSTRAINT fk_budget_review_owner FOREIGN KEY (tenant_id,request_id,application_id,employee_id)
        REFERENCES budget_adjustment(tenant_id,id,application_id,employee_id),
    CONSTRAINT fk_budget_review_version FOREIGN KEY (tenant_id,request_id,request_version)
        REFERENCES budget_adjustment_revision(tenant_id,request_id,request_version),
    CONSTRAINT ck_budget_review_separation CHECK (requested_by<>employee_id),
    CONSTRAINT ck_budget_review_time CHECK (updated_at>=requested_at),
    CONSTRAINT ck_budget_review_active CHECK (
        (status IN ('QUEUED','RUNNING') AND active_request_id IS NOT NULL AND active_request_id=request_id)
        OR (status NOT IN ('QUEUED','RUNNING') AND active_request_id IS NULL)),
    CONSTRAINT ck_budget_review_lease CHECK (
        (status='QUEUED' AND version=1 AND started_at IS NULL AND lease_until IS NULL)
        OR (status='VOIDED' AND version=2 AND started_at IS NULL AND lease_until IS NULL)
        OR (status<>'QUEUED' AND started_at IS NOT NULL AND started_at>=requested_at AND started_at<=updated_at
            AND lease_until IS NOT NULL AND lease_until>started_at
            AND ((status='RUNNING' AND version=2) OR (status='CONSUMED' AND version=4) OR (status NOT IN ('RUNNING','CONSUMED') AND version=3)))),
    CONSTRAINT ck_budget_review_evidence CHECK (
        (status IN ('READY','CONSUMED') AND checked_at IS NOT NULL AND checked_at>=started_at AND checked_at<lease_until AND checked_at<=updated_at)
        OR (status NOT IN ('READY','CONSUMED') AND checked_at IS NULL)),
    CONSTRAINT ck_budget_review_consumed CHECK (
        (status='CONSUMED' AND consumed_operation_id IS NOT NULL) OR (status<>'CONSUMED' AND consumed_operation_id IS NULL))
);
CREATE INDEX idx_budget_review_due ON budget_adjustment_review(status,lease_until,requested_at,id);
CREATE INDEX idx_budget_review_history ON budget_adjustment_review(tenant_id,request_id,requested_by,attempt_no);

CREATE TABLE budget_adjustment_review_revision (
    tenant_id VARCHAR(64) NOT NULL,
    review_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version BETWEEN 1 AND 4),
    state_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,review_id,version),
    CONSTRAINT fk_budget_review_revision FOREIGN KEY (tenant_id,review_id) REFERENCES budget_adjustment_review(tenant_id,id)
);

-- 指令本身就是不可变授权；同一申请只保留一个活动执行，结束证明不能由查无或超时替代。
CREATE TABLE budget_adjustment_operation (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    employee_id VARCHAR(128) NOT NULL,
    request_version BIGINT NOT NULL CHECK (request_version>0),
    authorized_by VARCHAR(128) NOT NULL,
    review_id VARCHAR(36) NOT NULL,
    review_version BIGINT NOT NULL CHECK (review_version=3),
    command_json TEXT NOT NULL,
    command_digest VARCHAR(64) NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','EXECUTING','UNKNOWN','QUERYING','APPLIED','REJECTED','NOT_FOUND','EXPIRED','VOIDED','RECONCILING')),
    attempts INTEGER NOT NULL CHECK (attempts>=0 AND attempts<version),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    lease_until TIMESTAMP WITH TIME ZONE,
    active_request_id VARCHAR(36),
    retired_version BIGINT,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_budget_adjustment_operation_active UNIQUE (tenant_id,active_request_id),
    CONSTRAINT uq_budget_adjustment_operation_review UNIQUE (tenant_id,review_id),
    CONSTRAINT uq_budget_adjustment_operation_owner UNIQUE (tenant_id,id,request_id,application_id,employee_id,request_version,authorized_by),
    CONSTRAINT fk_budget_adjustment_operation_review FOREIGN KEY (tenant_id,review_id,request_id,application_id,employee_id,request_version,authorized_by)
        REFERENCES budget_adjustment_review(tenant_id,id,request_id,application_id,employee_id,request_version,requested_by),
    CONSTRAINT fk_budget_adjustment_operation_evidence FOREIGN KEY (tenant_id,review_id,review_version)
        REFERENCES budget_adjustment_review_revision(tenant_id,review_id,version),
    CONSTRAINT ck_budget_adjustment_operation_time CHECK (updated_at>=created_at),
    CONSTRAINT ck_budget_adjustment_operation_active CHECK (
        (retired_version IS NULL AND active_request_id IS NOT NULL AND active_request_id=request_id)
        OR (retired_version IS NOT NULL AND retired_version=version AND active_request_id IS NULL AND status IN ('REJECTED','EXPIRED','VOIDED'))),
    CONSTRAINT ck_budget_adjustment_operation_schedule CHECK (
        (status IN ('QUEUED','UNKNOWN') AND next_attempt_at IS NOT NULL AND next_attempt_at>=updated_at AND lease_until IS NULL)
        OR (status IN ('EXECUTING','QUERYING') AND next_attempt_at IS NULL AND lease_until IS NOT NULL AND lease_until>updated_at AND attempts>0)
        OR (status IN ('APPLIED','REJECTED','NOT_FOUND','EXPIRED','VOIDED','RECONCILING') AND next_attempt_at IS NULL AND lease_until IS NULL))
);
CREATE INDEX idx_budget_adjustment_operation_due ON budget_adjustment_operation(status,next_attempt_at,lease_until,created_at,id);
CREATE INDEX idx_budget_adjustment_operation_history ON budget_adjustment_operation(tenant_id,request_id,created_at,id);
ALTER TABLE budget_adjustment_review ADD CONSTRAINT fk_budget_review_consumed_operation
    FOREIGN KEY (tenant_id,consumed_operation_id,request_id,application_id,employee_id,request_version,requested_by)
    REFERENCES budget_adjustment_operation(tenant_id,id,request_id,application_id,employee_id,request_version,authorized_by);

CREATE TABLE budget_adjustment_operation_revision (
    tenant_id VARCHAR(64) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    state_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,operation_id,version),
    CONSTRAINT fk_budget_adjustment_operation_revision FOREIGN KEY (tenant_id,operation_id) REFERENCES budget_adjustment_operation(tenant_id,id)
);

CREATE TABLE budget_adjustment_retirement (
    tenant_id VARCHAR(64) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    operation_version BIGINT NOT NULL,
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,operation_id),
    CONSTRAINT fk_budget_retirement_proof FOREIGN KEY (tenant_id,operation_id,operation_version)
        REFERENCES budget_adjustment_operation_revision(tenant_id,operation_id,version),
    CONSTRAINT uq_budget_retirement_version UNIQUE (tenant_id,operation_id,operation_version)
);
ALTER TABLE budget_adjustment_operation ADD CONSTRAINT fk_budget_adjustment_operation_retirement
    FOREIGN KEY (tenant_id,id,retired_version) REFERENCES budget_adjustment_retirement(tenant_id,operation_id,operation_version);
