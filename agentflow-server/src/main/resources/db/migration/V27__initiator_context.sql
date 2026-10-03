-- 每个提交轮次保存当时的任职上下文；旧轮次保持 NULL，不从现有组织推断历史。
ALTER TABLE approval_submission_round ADD COLUMN initiator_context_json TEXT;
