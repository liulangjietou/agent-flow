-- 部分差额引用独立调整，旧整单明细及原资源核销保持。
ALTER TABLE expense_partial_adjustment ADD CONSTRAINT uq_partial_adjustment_source UNIQUE(tenant_id,id,report_id,round_no);
CREATE TABLE finance_consumption_reduction (
    tenant_id VARCHAR(64) NOT NULL,
    adjustment_id VARCHAR(36) NOT NULL,
    resource_type VARCHAR(32) NOT NULL CHECK (resource_type IN ('INVOICE','ADVANCE','PRIOR_REQUEST')),
    resource_id VARCHAR(36) NOT NULL,
    source_line INTEGER NOT NULL CHECK (source_line>=0),
    report_id VARCHAR(36) NOT NULL,
    round_no INTEGER NOT NULL CHECK (round_no>0),
    report_line INTEGER NOT NULL CHECK (report_line>=0),
    before_version BIGINT NOT NULL CHECK (before_version>0),
    after_version BIGINT NOT NULL,
    amount DECIMAL(17,2),
    currency VARCHAR(3),
    adjusted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id,adjustment_id,resource_type,resource_id,source_line,report_line),
    CONSTRAINT ck_consumption_reduction_version CHECK (after_version=before_version+1),
    CONSTRAINT ck_consumption_reduction_amount CHECK ((resource_type='INVOICE' AND source_line=0 AND report_line>0 AND amount IS NULL AND currency IS NULL)
        OR (resource_type='ADVANCE' AND source_line=0 AND report_line=0 AND amount IS NOT NULL AND amount>0 AND currency IS NOT NULL)
        OR (resource_type='PRIOR_REQUEST' AND source_line>0 AND report_line>0 AND amount IS NOT NULL AND amount>0 AND currency IS NOT NULL)),
    CONSTRAINT fk_consumption_reduction_adjustment FOREIGN KEY (tenant_id,adjustment_id,report_id,round_no) REFERENCES expense_partial_adjustment(tenant_id,id,report_id,round_no),
    CONSTRAINT fk_consumption_reduction_before FOREIGN KEY (tenant_id,resource_type,resource_id,before_version) REFERENCES finance_resource_revision(tenant_id,resource_type,resource_id,version),
    CONSTRAINT fk_consumption_reduction_after FOREIGN KEY (tenant_id,resource_type,resource_id,after_version) REFERENCES finance_resource_revision(tenant_id,resource_type,resource_id,version)
);
CREATE INDEX idx_consumption_reduction_source ON finance_consumption_reduction(tenant_id,report_id,resource_type,resource_id);
-- 固定真正接受的两侧操作所在修订和最终资源完成修订，后续复查不能替换此证明。
CREATE TABLE expense_partial_adjustment_completion (
    tenant_id VARCHAR(64) NOT NULL,
    adjustment_id VARCHAR(36) NOT NULL,
    before_version BIGINT NOT NULL CHECK (before_version>0),
    after_version BIGINT NOT NULL,
    source_json TEXT NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id,adjustment_id),
    CONSTRAINT uq_partial_completion_version UNIQUE (tenant_id,adjustment_id,after_version),
    CONSTRAINT ck_partial_completion_version CHECK (after_version=before_version+1),
    CONSTRAINT fk_partial_completion_before FOREIGN KEY (tenant_id,adjustment_id,before_version) REFERENCES expense_partial_adjustment_revision(tenant_id,adjustment_id,version),
    CONSTRAINT fk_partial_completion_after FOREIGN KEY (tenant_id,adjustment_id,after_version) REFERENCES expense_partial_adjustment_revision(tenant_id,adjustment_id,version)
);
ALTER TABLE expense_partial_adjustment_return ADD COLUMN completed_version BIGINT;
ALTER TABLE expense_partial_adjustment_return ADD CONSTRAINT fk_partial_return_completion FOREIGN KEY (tenant_id,adjustment_id,completed_version)
    REFERENCES expense_partial_adjustment_completion(tenant_id,adjustment_id,after_version);
ALTER TABLE expense_partial_adjustment_return ADD CONSTRAINT ck_partial_return_completion CHECK ((completed_at IS NULL AND completed_version IS NULL)
    OR (completed_at IS NOT NULL AND completed_version IS NOT NULL AND completed_version>1));
