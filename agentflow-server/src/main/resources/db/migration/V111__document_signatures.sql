-- 签署只引用真实批准轮次；请求与目标固定，未结束的原操作阻止同一轮次再次外发。
CREATE TABLE signature_operation (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    round_no INTEGER NOT NULL CHECK (round_no>0),
    request_digest VARCHAR(64) NOT NULL,
    target_digest VARCHAR(64) NOT NULL,
    input_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    status VARCHAR(24) NOT NULL CHECK (status IN ('QUEUED','SENDING','UNKNOWN','QUERYING','PENDING','COLLECTING','FETCHING_FILES','SIGNED','DECLINED','CANCELLED','EXPIRED')),
    attempts INTEGER NOT NULL CHECK (attempts>=0),
    authorized_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    lease_until TIMESTAMP WITH TIME ZONE,
    poll_at TIMESTAMP WITH TIME ZONE,
    active_guard INTEGER,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_signature_round_identity UNIQUE (tenant_id,id,application_id,round_no),
    CONSTRAINT uq_signature_active_round UNIQUE (tenant_id,application_id,round_no,active_guard),
    CONSTRAINT fk_signature_round FOREIGN KEY (tenant_id,application_id,round_no)
        REFERENCES approval_submission_round(tenant_id,application_id,round_no),
    CONSTRAINT ck_signature_active CHECK (
        (status IN ('SIGNED','DECLINED','CANCELLED','EXPIRED') AND active_guard IS NULL AND poll_at IS NULL)
        OR (status NOT IN ('SIGNED','DECLINED','CANCELLED','EXPIRED') AND active_guard IS NOT NULL AND active_guard=1 AND poll_at IS NOT NULL)),
    CONSTRAINT ck_signature_times CHECK (updated_at>=authorized_at AND version>attempts),
    CONSTRAINT ck_signature_schedule CHECK (
        -- 索引截到微秒可能相等，精确的正租约由原始状态 JSON 的领域不变量核对。
        (status IN ('SENDING','QUERYING','FETCHING_FILES') AND lease_until IS NOT NULL AND lease_until>=updated_at AND next_attempt_at IS NULL)
        OR (status IN ('QUEUED','UNKNOWN','PENDING','COLLECTING') AND lease_until IS NULL AND next_attempt_at IS NOT NULL AND next_attempt_at>=updated_at)
        OR (status IN ('SIGNED','DECLINED','CANCELLED','EXPIRED') AND lease_until IS NULL AND next_attempt_at IS NULL))
);
CREATE INDEX idx_signature_due ON signature_operation(poll_at,tenant_id,id);
CREATE INDEX idx_signature_round ON signature_operation(tenant_id,application_id,round_no,authorized_at,id);

-- 两个附件外键分别保护字段授权引用和真实物理原件，子流程复制引用不能改换内容。
CREATE TABLE signature_source_document (
    tenant_id VARCHAR(64) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    round_no INTEGER NOT NULL,
    attachment_id VARCHAR(36) NOT NULL,
    original_content_id VARCHAR(36) NOT NULL,
    field_path VARCHAR(129) NOT NULL,
    filename VARCHAR(255) NOT NULL,
    byte_size BIGINT NOT NULL CHECK (byte_size>0),
    sha256 VARCHAR(64) NOT NULL,
    original_status VARCHAR(16) NOT NULL CHECK (original_status='READY'),
    PRIMARY KEY (tenant_id,operation_id,attachment_id),
    CONSTRAINT fk_signature_document_operation FOREIGN KEY (tenant_id,operation_id,application_id,round_no)
        REFERENCES signature_operation(tenant_id,id,application_id,round_no),
    CONSTRAINT fk_signature_document_round FOREIGN KEY (tenant_id,application_id,round_no,field_path,attachment_id)
        REFERENCES approval_attachment_round(tenant_id,application_id,round_no,field_path,attachment_id),
    CONSTRAINT fk_signature_document_reference FOREIGN KEY (tenant_id,attachment_id,byte_size,sha256,original_status)
        REFERENCES approval_attachment(tenant_id,id,byte_size,sha256,status),
    CONSTRAINT fk_signature_document_original FOREIGN KEY (tenant_id,original_content_id,byte_size,sha256,original_status)
        REFERENCES approval_attachment(tenant_id,id,byte_size,sha256,status)
);

CREATE TABLE signature_operation_revision (
    tenant_id VARCHAR(64) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version>0),
    state_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id,operation_id,version),
    CONSTRAINT fk_signature_revision FOREIGN KEY (tenant_id,operation_id) REFERENCES signature_operation(tenant_id,id)
);

-- 结果标识在文件传输前持久分配；已保存文件逐份登记，重启继续收集尚未确认的文件。
CREATE TABLE signature_result_file (
    tenant_id VARCHAR(64) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    document_id VARCHAR(36) NOT NULL,
    content_id VARCHAR(36) NOT NULL UNIQUE,
    byte_size BIGINT NOT NULL CHECK (byte_size>0),
    sha256 VARCHAR(64) NOT NULL,
    receipt_digest VARCHAR(64) NOT NULL,
    created_version BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('RESERVED','READY')),
    saved_at TIMESTAMP WITH TIME ZONE,
    saved_version BIGINT,
    PRIMARY KEY (tenant_id,operation_id,document_id),
    CONSTRAINT fk_signature_result_source FOREIGN KEY (tenant_id,operation_id,document_id)
        REFERENCES signature_source_document(tenant_id,operation_id,attachment_id),
    CONSTRAINT fk_signature_result_revision FOREIGN KEY (tenant_id,operation_id,created_version)
        REFERENCES signature_operation_revision(tenant_id,operation_id,version),
    CONSTRAINT fk_signature_result_saved_revision FOREIGN KEY (tenant_id,operation_id,saved_version)
        REFERENCES signature_operation_revision(tenant_id,operation_id,version),
    CONSTRAINT ck_signature_result_saved CHECK ((status='RESERVED' AND saved_at IS NULL AND saved_version IS NULL)
        OR (status='READY' AND saved_at IS NOT NULL AND saved_version IS NOT NULL AND saved_version>created_version))
);

-- 沿用备份工具的既有三态契约；已验证的签署结果和原件一起备份，未保存的预留不冒充文件。
CREATE OR REPLACE VIEW stored_document_inventory AS
    SELECT id,byte_size,sha256,status FROM approval_attachment WHERE content_id IS NULL
    UNION ALL
    SELECT id,byte_size,sha256,status FROM invoice_original
    UNION ALL
    SELECT content_id AS id,byte_size,sha256,CAST(CASE WHEN status='READY' THEN 'READY' ELSE 'UPLOADING' END AS VARCHAR(16)) AS status FROM signature_result_file;
