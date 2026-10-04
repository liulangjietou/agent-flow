-- 只保存不可用于登录的内部引用；原会话注销或清理不能删除签署授权历史。
CREATE TABLE signature_login_authorization (
    tenant_id VARCHAR(64) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    authentication_kind VARCHAR(32) NOT NULL,
    login_reference VARCHAR(64) NOT NULL,
    PRIMARY KEY (tenant_id, operation_id),
    FOREIGN KEY (tenant_id, operation_id) REFERENCES signature_operation(tenant_id, id),
    CHECK (authentication_kind IN ('DEMO_LOGIN', 'OIDC_SESSION')),
    CHECK ((authentication_kind = 'DEMO_LOGIN' AND LENGTH(login_reference) = 64)
        OR (authentication_kind = 'OIDC_SESSION' AND LENGTH(login_reference) = 36))
);
