-- 封存只写一次；等待记录不携带假档案，原件保留引用参与数据库及文件配套恢复。
ALTER TABLE expense_settlement ADD CONSTRAINT uq_settlement_archive_round UNIQUE(tenant_id,report_id,round_no);
CREATE TABLE expense_archive (
    tenant_id VARCHAR(64) NOT NULL,
    report_id VARCHAR(36) NOT NULL,
    round_no INTEGER NOT NULL CHECK(round_no>0),
    settlement_version BIGINT NOT NULL CHECK(settlement_version>0),
    issue VARCHAR(64),
    checked_at TIMESTAMP WITH TIME ZONE NOT NULL,
    archived_at TIMESTAMP WITH TIME ZONE,
    manifest_json TEXT,
    manifest_sha256 VARCHAR(64),
    PRIMARY KEY(tenant_id,report_id,round_no),
    CONSTRAINT fk_archive_settlement FOREIGN KEY(tenant_id,report_id,settlement_version)
        REFERENCES expense_settlement_revision(tenant_id,report_id,version),
    CONSTRAINT fk_archive_round FOREIGN KEY(tenant_id,report_id,round_no) REFERENCES expense_settlement(tenant_id,report_id,round_no),
    CONSTRAINT ck_archive_seal CHECK(
        (archived_at IS NULL AND manifest_json IS NULL AND manifest_sha256 IS NULL AND issue IS NOT NULL)
        OR (archived_at IS NOT NULL AND manifest_json IS NOT NULL AND manifest_sha256 IS NOT NULL AND LENGTH(manifest_sha256)=64 AND issue IS NULL)),
    CONSTRAINT ck_archive_checked CHECK(archived_at IS NULL OR archived_at=checked_at)
);
CREATE TABLE expense_archive_original (
    tenant_id VARCHAR(64) NOT NULL,
    report_id VARCHAR(36) NOT NULL,
    round_no INTEGER NOT NULL,
    original_id VARCHAR(36) NOT NULL,
    PRIMARY KEY(tenant_id,report_id,round_no,original_id),
    CONSTRAINT fk_archive_original_archive FOREIGN KEY(tenant_id,report_id,round_no) REFERENCES expense_archive(tenant_id,report_id,round_no),
    CONSTRAINT fk_archive_original_file FOREIGN KEY(tenant_id,original_id) REFERENCES invoice_original(tenant_id,id)
);
