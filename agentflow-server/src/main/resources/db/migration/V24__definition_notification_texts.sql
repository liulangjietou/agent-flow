-- 旧流程、申请及消息没有自定义文案事实，保持 NULL，不从当前模板回填历史。
ALTER TABLE approval_definition ADD COLUMN notification_texts_json TEXT;
ALTER TABLE approval_application ADD COLUMN notification_texts_json TEXT;
ALTER TABLE notification_inbox ADD COLUMN content VARCHAR(500);
