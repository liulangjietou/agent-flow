-- 可信目录只由部署配置安装；相同租户、键和版本的契约与目标不得改写。
CREATE TABLE service_task_catalog_lock (id INTEGER PRIMARY KEY CHECK (id=1));
INSERT INTO service_task_catalog_lock(id) VALUES(1);
CREATE TABLE service_task_contract (
    tenant_id VARCHAR(64) NOT NULL,
    operation_key VARCHAR(64) NOT NULL,
    operation_version BIGINT NOT NULL CHECK (operation_version>0),
    contract_digest VARCHAR(64) NOT NULL,
    target_digest VARCHAR(64) NOT NULL,
    contract_json TEXT NOT NULL,
    installed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id,operation_key,operation_version),
    CONSTRAINT uq_service_task_contract_binding UNIQUE (tenant_id,operation_key,operation_version,contract_digest,target_digest)
);

-- 节点激活与提交共用事务，首次激活时轮次尚未插入，因此保留申请外键并在领取时核验真实轮次。
-- execution 会跨顺序节点复用，唯一键必须包括节点；设计器禁止循环。
CREATE TABLE service_task_operation (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    round_no INTEGER NOT NULL CHECK (round_no>0),
    process_instance_id VARCHAR(255) NOT NULL,
    execution_id VARCHAR(255) NOT NULL,
    node_id VARCHAR(128) NOT NULL,
    operation_key VARCHAR(64) NOT NULL,
    operation_version BIGINT NOT NULL,
    contract_digest VARCHAR(64) NOT NULL,
    target_digest VARCHAR(64) NOT NULL,
    command_digest VARCHAR(64) NOT NULL,
    input_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','EXECUTING','UNKNOWN','QUERYING','APPLIED','REJECTED','CANCELLED')),
    attempts INTEGER NOT NULL CHECK (attempts>=0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    lease_until TIMESTAMP WITH TIME ZONE,
    progress VARCHAR(16) NOT NULL CHECK (progress IN ('PENDING','ADVANCED','STALE')),
    progressed_at TIMESTAMP WITH TIME ZONE,
    poll_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_service_task_wait UNIQUE (tenant_id,process_instance_id,execution_id,node_id),
    CONSTRAINT fk_service_task_application FOREIGN KEY (tenant_id,application_id) REFERENCES approval_application(tenant_id,id),
    CONSTRAINT fk_service_task_contract FOREIGN KEY (tenant_id,operation_key,operation_version,contract_digest,target_digest)
        REFERENCES service_task_contract(tenant_id,operation_key,operation_version,contract_digest,target_digest),
    CONSTRAINT ck_service_task_times CHECK (updated_at>=created_at),
    CONSTRAINT ck_service_task_progress CHECK ((progress='PENDING' AND progressed_at IS NULL)
        OR (progress<>'PENDING' AND progressed_at IS NOT NULL AND poll_at IS NULL)),
    CONSTRAINT ck_service_task_advanced CHECK (progress<>'ADVANCED' OR status='APPLIED')
);
CREATE INDEX idx_service_task_due ON service_task_operation(poll_at,tenant_id,id);
CREATE INDEX idx_service_task_round ON service_task_operation(tenant_id,application_id,round_no,created_at,id);
CREATE TABLE service_task_operation_revision (
    tenant_id VARCHAR(64) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL,
    state_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,operation_id,version),
    CONSTRAINT fk_service_task_revision FOREIGN KEY (tenant_id,operation_id) REFERENCES service_task_operation(tenant_id,id)
);
