-- 发票、已放款借款及已批准事前申请共享存储协议，各自由自己的领域仓储解释。
CREATE TABLE finance_resource (
    tenant_id VARCHAR(64) NOT NULL,
    resource_type VARCHAR(32) NOT NULL CHECK (resource_type IN ('INVOICE','ADVANCE','PRIOR_REQUEST')),
    id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    source_reference VARCHAR(128) NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    context_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,resource_type,id),
    CONSTRAINT uq_finance_resource_source UNIQUE (tenant_id,resource_type,source_reference)
);
CREATE INDEX idx_finance_resource_owner ON finance_resource(tenant_id,resource_type,owner_id,updated_at,id);

CREATE TABLE finance_resource_revision (
    tenant_id VARCHAR(64) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    state_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,resource_type,resource_id,version),
    CONSTRAINT fk_finance_resource_revision FOREIGN KEY (tenant_id,resource_type,resource_id)
        REFERENCES finance_resource(tenant_id,resource_type,id)
);

-- 主键与 PostgreSQL 部分唯一索引具有相同的有效占用互斥效果，同时兼容 H2。
-- 此表只保存 OCCUPIED/CONSUMED，释放移除有效键；完整历史在版本表中保留。
CREATE TABLE invoice_active_claim (
    tenant_id VARCHAR(64) NOT NULL,
    invoice_key VARCHAR(96) NOT NULL,
    invoice_id VARCHAR(36) NOT NULL,
    resource_type VARCHAR(32) NOT NULL DEFAULT 'INVOICE' CHECK (resource_type='INVOICE'),
    report_id VARCHAR(36) NOT NULL,
    round_no INTEGER NOT NULL CHECK (round_no > 0),
    line_no INTEGER NOT NULL CHECK (line_no > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('OCCUPIED','CONSUMED')),
    PRIMARY KEY (tenant_id,invoice_key),
    CONSTRAINT uq_invoice_active_file UNIQUE (tenant_id,invoice_id),
    CONSTRAINT fk_invoice_claim_resource FOREIGN KEY (tenant_id,resource_type,invoice_id)
        REFERENCES finance_resource(tenant_id,resource_type,id),
    CONSTRAINT fk_invoice_claim_report FOREIGN KEY (tenant_id,report_id) REFERENCES expense_report(tenant_id,id)
);
CREATE INDEX idx_invoice_claim_report ON invoice_active_claim(tenant_id,report_id,round_no);

CREATE TABLE finance_amount_use (
    tenant_id VARCHAR(64) NOT NULL,
    resource_type VARCHAR(32) NOT NULL CHECK (resource_type IN ('ADVANCE','PRIOR_REQUEST')),
    resource_id VARCHAR(36) NOT NULL,
    source_line INTEGER NOT NULL CHECK (source_line >= 0),
    report_id VARCHAR(36) NOT NULL,
    round_no INTEGER NOT NULL CHECK (round_no > 0),
    report_line INTEGER NOT NULL CHECK (report_line >= 0),
    amount DECIMAL(17,2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('RESERVED','CONSUMED')),
    PRIMARY KEY (tenant_id,resource_type,resource_id,source_line,report_id,round_no,report_line),
    CONSTRAINT ck_finance_use_line CHECK ((resource_type='ADVANCE' AND source_line=0 AND report_line=0)
        OR (resource_type='PRIOR_REQUEST' AND source_line>0 AND report_line>0)),
    CONSTRAINT fk_amount_use_resource FOREIGN KEY (tenant_id,resource_type,resource_id) REFERENCES finance_resource(tenant_id,resource_type,id),
    CONSTRAINT fk_amount_use_report FOREIGN KEY (tenant_id,report_id) REFERENCES expense_report(tenant_id,id)
);
CREATE INDEX idx_finance_amount_report ON finance_amount_use(tenant_id,report_id,round_no,status);
