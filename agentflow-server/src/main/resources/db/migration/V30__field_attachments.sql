-- 附件内容存入单机持久目录，数据库仅保存不可变身份与指纹；旧申请不补造文件。
ALTER TABLE approval_application ADD CONSTRAINT uk_attachment_application_tenant UNIQUE (tenant_id, id);

CREATE TABLE approval_attachment (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    field_path VARCHAR(129) NOT NULL,
    filename VARCHAR(255) NOT NULL,
    byte_size BIGINT NOT NULL CHECK (byte_size >= 0),
    sha256 VARCHAR(64) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('UPLOADING', 'READY', 'FAILED')),
    CONSTRAINT uk_attachment_field UNIQUE (tenant_id, application_id, field_path, id),
    CONSTRAINT fk_attachment_application FOREIGN KEY (tenant_id, application_id) REFERENCES approval_application(tenant_id, id)
);
CREATE INDEX idx_attachment_application ON approval_attachment(tenant_id, application_id);

CREATE TABLE approval_attachment_round (
    tenant_id VARCHAR(64) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    round_no INT NOT NULL,
    field_path VARCHAR(129) NOT NULL,
    attachment_id VARCHAR(36) NOT NULL,
    CONSTRAINT pk_attachment_round PRIMARY KEY (tenant_id, application_id, round_no, field_path, attachment_id),
    CONSTRAINT fk_attachment_round FOREIGN KEY (tenant_id, application_id, round_no)
        REFERENCES approval_submission_round(tenant_id, application_id, round_no),
    CONSTRAINT fk_attachment_round_content FOREIGN KEY (tenant_id, application_id, field_path, attachment_id)
        REFERENCES approval_attachment(tenant_id, application_id, field_path, id)
);
