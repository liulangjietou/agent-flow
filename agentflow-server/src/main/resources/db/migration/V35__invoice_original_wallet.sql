-- 票夹独立于审批申请，配额统计包含失败登记与已发布原件，不自动删除财务证据。
ALTER TABLE finance_resource ADD CONSTRAINT uq_finance_resource_origin UNIQUE (tenant_id,resource_type,id,owner_id,source_reference);

CREATE TABLE invoice_wallet (
    tenant_id VARCHAR(64) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    used_bytes BIGINT NOT NULL DEFAULT 0 CHECK (used_bytes >= 0),
    upload_count INTEGER NOT NULL DEFAULT 0 CHECK (upload_count >= 0),
    PRIMARY KEY (tenant_id,owner_id)
);

CREATE TABLE invoice_original (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(36) NOT NULL,
    invoice_id VARCHAR(36) NOT NULL,
    resource_type VARCHAR(32) NOT NULL DEFAULT 'INVOICE' CHECK (resource_type='INVOICE'),
    owner_id VARCHAR(128) NOT NULL,
    filename VARCHAR(255) NOT NULL,
    byte_size BIGINT NOT NULL CHECK (byte_size > 0 AND byte_size <= 20971520),
    sha256 VARCHAR(64) NOT NULL,
    format VARCHAR(16) NOT NULL CHECK (format IN ('PDF','OFD','PNG','JPEG')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('UPLOADING','READY','FAILED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id,id),
    CONSTRAINT uq_invoice_original_file UNIQUE (id),
    CONSTRAINT uq_invoice_original_invoice UNIQUE (tenant_id,invoice_id),
    CONSTRAINT fk_invoice_original_resource FOREIGN KEY (tenant_id,resource_type,invoice_id,owner_id,id)
        REFERENCES finance_resource(tenant_id,resource_type,id,owner_id,source_reference),
    CONSTRAINT fk_invoice_original_wallet FOREIGN KEY (tenant_id,owner_id) REFERENCES invoice_wallet(tenant_id,owner_id)
);
CREATE INDEX idx_invoice_original_owner ON invoice_original(tenant_id,owner_id,invoice_id);

-- 配套备份统一枚举已登记字节，文件名及财务正文不进入备份清单。
CREATE VIEW stored_document_inventory AS
    SELECT id,byte_size,sha256,status FROM approval_attachment
    UNION ALL
    SELECT id,byte_size,sha256,status FROM invoice_original;
