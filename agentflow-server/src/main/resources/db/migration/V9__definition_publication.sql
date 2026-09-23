-- 只记录本次迁移后的发布事实，历史版本的发布者和说明不回填。
CREATE TABLE definition_publication (
    definition_id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    process_key VARCHAR(128) NOT NULL,
    definition_version BIGINT NOT NULL,
    published_by VARCHAR(128) NOT NULL,
    authorized_role VARCHAR(32) NOT NULL,
    published_at TIMESTAMP NOT NULL,
    change_note VARCHAR(2000) NOT NULL,
    validation_json TEXT NOT NULL,
    CONSTRAINT fk_publication_definition FOREIGN KEY (tenant_id, definition_id)
        REFERENCES approval_definition (tenant_id, id),
    CONSTRAINT uk_publication_version UNIQUE (tenant_id, process_key, definition_version),
    CONSTRAINT ck_publication_version CHECK (definition_version > 0),
    CONSTRAINT ck_publication_role CHECK (authorized_role IN ('ADMIN', 'PROCESS_ADMIN'))
);
