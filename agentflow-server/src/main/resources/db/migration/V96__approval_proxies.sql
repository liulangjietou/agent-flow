-- 只新增明确授权；存量组织、候选人和历史任务不自动生成代理关系。
CREATE TABLE organization_approval_proxy (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    definition_id VARCHAR(36) NOT NULL,
    principal_id VARCHAR(36) NOT NULL,
    substitute_id VARCHAR(36) NOT NULL,
    starts_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ends_at TIMESTAMP WITH TIME ZONE NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revision BIGINT NOT NULL,
    revoked_by VARCHAR(128),
    revoked_reason VARCHAR(1000),
    revoked_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (tenant_id,id),
    FOREIGN KEY (tenant_id,definition_id) REFERENCES approval_definition(tenant_id,id),
    FOREIGN KEY (tenant_id,principal_id) REFERENCES organization_person(tenant_id,id),
    FOREIGN KEY (tenant_id,substitute_id) REFERENCES organization_person(tenant_id,id),
    CHECK (principal_id <> substitute_id),
    CHECK (starts_at < ends_at AND created_at < ends_at),
    CHECK ((revision=1 AND revoked_by IS NULL AND revoked_reason IS NULL AND revoked_at IS NULL)
        OR (revision=2 AND revoked_by IS NOT NULL AND revoked_reason IS NOT NULL AND revoked_at IS NOT NULL AND revoked_at>=created_at))
);
CREATE INDEX idx_approval_proxy_principal ON organization_approval_proxy(tenant_id,definition_id,principal_id,starts_at,ends_at);
CREATE INDEX idx_approval_proxy_substitute ON organization_approval_proxy(tenant_id,substitute_id,ends_at);
