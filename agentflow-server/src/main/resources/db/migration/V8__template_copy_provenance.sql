-- 模板出处独立保存，不回填或改写既有定义与申请。
ALTER TABLE approval_definition ADD CONSTRAINT uk_definition_tenant_id UNIQUE (tenant_id, id);

CREATE TABLE template_copy (
    definition_id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    template_key VARCHAR(64) NOT NULL,
    template_version BIGINT NOT NULL,
    copied_by VARCHAR(128) NOT NULL,
    copied_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_template_copy_definition FOREIGN KEY (tenant_id, definition_id)
        REFERENCES approval_definition (tenant_id, id),
    CONSTRAINT ck_template_copy_version CHECK (template_version > 0)
);

CREATE INDEX idx_template_copy_tenant ON template_copy (tenant_id, template_key, copied_at DESC);
