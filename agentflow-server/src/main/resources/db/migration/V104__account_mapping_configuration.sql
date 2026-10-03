-- 科目管理只新增配置及历史；既有凭证正文、摘要和已记录的 ERP 事实保持原样。
CREATE TABLE account_mapping_scope (
    tenant_id VARCHAR(64) NOT NULL,
    legal_entity_id VARCHAR(36) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    active_revision BIGINT NOT NULL DEFAULT 0 CHECK (active_revision >= 0),
    active_mapping_id VARCHAR(36),
    active_mapping_version BIGINT,
    PRIMARY KEY (tenant_id, legal_entity_id, currency),
    FOREIGN KEY (tenant_id) REFERENCES expense_configuration (tenant_id),
    CHECK ((active_revision = 0 AND active_mapping_id IS NULL AND active_mapping_version IS NULL)
        OR (active_revision > 0 AND active_mapping_id IS NOT NULL AND active_mapping_version IS NOT NULL AND active_mapping_version > 0))
);
CREATE TABLE account_mapping_draft (
    tenant_id VARCHAR(64) NOT NULL,
    mapping_key VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    legal_entity_id VARCHAR(36) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    name VARCHAR(128) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    published_version BIGINT NOT NULL CHECK (published_version >= 0),
    published_draft_revision BIGINT NOT NULL CHECK (published_draft_revision >= published_version AND published_draft_revision <= revision),
    state_json TEXT NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id, mapping_key),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, legal_entity_id, currency, id),
    CHECK ((published_version = 0 AND published_draft_revision = 0) OR (published_version > 0 AND published_draft_revision > 0)),
    FOREIGN KEY (tenant_id, legal_entity_id, currency) REFERENCES account_mapping_scope (tenant_id, legal_entity_id, currency)
);
CREATE TABLE account_mapping_draft_revision (
    tenant_id VARCHAR(64) NOT NULL,
    mapping_id VARCHAR(36) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    legal_entity_id VARCHAR(36) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    name VARCHAR(128) NOT NULL,
    definition_json TEXT NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    comment VARCHAR(2000) NOT NULL,
    PRIMARY KEY (tenant_id, mapping_id, revision),
    FOREIGN KEY (tenant_id, legal_entity_id, currency, mapping_id) REFERENCES account_mapping_draft (tenant_id, legal_entity_id, currency, id)
);
CREATE TABLE account_mapping_version (
    tenant_id VARCHAR(64) NOT NULL,
    mapping_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    draft_revision BIGINT NOT NULL CHECK (draft_revision >= version),
    -- 未建立类别的往来科目配置使用 SQL NULL 表示领域零版，已存在类别继续受外键保护。
    category_revision BIGINT CHECK (category_revision > 0),
    legal_entity_id VARCHAR(36) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    name VARCHAR(128) NOT NULL,
    target_digest VARCHAR(64) NOT NULL,
    definition_digest VARCHAR(64) NOT NULL,
    state_json TEXT NOT NULL,
    published_by VARCHAR(128) NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE NOT NULL,
    comment VARCHAR(2000) NOT NULL,
    PRIMARY KEY (tenant_id, mapping_id, version),
    UNIQUE (tenant_id, mapping_id, draft_revision),
    UNIQUE (tenant_id, legal_entity_id, currency, mapping_id, version),
    FOREIGN KEY (tenant_id, mapping_id, draft_revision) REFERENCES account_mapping_draft_revision (tenant_id, mapping_id, revision),
    FOREIGN KEY (tenant_id, legal_entity_id, currency, mapping_id) REFERENCES account_mapping_draft (tenant_id, legal_entity_id, currency, id),
    FOREIGN KEY (tenant_id, category_revision) REFERENCES expense_category_revision (tenant_id, revision)
);
CREATE TABLE account_mapping_activation (
    tenant_id VARCHAR(64) NOT NULL,
    legal_entity_id VARCHAR(36) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    mapping_id VARCHAR(36) NOT NULL,
    mapping_version BIGINT NOT NULL,
    activated_by VARCHAR(128) NOT NULL,
    activated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    comment VARCHAR(2000) NOT NULL,
    PRIMARY KEY (tenant_id, legal_entity_id, currency, revision),
    FOREIGN KEY (tenant_id, legal_entity_id, currency, mapping_id, mapping_version)
        REFERENCES account_mapping_version (tenant_id, legal_entity_id, currency, mapping_id, version)
);
ALTER TABLE account_mapping_scope ADD CONSTRAINT fk_active_account_mapping
    FOREIGN KEY (tenant_id, legal_entity_id, currency, active_mapping_id, active_mapping_version)
        REFERENCES account_mapping_version (tenant_id, legal_entity_id, currency, mapping_id, version);
