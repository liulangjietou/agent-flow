-- 只创建白名单契约目录，不补造企业事件、不修改旧流程及财务记录。
CREATE TABLE event_contract (
    tenant_id VARCHAR(64) NOT NULL,
    contract_key VARCHAR(64) NOT NULL,
    latest_version BIGINT NOT NULL CHECK (latest_version > 0),
    PRIMARY KEY (tenant_id, contract_key)
);
CREATE TABLE event_contract_version (
    tenant_id VARCHAR(64) NOT NULL,
    contract_key VARCHAR(64) NOT NULL,
    contract_version BIGINT NOT NULL CHECK (contract_version > 0),
    name VARCHAR(200) NOT NULL,
    source_key VARCHAR(64) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    envelope_version INTEGER NOT NULL CHECK (envelope_version = 1),
    published_by VARCHAR(128) NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE NOT NULL,
    publication_reason VARCHAR(2000) NOT NULL,
    availability_revision BIGINT NOT NULL CHECK (availability_revision > 0),
    enabled BOOLEAN NOT NULL,
    changed_by VARCHAR(128) NOT NULL,
    changed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    change_reason VARCHAR(2000) NOT NULL,
    PRIMARY KEY (tenant_id, contract_key, contract_version),
    CONSTRAINT fk_event_contract_version FOREIGN KEY (tenant_id, contract_key)
        REFERENCES event_contract (tenant_id, contract_key)
);
CREATE TABLE event_contract_availability_history (
    tenant_id VARCHAR(64) NOT NULL,
    contract_key VARCHAR(64) NOT NULL,
    contract_version BIGINT NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    enabled BOOLEAN NOT NULL,
    changed_by VARCHAR(128) NOT NULL,
    changed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    PRIMARY KEY (tenant_id, contract_key, contract_version, revision),
    CONSTRAINT fk_event_contract_availability_history FOREIGN KEY (tenant_id, contract_key, contract_version)
        REFERENCES event_contract_version (tenant_id, contract_key, contract_version)
);
