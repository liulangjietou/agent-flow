-- 申请人主动催办记录，用于冷却期控制与催办审计追溯。
CREATE TABLE application_urge_record (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    round_no INT NOT NULL,
    urged_by VARCHAR(128) NOT NULL,
    note VARCHAR(500),
    urged_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_application_urge_lookup
    ON application_urge_record (tenant_id, application_id, round_no, urged_at DESC);
