-- 本地独立身份认证（口令哈希 + TOTP MFA + 持久化会话）及多租户动态 OIDC 提供方配置。
CREATE TABLE local_user_account (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    username VARCHAR(128) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    password_hash VARCHAR(256) NOT NULL,
    roles_json TEXT NOT NULL,
    enabled BOOLEAN NOT NULL,
    failed_attempts INT NOT NULL,
    locked_until TIMESTAMP WITH TIME ZONE NULL,
    mfa_enabled BOOLEAN NOT NULL,
    mfa_secret VARCHAR(128) NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_local_user_tenant_username UNIQUE (tenant_id, username)
);

CREATE TABLE local_auth_session (
    token_hash VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    username VARCHAR(128) NOT NULL,
    roles_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE NULL
);

CREATE INDEX idx_local_auth_session_user
    ON local_auth_session (tenant_id, username, expires_at);

CREATE TABLE tenant_oidc_provider (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    provider_key VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    issuer_uri VARCHAR(512) NOT NULL,
    client_id VARCHAR(256) NOT NULL,
    client_secret VARCHAR(1024) NOT NULL,
    scopes VARCHAR(512) NOT NULL,
    enabled BOOLEAN NOT NULL,
    version BIGINT NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_tenant_oidc_provider_key UNIQUE (tenant_id, provider_key)
);
