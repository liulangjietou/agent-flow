package io.agentflow.auth;

import io.agentflow.common.Actor;
import io.agentflow.approval.service.TaskRecipientDirectory;
import io.agentflow.definition.DefinitionAssigneeDirectory;
import java.util.List;
import io.agentflow.common.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认证端口的首版实现；生产环境应替换为企业 IdP/OIDC 适配器。
 * @author owlzhangfq@gmail.com
 */
@Service
public class AuthService implements TaskRecipientDirectory, DefinitionAssigneeDirectory {
    private static final Map<String, Set<String>> DEMO_ROLES = Map.of(
            "admin", Set.of("EMPLOYEE", "APPROVER", "FINANCE", "PROCESS_ADMIN", "ADMIN"),
            "finance", Set.of("EMPLOYEE", "APPROVER", "FINANCE"),
            "manager", Set.of("EMPLOYEE", "APPROVER", "MANAGER"),
            "employee", Set.of("EMPLOYEE", "APPROVER"),
            "alice", Set.of("EMPLOYEE", "APPROVER"),
            "bob", Set.of("EMPLOYEE", "APPROVER"));
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
        Set<String> roles = DEMO_ROLES.get(username);
        if (roles == null) throw new DomainException("UNAUTHENTICATED", "Invalid credentials");
        Actor actor = new Actor(tenantId, username, roles);
        String token = UUID.randomUUID().toString();
        tokens.put(token, actor);
        return new LoginResult(token, actor);
    }

    /** 演示目录与登录共用账号来源；禁用演示或跨租户时不提供接收人。 */
    @Override
    public List<String> approvers(String tenantId) {
        if (!demoEnabled || !demoTenant.equals(tenantId)) return List.of();
        return DEMO_ROLES.entrySet().stream().filter(entry -> entry.getValue().contains("APPROVER"))
                .map(Map.Entry::getKey).sorted().toList();
    }

    /** 任务候选人与候选组取并集；与登录共享同一租户、账号和角色来源。 */
    @Override
    public List<String> members(String tenantId, Set<String> users, Set<String> roles) {
        return approvers(tenantId).stream().filter(user -> users.contains(user)
                || DEMO_ROLES.get(user).stream().anyMatch(roles::contains)).toList();
    }

    /** 设计器与登录共用身份源；只展示至少有一位有效审批人的规则。 */
    @Override
    public List<DefinitionAssigneeDirectory.Option> options(String tenantId) {
        List<String> users = approvers(tenantId);
        var options = new java.util.ArrayList<DefinitionAssigneeDirectory.Option>();
        users.forEach(user -> options.add(new DefinitionAssigneeDirectory.Option("user:" + user, user, 1)));
        users.stream().flatMap(user -> DEMO_ROLES.get(user).stream()).distinct().sorted().forEach(role -> {
            int count = (int) users.stream().filter(user -> DEMO_ROLES.get(user).contains(role)).count();
            options.add(new DefinitionAssigneeDirectory.Option("role:" + role, role, count));
        });
        return List.copyOf(options);
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

    /**
     * 登录结果。
     * @author owlzhangfq@gmail.com
     */
    public record LoginResult(String token, Actor actor) { }
}
