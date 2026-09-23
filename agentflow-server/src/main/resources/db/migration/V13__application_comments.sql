-- 协作评论不改变申请、轮次和引擎任务；仅为后续真实评论建立存储，不补造历史数据。
CREATE TABLE application_comment (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    author_id VARCHAR(128) NOT NULL,
    content VARCHAR(4000) NOT NULL,
    round_no INTEGER NOT NULL,
    application_version BIGINT NOT NULL,
    application_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_comment_application FOREIGN KEY (application_id) REFERENCES approval_application(id),
    CONSTRAINT ck_comment_round CHECK (round_no > 0),
    CONSTRAINT ck_comment_version CHECK (application_version > 0)
);
CREATE INDEX idx_comment_application_page ON application_comment (tenant_id, application_id, created_at DESC, id DESC);
