-- 父提交启动原生流程时，父轮次尚未插入；父申请外键保留，子轮次必须先保存。
ALTER TABLE approval_definition ADD CONSTRAINT uk_subprocess_definition_identity UNIQUE (tenant_id,id,process_key,version);
ALTER TABLE approval_submission_round ADD CONSTRAINT uk_subprocess_round_identity
    UNIQUE (tenant_id,application_id,round_no,process_instance_id,definition_version);

CREATE TABLE approval_subprocess_call (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    parent_application_id VARCHAR(36) NOT NULL,
    parent_round_no INT NOT NULL CHECK (parent_round_no > 0),
    parent_instance_id VARCHAR(128) NOT NULL,
    parent_runtime_definition_id VARCHAR(128) NOT NULL,
    node_id VARCHAR(128) NOT NULL,
    node_name VARCHAR(256) NOT NULL,
    activation_id VARCHAR(128) NOT NULL,
    child_application_id VARCHAR(36) NOT NULL,
    child_round_no INT NOT NULL CHECK (child_round_no = 1),
    child_instance_id VARCHAR(128) NOT NULL,
    child_definition_id VARCHAR(36) NOT NULL,
    child_process_key VARCHAR(128) NOT NULL,
    child_definition_version BIGINT NOT NULL,
    child_runtime_definition_id VARCHAR(128) NOT NULL,
    policy_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_subprocess_distinct CHECK (parent_application_id<>child_application_id AND parent_instance_id<>child_instance_id),
    CONSTRAINT uk_subprocess_child UNIQUE (tenant_id,child_application_id),
    CONSTRAINT uk_subprocess_activation UNIQUE (tenant_id,parent_instance_id,activation_id),
    CONSTRAINT uk_subprocess_participants UNIQUE (tenant_id,id,parent_application_id,child_application_id),
    CONSTRAINT fk_subprocess_parent FOREIGN KEY (tenant_id,parent_application_id) REFERENCES approval_application(tenant_id,id),
    CONSTRAINT fk_subprocess_child_round FOREIGN KEY (tenant_id,child_application_id,child_round_no,child_instance_id,child_definition_version)
        REFERENCES approval_submission_round(tenant_id,application_id,round_no,process_instance_id,definition_version),
    CONSTRAINT fk_subprocess_definition FOREIGN KEY (tenant_id,child_definition_id,child_process_key,child_definition_version)
        REFERENCES approval_definition(tenant_id,id,process_key,version)
);
CREATE INDEX idx_subprocess_parent_round ON approval_subprocess_call(tenant_id,parent_application_id,parent_round_no);

-- 每个子申请持有独立的附件授权身份，物理内容仍是同租户、相同指纹的已就绪原件。
ALTER TABLE approval_attachment ADD COLUMN content_id VARCHAR(36);
ALTER TABLE approval_attachment ADD CONSTRAINT uk_attachment_content_fingerprint UNIQUE (tenant_id,id,byte_size,sha256,status);
ALTER TABLE approval_attachment ADD CONSTRAINT ck_attachment_content_reference CHECK (content_id IS NULL OR (content_id<>id AND status='READY'));
ALTER TABLE approval_attachment ADD CONSTRAINT fk_attachment_content_reference FOREIGN KEY (tenant_id,content_id,byte_size,sha256,status)
    REFERENCES approval_attachment(tenant_id,id,byte_size,sha256,status);

CREATE TABLE approval_subprocess_attachment (
    tenant_id VARCHAR(64) NOT NULL,
    call_id VARCHAR(36) NOT NULL,
    parent_application_id VARCHAR(36) NOT NULL,
    child_application_id VARCHAR(36) NOT NULL,
    source_field_path VARCHAR(129) NOT NULL,
    source_attachment_id VARCHAR(36) NOT NULL,
    target_field_path VARCHAR(129) NOT NULL,
    target_attachment_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (tenant_id,call_id,target_field_path,target_attachment_id),
    CONSTRAINT uk_subprocess_attachment_source UNIQUE (tenant_id,call_id,source_field_path,source_attachment_id,target_field_path),
    CONSTRAINT fk_subprocess_attachment_call FOREIGN KEY (tenant_id,call_id,parent_application_id,child_application_id)
        REFERENCES approval_subprocess_call(tenant_id,id,parent_application_id,child_application_id),
    CONSTRAINT fk_subprocess_attachment_source FOREIGN KEY (tenant_id,parent_application_id,source_field_path,source_attachment_id)
        REFERENCES approval_attachment(tenant_id,application_id,field_path,id),
    CONSTRAINT fk_subprocess_attachment_target FOREIGN KEY (tenant_id,child_application_id,target_field_path,target_attachment_id)
        REFERENCES approval_attachment(tenant_id,application_id,field_path,id)
);

-- 备份只枚举实际物理原件；子申请授权和来源关系随数据库备份保留，不产生不存在的文件名。
CREATE OR REPLACE VIEW stored_document_inventory AS
    SELECT id,byte_size,sha256,status FROM approval_attachment WHERE content_id IS NULL
    UNION ALL
    SELECT id,byte_size,sha256,status FROM invoice_original;
