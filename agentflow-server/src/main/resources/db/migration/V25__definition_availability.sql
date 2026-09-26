-- 迁移保留已有版本可发起的行为，不虚构历史开关操作。
ALTER TABLE approval_definition ADD COLUMN start_enabled BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE definition_availability_change (
    tenant_id VARCHAR(64) NOT NULL,
    definition_id VARCHAR(36) NOT NULL,
    revision BIGINT NOT NULL,
    previous_enabled BOOLEAN NOT NULL,
    start_enabled BOOLEAN NOT NULL,
    changed_by VARCHAR(128) NOT NULL,
    authorized_role VARCHAR(32) NOT NULL,
    changed_at TIMESTAMP NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    PRIMARY KEY (tenant_id, definition_id, revision),
    CONSTRAINT fk_availability_definition FOREIGN KEY (tenant_id, definition_id)
        REFERENCES approval_definition (tenant_id, id),
    CONSTRAINT ck_availability_revision CHECK (revision > 1),
    CONSTRAINT ck_availability_changed CHECK (previous_enabled <> start_enabled),
    CONSTRAINT ck_availability_role CHECK (authorized_role IN ('ADMIN', 'PROCESS_ADMIN'))
);
