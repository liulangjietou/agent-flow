-- 过期幂等记录保留键与摘要作为墓碑（拒绝过期键重执业务），只按到期时间分批清除响应正文。
CREATE INDEX ix_request_idempotency_expiry ON request_idempotency (expires_at);
