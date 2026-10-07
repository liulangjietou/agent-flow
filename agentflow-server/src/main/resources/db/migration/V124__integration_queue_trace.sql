-- 后台集成追踪不进入业务身份、签名原文或幂等摘要，历史记录不伪造请求来源。
ALTER TABLE service_task_operation ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE signature_operation ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE organization_sync_batch ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE notification_dispatch ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE event_inbox ADD COLUMN trace_id VARCHAR(36);
ALTER TABLE payment_callback ADD COLUMN trace_id VARCHAR(36);
