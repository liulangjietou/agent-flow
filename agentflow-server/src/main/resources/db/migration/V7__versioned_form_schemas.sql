-- 旧数据保持 NULL，不从当前定义或历史表单值猜测不存在的 schema。
ALTER TABLE approval_definition ADD COLUMN form_schema_json TEXT NULL;
ALTER TABLE approval_application ADD COLUMN form_schema_json TEXT NULL;
ALTER TABLE approval_submission_round ADD COLUMN form_schema_json TEXT NULL;
-- 新申请同时冻结实际定义来源；旧记录未保存过的引擎标识不能凭 key/version 推断。
ALTER TABLE approval_application ADD COLUMN runtime_definition_id VARCHAR(128) NULL;
