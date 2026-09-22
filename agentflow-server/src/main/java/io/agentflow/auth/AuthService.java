package io.agentflow.auth;

import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 认证端口的首版实现；生产环境应替换为企业 IdP/OIDC 适配器。 */
@Service
public class AuthService {
    private final Map<String, Actor> tokens = new ConcurrentHashMap<>();
    private final boolean demoEnabled;
    private final String demoTenant;

    /** 创建认证服务。 */
    public AuthService(@Value("${agentflow.auth.demo-enabled:false}") boolean demoEnabled,
                       @Value("${agentflow.auth.demo-tenant:demo}") String demoTenant) {
        this.demoEnabled = demoEnabled;
        this.demoTenant = demoTenant;
    }

    /** 只在开发配置启用演示账号登录。 */
    public LoginResult login(String tenantId, String username, String password) {
        if (!demoEnabled) {
            throw new DomainException("AUTH_PROVIDER_NOT_CONFIGURED", "Enterprise identity provider is not configured");
        }
        if (!demoTenant.equals(tenantId)) {
            throw new DomainException("UNAUTHENTICATED", "Tenant is not available for demo authentication");
        }
        if (!"demo".equals(password)) {
            throw new DomainException("UNAUTHENTICATED", "Invalid credentials");
        }
        Set<String> roles = switch (username) {
            case "admin" -> Set.of("EMPLOYEE", "APPROVER", "FINANCE", "PROCESS_ADMIN", "ADMIN");
            case "finance" -> Set.of("EMPLOYEE", "APPROVER", "FINANCE");
            case "manager" -> Set.of("EMPLOYEE", "APPROVER", "MANAGER");
            case "employee", "alice", "bob" -> Set.of("EMPLOYEE", "APPROVER");
            default -> throw new DomainException("UNAUTHENTICATED", "Invalid credentials");
        };
        Actor actor = new Actor(tenantId, username, roles);
        String token = UUID.randomUUID().toString();
        tokens.put(token, actor);
        return new LoginResult(token, actor);
    }

    /** 从 Bearer token 解析认证主体。 */
    public Actor authenticate(String token) {
        Actor actor = tokens.get(token);
        if (actor == null) {
            throw new DomainException("UNAUTHENTICATED", "Token is invalid or expired");
        }
        return actor;
    }

    /** 注销当前 token。 */
    public void logout(String token) {
        tokens.remove(token);
    }

    /** 登录结果。 */
    public record LoginResult(String token, Actor actor) { }
}
