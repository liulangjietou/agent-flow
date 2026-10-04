-- 原始回执、服务方签名和授权公钥绑定跟随接受该事实的操作修订一起保存。
CREATE TABLE signature_receipt_evidence (
    tenant_id VARCHAR(64) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    evidence_digest VARCHAR(64) NOT NULL,
    receipt_digest VARCHAR(64) NOT NULL,
    provider_revision BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    accepted_version BIGINT NOT NULL CHECK (accepted_version>1),
    verified_at TIMESTAMP WITH TIME ZONE NOT NULL,
    evidence_json TEXT NOT NULL CHECK (CHAR_LENGTH(evidence_json)<=163840),
    PRIMARY KEY (tenant_id,operation_id,evidence_digest),
    CONSTRAINT fk_signature_evidence_revision FOREIGN KEY (tenant_id,operation_id,accepted_version)
        REFERENCES signature_operation_revision(tenant_id,operation_id,version),
    CONSTRAINT ck_signature_evidence_status CHECK (
        (status='NOT_FOUND' AND provider_revision=0)
        OR (status IN ('PENDING','SIGNED','DECLINED','CANCELLED') AND provider_revision>0))
);
CREATE INDEX idx_signature_evidence_receipt ON signature_receipt_evidence(tenant_id,operation_id,receipt_digest);
