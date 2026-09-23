-- 日历独立配置，当前指针与不可变修订共同保存，不修改任何既有审批数据。
CREATE TABLE business_calendar (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    calendar_key VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    zone_id VARCHAR(128) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    updated_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_calendar_key UNIQUE (tenant_id, calendar_key),
    CONSTRAINT uq_calendar_tenant_id UNIQUE (tenant_id, id)
);
CREATE TABLE business_calendar_version (
    tenant_id VARCHAR(64) NOT NULL,
    calendar_id VARCHAR(36) NOT NULL,
    calendar_key VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    zone_id VARCHAR(128) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    rules_json TEXT NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (calendar_id, revision),
    CONSTRAINT fk_calendar_version_tenant FOREIGN KEY (tenant_id, calendar_id) REFERENCES business_calendar (tenant_id, id)
);
CREATE INDEX idx_calendar_versions ON business_calendar_version (tenant_id, calendar_id, revision DESC);
