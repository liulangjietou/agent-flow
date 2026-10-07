-- 原生任务由 Flowable 管理，只在应用表保存不可变创建来源，不改写引擎表或回填历史来源。
CREATE TABLE workflow_execution_origin (
    tenant_id VARCHAR(128) NOT NULL,
    object_kind VARCHAR(16) NOT NULL,
    object_id VARCHAR(64) NOT NULL,
    trace_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (tenant_id, object_kind, object_id),
    CONSTRAINT ck_workflow_origin_kind CHECK (object_kind IN ('TASK', 'TIMER'))
);

ALTER TABLE organization_approval_proxy ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE employee_advance_order ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE expense_budget_retention ADD COLUMN trace_id VARCHAR(36);
