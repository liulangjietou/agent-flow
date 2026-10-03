-- 本地组织与认证分离；租户来自可信认证映射，初始化不创建身份或授予系统角色。
CREATE TABLE organization_directory (
    tenant_id VARCHAR(64) PRIMARY KEY,
    revision BIGINT NOT NULL CHECK (revision > 0),
    initialized_by VARCHAR(128) NOT NULL,
    initialized_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE organization_unit (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    kind VARCHAR(24) NOT NULL CHECK (kind IN ('LEGAL_ENTITY','DEPARTMENT','POSITION')),
    name VARCHAR(128) NOT NULL,
    legal_entity_id VARCHAR(36),
    parent_department_id VARCHAR(36),
    active BOOLEAN NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    PRIMARY KEY (tenant_id,id),
    FOREIGN KEY (tenant_id) REFERENCES organization_directory(tenant_id),
    FOREIGN KEY (tenant_id,legal_entity_id) REFERENCES organization_unit(tenant_id,id),
    FOREIGN KEY (tenant_id,parent_department_id) REFERENCES organization_unit(tenant_id,id)
);
CREATE INDEX idx_organization_units ON organization_unit(tenant_id,kind,id);
CREATE TABLE organization_person (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    subject VARCHAR(128) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    active BOOLEAN NOT NULL,
    approval_eligible BOOLEAN NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    PRIMARY KEY (tenant_id,id),
    UNIQUE (tenant_id,subject),
    FOREIGN KEY (tenant_id) REFERENCES organization_directory(tenant_id)
);
CREATE TABLE organization_appointment (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    person_id VARCHAR(36) NOT NULL,
    department_id VARCHAR(36) NOT NULL,
    position_id VARCHAR(36) NOT NULL,
    active BOOLEAN NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    PRIMARY KEY (tenant_id,id),
    UNIQUE (tenant_id,person_id,department_id,position_id),
    FOREIGN KEY (tenant_id,person_id) REFERENCES organization_person(tenant_id,id),
    FOREIGN KEY (tenant_id,department_id) REFERENCES organization_unit(tenant_id,id),
    FOREIGN KEY (tenant_id,position_id) REFERENCES organization_unit(tenant_id,id)
);
CREATE INDEX idx_organization_appointments_person ON organization_appointment(tenant_id,person_id,id);
CREATE INDEX idx_organization_appointments_department ON organization_appointment(tenant_id,department_id,active);
CREATE INDEX idx_organization_appointments_position ON organization_appointment(tenant_id,position_id,active);
CREATE TABLE organization_change (
    tenant_id VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 1),
    actor VARCHAR(128) NOT NULL,
    kind VARCHAR(24) NOT NULL,
    record_id VARCHAR(36) NOT NULL,
    snapshot_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id,revision),
    FOREIGN KEY (tenant_id) REFERENCES organization_directory(tenant_id)
);
