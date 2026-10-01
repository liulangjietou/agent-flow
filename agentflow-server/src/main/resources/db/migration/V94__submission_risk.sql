-- 旧轮次没有风险事实，保持空值；新轮次只冻结公开分级和规则标签。
ALTER TABLE approval_submission_round ADD COLUMN risk_level VARCHAR(16);
ALTER TABLE approval_submission_round ADD COLUMN risk_json TEXT;
ALTER TABLE approval_submission_round ADD CONSTRAINT ck_submission_risk_pair CHECK (
    (risk_level IS NULL AND risk_json IS NULL) OR
    (risk_level IS NOT NULL AND risk_level IN ('UNASSESSED', 'UNMATCHED', 'LOW', 'MEDIUM', 'HIGH') AND risk_json IS NOT NULL)
);
