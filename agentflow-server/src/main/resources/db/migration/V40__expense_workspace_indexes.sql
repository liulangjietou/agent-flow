-- 分页依据不可变创建时间或资源标识，不能因余额或审批更新而跳过尚未读到的数据。
CREATE INDEX idx_expense_owner_created ON expense_report(tenant_id,employee_id,created_at,id);
CREATE INDEX idx_finance_owner_page ON finance_resource(tenant_id,resource_type,owner_id,id);
CREATE INDEX idx_budget_report_version ON budget_operation(tenant_id,report_id,financial_version,created_at,id);
