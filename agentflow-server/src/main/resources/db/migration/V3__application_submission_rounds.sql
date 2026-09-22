-- 旧申请不补造历史内容，新提交才写入实际实例对应的不可变快照。
CREATE TABLE approval_submission_round (
    tenant_id VARCHAR(64) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    round_no INT NOT NULL,
    process_instance_id VARCHAR(128) NOT NULL,
    definition_version BIGINT NOT NULL,
    title VARCHAR(256) NOT NULL,
    payload_json TEXT NOT NULL,
    submitted_by VARCHAR(128) NOT NULL,
    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(32) NOT NULL,
    reason TEXT NULL,
    completed_by VARCHAR(128) NULL,
    completed_at TIMESTAMP WITH TIME ZONE NULL,
    CONSTRAINT pk_submission_round PRIMARY KEY (tenant_id, application_id, round_no),
    CONSTRAINT uk_submission_instance UNIQUE (tenant_id, process_instance_id),
    CONSTRAINT fk_submission_application FOREIGN KEY (application_id) REFERENCES approval_application(id),
    CONSTRAINT ck_submission_round_positive CHECK (round_no > 0),
    CONSTRAINT ck_submission_conclusion CHECK (
        (status = 'IN_APPROVAL' AND reason IS NULL AND completed_by IS NULL AND completed_at IS NULL)
        OR (status IN ('RETURNED', 'REJECTED', 'APPROVED') AND completed_by IS NOT NULL AND completed_at IS NOT NULL)
    )
);
