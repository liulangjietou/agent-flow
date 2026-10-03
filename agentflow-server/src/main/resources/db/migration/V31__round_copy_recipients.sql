-- 收件事实在引擎节点激活的原事务中冻结；首节点执行早于轮次插入，故不建立即时轮次外键。
-- 读取必须再匹配真实轮次及实例，事务失败时收件事实、消息、引擎和申请一起回滚。
CREATE TABLE approval_copy_recipient (
    tenant_id VARCHAR(64) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    round_no INT NOT NULL CHECK (round_no > 0),
    process_instance_id VARCHAR(128) NOT NULL,
    node_id VARCHAR(128) NOT NULL,
    node_name VARCHAR(200) NOT NULL,
    recipient_id VARCHAR(128) NOT NULL,
    recipient_rule VARCHAR(160) NOT NULL,
    directory_revision BIGINT NOT NULL CHECK (directory_revision >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id, application_id, round_no, node_id, recipient_id),
    FOREIGN KEY (tenant_id, application_id) REFERENCES approval_application(tenant_id, id)
);
CREATE INDEX idx_copy_recipient ON approval_copy_recipient(tenant_id, recipient_id, application_id, round_no);
