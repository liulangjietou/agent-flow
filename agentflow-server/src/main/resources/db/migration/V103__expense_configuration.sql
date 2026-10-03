-- 费用配置只新增目录与版本历史；不回写已有预检、财务轮次或外部制度事实。
CREATE TABLE expense_configuration (
    tenant_id VARCHAR(64) PRIMARY KEY,
    category_revision BIGINT NOT NULL DEFAULT 0 CHECK (category_revision >= 0),
    active_revision BIGINT NOT NULL DEFAULT 0 CHECK (active_revision >= 0),
    active_policy_id VARCHAR(36),
    active_policy_version BIGINT,
    CHECK ((active_revision = 0 AND active_policy_id IS NULL AND active_policy_version IS NULL)
        OR (active_revision > 0 AND active_policy_id IS NOT NULL AND active_policy_version IS NOT NULL AND active_policy_version > 0))
);
CREATE TABLE expense_category_revision (
    tenant_id VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    category_count INTEGER NOT NULL CHECK (category_count >= 0),
    state_json TEXT NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    comment VARCHAR(2000) NOT NULL,
    PRIMARY KEY (tenant_id, revision),
    FOREIGN KEY (tenant_id) REFERENCES expense_configuration (tenant_id)
);
CREATE TABLE expense_policy_draft (
    tenant_id VARCHAR(64) NOT NULL,
    policy_key VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    name VARCHAR(128) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    published_version BIGINT NOT NULL CHECK (published_version >= 0),
    published_draft_revision BIGINT NOT NULL CHECK (published_draft_revision >= published_version AND published_draft_revision <= revision),
    state_json TEXT NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id, policy_key),
    UNIQUE (tenant_id, id),
    CHECK ((published_version = 0 AND published_draft_revision = 0) OR (published_version > 0 AND published_draft_revision > 0)),
    FOREIGN KEY (tenant_id) REFERENCES expense_configuration (tenant_id)
);
CREATE TABLE expense_policy_draft_revision (
    tenant_id VARCHAR(64) NOT NULL,
    policy_id VARCHAR(36) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    name VARCHAR(128) NOT NULL,
    definition_json TEXT NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    comment VARCHAR(2000) NOT NULL,
    PRIMARY KEY (tenant_id, policy_id, revision),
    FOREIGN KEY (tenant_id, policy_id) REFERENCES expense_policy_draft (tenant_id, id)
);
CREATE TABLE expense_policy_version (
    tenant_id VARCHAR(64) NOT NULL,
    policy_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    draft_revision BIGINT NOT NULL CHECK (draft_revision >= version),
    category_revision BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    state_json TEXT NOT NULL,
    published_by VARCHAR(128) NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE NOT NULL,
    comment VARCHAR(2000) NOT NULL,
    PRIMARY KEY (tenant_id, policy_id, version),
    UNIQUE (tenant_id, policy_id, draft_revision),
    FOREIGN KEY (tenant_id, policy_id, draft_revision) REFERENCES expense_policy_draft_revision (tenant_id, policy_id, revision),
    FOREIGN KEY (tenant_id, category_revision) REFERENCES expense_category_revision (tenant_id, revision)
);
CREATE TABLE expense_policy_activation (
    tenant_id VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    policy_id VARCHAR(36) NOT NULL,
    policy_version BIGINT NOT NULL,
    activated_by VARCHAR(128) NOT NULL,
    activated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    comment VARCHAR(2000) NOT NULL,
    PRIMARY KEY (tenant_id, revision),
    FOREIGN KEY (tenant_id, policy_id, policy_version) REFERENCES expense_policy_version (tenant_id, policy_id, version)
);
ALTER TABLE expense_configuration ADD CONSTRAINT fk_active_expense_policy
    FOREIGN KEY (tenant_id, active_policy_id, active_policy_version) REFERENCES expense_policy_version (tenant_id, policy_id, version);
