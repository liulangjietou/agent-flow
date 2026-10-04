-- 组织同步只管理本地组织事实，来源游标和映射不创建认证身份或系统权限。
CREATE TABLE organization_sync_source (
    tenant_id VARCHAR(64) PRIMARY KEY,
    source_key VARCHAR(64) NOT NULL,
    applied_revision BIGINT NOT NULL CHECK (applied_revision >= 0),
    version BIGINT NOT NULL CHECK (version > 0),
    last_applied_batch_id VARCHAR(36),
    registered_by VARCHAR(128) NOT NULL,
    registered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (tenant_id,source_key),
    FOREIGN KEY (tenant_id) REFERENCES organization_directory(tenant_id),
    CHECK ((version=1 AND applied_revision=0 AND last_applied_batch_id IS NULL)
        OR (version>1 AND last_applied_batch_id IS NOT NULL))
);

CREATE TABLE organization_sync_batch (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    source_key VARCHAR(64) NOT NULL,
    after_revision BIGINT NOT NULL CHECK (after_revision >= 0),
    received_revision BIGINT,
    requested_by VARCHAR(128) NOT NULL,
    retry_of VARCHAR(36),
    status VARCHAR(24) NOT NULL,
    version BIGINT NOT NULL,
    pending_tenant_id VARCHAR(64),
    context_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (tenant_id,id),
    UNIQUE (pending_tenant_id),
    FOREIGN KEY (tenant_id,source_key) REFERENCES organization_sync_source(tenant_id,source_key),
    FOREIGN KEY (tenant_id,retry_of) REFERENCES organization_sync_batch(tenant_id,id),
    CHECK (retry_of IS NULL OR retry_of<>id),
    CHECK ((status='QUEUED' AND version=1) OR (status='FETCHING' AND version=2)
        OR (status IN ('RECEIVED','FAILED') AND version=3) OR (status='APPLIED' AND version=4)
        OR (status='CANCELLED' AND version IN (2,3,4))),
    CHECK ((status IN ('QUEUED','FETCHING','RECEIVED') AND pending_tenant_id IS NOT NULL AND pending_tenant_id=tenant_id)
        OR (status IN ('FAILED','APPLIED','CANCELLED') AND pending_tenant_id IS NULL)),
    CHECK ((status='FETCHING' AND lease_until IS NOT NULL) OR (status<>'FETCHING' AND lease_until IS NULL)),
    CHECK ((status IN ('RECEIVED','APPLIED') OR (status='CANCELLED' AND version=4))
            AND received_revision IS NOT NULL AND received_revision>=after_revision
        OR (status IN ('QUEUED','FETCHING','FAILED') OR (status='CANCELLED' AND version IN (2,3))) AND received_revision IS NULL)
);
CREATE INDEX idx_organization_sync_due ON organization_sync_batch(status,lease_until,created_at,id);
CREATE INDEX idx_organization_sync_history ON organization_sync_batch(tenant_id,created_at,id);
ALTER TABLE organization_sync_source ADD CONSTRAINT fk_organization_sync_last_batch
    FOREIGN KEY (tenant_id,last_applied_batch_id) REFERENCES organization_sync_batch(tenant_id,id);

CREATE TABLE organization_sync_transition (
    tenant_id VARCHAR(64) NOT NULL,
    batch_id VARCHAR(36) NOT NULL,
    batch_version BIGINT NOT NULL CHECK (batch_version > 0),
    status VARCHAR(24) NOT NULL,
    state_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,batch_id,batch_version),
    FOREIGN KEY (tenant_id,batch_id) REFERENCES organization_sync_batch(tenant_id,id)
);

CREATE TABLE organization_sync_binding (
    tenant_id VARCHAR(64) NOT NULL,
    source_key VARCHAR(64) NOT NULL,
    kind VARCHAR(24) NOT NULL CHECK (kind IN ('LEGAL_ENTITY','DEPARTMENT','POSITION','PERSON','APPOINTMENT')),
    external_id VARCHAR(128) NOT NULL,
    local_id VARCHAR(36) NOT NULL,
    unit_id VARCHAR(36),
    person_id VARCHAR(36),
    appointment_id VARCHAR(36),
    local_revision BIGINT NOT NULL CHECK (local_revision > 0),
    source_revision BIGINT NOT NULL CHECK (source_revision > 0),
    version BIGINT NOT NULL CHECK (version > 0),
    applied_batch_id VARCHAR(36) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id,kind,external_id),
    UNIQUE (tenant_id,kind,local_id),
    FOREIGN KEY (tenant_id,source_key) REFERENCES organization_sync_source(tenant_id,source_key),
    FOREIGN KEY (tenant_id,unit_id) REFERENCES organization_unit(tenant_id,id),
    FOREIGN KEY (tenant_id,person_id) REFERENCES organization_person(tenant_id,id),
    FOREIGN KEY (tenant_id,appointment_id) REFERENCES organization_appointment(tenant_id,id),
    FOREIGN KEY (tenant_id,applied_batch_id) REFERENCES organization_sync_batch(tenant_id,id),
    CHECK ((kind IN ('LEGAL_ENTITY','DEPARTMENT','POSITION') AND unit_id IS NOT NULL AND unit_id=local_id AND person_id IS NULL AND appointment_id IS NULL)
        OR (kind='PERSON' AND person_id IS NOT NULL AND person_id=local_id AND unit_id IS NULL AND appointment_id IS NULL)
        OR (kind='APPOINTMENT' AND appointment_id IS NOT NULL AND appointment_id=local_id AND unit_id IS NULL AND person_id IS NULL))
);

CREATE TABLE organization_sync_binding_change (
    tenant_id VARCHAR(64) NOT NULL,
    kind VARCHAR(24) NOT NULL,
    external_id VARCHAR(128) NOT NULL,
    binding_version BIGINT NOT NULL CHECK (binding_version > 0),
    batch_id VARCHAR(36) NOT NULL,
    snapshot_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id,kind,external_id,binding_version),
    FOREIGN KEY (tenant_id,kind,external_id) REFERENCES organization_sync_binding(tenant_id,kind,external_id),
    FOREIGN KEY (tenant_id,batch_id) REFERENCES organization_sync_batch(tenant_id,id)
);
