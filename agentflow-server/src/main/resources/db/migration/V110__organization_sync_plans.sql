-- 保存完整核对计划及最终采用的计划，摘要只用于一致性校验，不能替代人工核对内容。
CREATE TABLE organization_sync_plan (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    batch_id VARCHAR(36) NOT NULL,
    batch_version BIGINT NOT NULL CHECK (batch_version=3),
    source_version BIGINT NOT NULL CHECK (source_version>0),
    directory_revision BIGINT NOT NULL CHECK (directory_revision>0),
    prepared_by VARCHAR(128) NOT NULL,
    prepared_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ready BOOLEAN NOT NULL,
    digest VARCHAR(64) NOT NULL,
    plan_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,id),
    UNIQUE (tenant_id,batch_id,id),
    FOREIGN KEY (tenant_id,batch_id) REFERENCES organization_sync_batch(tenant_id,id)
);
CREATE INDEX idx_organization_sync_plan_batch ON organization_sync_plan(tenant_id,batch_id,prepared_at,id);

CREATE TABLE organization_sync_application (
    tenant_id VARCHAR(64) NOT NULL,
    batch_id VARCHAR(36) NOT NULL,
    plan_id VARCHAR(36) NOT NULL,
    plan_digest VARCHAR(64) NOT NULL,
    PRIMARY KEY (tenant_id,batch_id),
    FOREIGN KEY (tenant_id,batch_id) REFERENCES organization_sync_batch(tenant_id,id),
    FOREIGN KEY (tenant_id,batch_id,plan_id) REFERENCES organization_sync_plan(tenant_id,batch_id,id)
);
