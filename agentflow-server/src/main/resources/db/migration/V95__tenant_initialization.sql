-- 初始化由已认证管理员明确提交，旧租户不自动补写完成记录或新增业务资源。
CREATE TABLE tenant_initialization (
    tenant_id VARCHAR(64) PRIMARY KEY,
    id VARCHAR(36) NOT NULL UNIQUE,
    workspace_name VARCHAR(128) NOT NULL,
    initialized_by VARCHAR(128) NOT NULL,
    initialized_at TIMESTAMP WITH TIME ZONE NOT NULL,
    administrator_person_id VARCHAR(36) NOT NULL,
    administrator_appointment_id VARCHAR(36) NOT NULL,
    calendar_id VARCHAR(36) NOT NULL,
    calendar_revision BIGINT NOT NULL CHECK (calendar_revision > 0),
    snapshot_json TEXT NOT NULL,
    FOREIGN KEY (tenant_id) REFERENCES organization_directory(tenant_id),
    FOREIGN KEY (tenant_id,administrator_person_id) REFERENCES organization_person(tenant_id,id),
    FOREIGN KEY (tenant_id,administrator_appointment_id) REFERENCES organization_appointment(tenant_id,id),
    FOREIGN KEY (tenant_id,calendar_id) REFERENCES business_calendar(tenant_id,id),
    FOREIGN KEY (calendar_id,calendar_revision) REFERENCES business_calendar_version(calendar_id,revision)
);
