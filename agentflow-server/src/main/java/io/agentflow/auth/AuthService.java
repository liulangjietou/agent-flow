package io.agentflow.auth;

import io.agentflow.common.ErrorCode;
import io.agentflow.common.Actor;
import io.agentflow.common.SystemRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.time.Instant;
import io.agentflow.approval.service.TaskRecipientDirectory;
import io.agentflow.definition.DefinitionAssigneeDirectory;
import java.util.List;
import io.agentflow.common.DomainException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 演示认证及演示审批人目录；企业登录由独立 OIDC 接入层负责。
 * @author owlzhangfq@gmail.com
 */
@Service
public class AuthService implements TaskRecipientDirectory, DefinitionAssigneeDirectory {
    private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);
    /** 生产配置文件名；演示登录与不安全回环例外都以它为界。 */
    static final Profiles PRODUCTION = Profiles.of("prod", "production");
    /** 演示账号共用的固定口令，仅在演示认证开启时有效。 */
    private static final String DEMO_PASSWORD = "demo";
    private static final Map<String, Set<String>> DEMO_ROLES = Map.of(
            "admin", roles(SystemRole.EMPLOYEE, SystemRole.APPROVER, SystemRole.FINANCE, SystemRole.FINANCE_CONFIG_ADMIN,
                    SystemRole.PROCESS_ADMIN, SystemRole.ADMIN),
            "finance", roles(SystemRole.EMPLOYEE, SystemRole.APPROVER, SystemRole.FINANCE),
            "cashier", roles(SystemRole.EMPLOYEE, SystemRole.CASHIER),
            "manager", roles(SystemRole.EMPLOYEE, SystemRole.APPROVER, SystemRole.MANAGER),
            "employee", roles(SystemRole.EMPLOYEE, SystemRole.APPROVER),
            "alice", roles(SystemRole.EMPLOYEE, SystemRole.APPROVER),
            "bob", roles(SystemRole.EMPLOYEE, SystemRole.APPROVER));
    private final Map<String, Actor> tokens = new ConcurrentHashMap<>();
    /** 令牌过期时刻；与 tokens 分开存放，任一侧缺失都按失效处理。 */
    private final Map<String, Instant> expiries = new ConcurrentHashMap<>();
    private final boolean demoEnabled;
    private final String demoTenant;
    private final Duration tokenTtl;

    /**
     * 容器入口：prod 配置文件虽写死关闭演示登录，环境变量或命令行仍能把它覆盖成开启；
     * 演示口令公开固定，生产实例出现这种组合时直接拒绝启动，而不是带着公开口令上线。
     */
    @Autowired
    public AuthService(@Value("${agentflow.auth.demo-enabled:false}") boolean demoEnabled,
                       @Value("${agentflow.auth.demo-tenant:demo}") String demoTenant,
                       @Value("${agentflow.auth.demo-token-ttl:PT12H}") Duration tokenTtl,
                       Environment environment) {
        this(requireOutsideProduction(demoEnabled, environment), demoTenant, tokenTtl);
    }

    /** 创建认证服务；演示令牌按配置时长过期，默认 12 小时。 */
    public AuthService(boolean demoEnabled, String demoTenant, Duration tokenTtl) {
        if (tokenTtl.isNegative() || tokenTtl.isZero()) throw new IllegalArgumentException("agentflow.auth.demo-token-ttl must be positive");
        this.demoEnabled = demoEnabled;
        this.demoTenant = demoTenant;
        this.tokenTtl = tokenTtl;
        if (demoEnabled) {
            // 演示认证使用公开的固定口令，只能用于本机开发与演示；生产必须使用 prod 配置关闭。
            LOG.info("Demo authentication is ENABLED with shared demo credentials; never expose this instance, errorCode={}, tenant={}",
                    "DEMO_AUTH_ENABLED", demoTenant);
        }
    }

    private static boolean requireOutsideProduction(boolean demoEnabled, Environment environment) {
        if (demoEnabled && environment.acceptsProfiles(PRODUCTION)) {
            throw new IllegalStateException("Demo authentication must stay disabled under the prod profile");
        }
        return demoEnabled;
    }

    private static Set<String> roles(SystemRole... roles) {
        return java.util.Arrays.stream(roles).map(SystemRole::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** 只在开发配置启用演示账号登录。 */
    public LoginResult login(String tenantId, String username, String password) {
        if (!demoEnabled) {
            throw new DomainException(ErrorCode.AUTH_PROVIDER_NOT_CONFIGURED, "Enterprise identity provider is not configured");
        }
        if (!demoTenant.equals(tenantId)) {
            throw new DomainException(ErrorCode.UNAUTHENTICATED, "Tenant is not available for demo authentication");
        }
        if (!DEMO_PASSWORD.equals(password)) {
            throw new DomainException(ErrorCode.UNAUTHENTICATED, "Invalid credentials");
        }
        Set<String> roles = DEMO_ROLES.get(username);
        if (roles == null) throw new DomainException(ErrorCode.UNAUTHENTICATED, "Invalid credentials");
        Actor actor = new Actor(tenantId, username, roles);
        String token = UUID.randomUUID().toString();
        Instant now = Instant.now();
        expiries.entrySet().removeIf(entry -> {
            if (!expired(entry.getValue(), now)) return false;
            tokens.remove(entry.getKey());
            return true;
        });
        expiries.put(token, now.plus(tokenTtl));
        tokens.put(token, actor);
        return new LoginResult(token, actor);
    }

    /** 演示目录与登录共用账号来源；禁用演示或跨租户时不提供接收人。 */
    @Override
    public List<String> approvers(String tenantId) {
        if (!demoEnabled || !demoTenant.equals(tenantId)) return List.of();
        return DEMO_ROLES.entrySet().stream().filter(entry -> entry.getValue().contains(SystemRole.APPROVER.name()))
                .map(Map.Entry::getKey).sorted().toList();
    }

    /** 普通员工和出纳也可以接收本人提醒，通知资格不要求审批角色。 */
    public boolean activeAccount(String tenantId, String username) {
        return demoEnabled && demoTenant.equals(tenantId) && DEMO_ROLES.containsKey(username);
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
        Instant expiresAt = expiries.get(token);
        if (actor == null || expiresAt == null || expired(expiresAt, Instant.now())) {
            if (actor != null) logout(token);
            throw new DomainException(ErrorCode.UNAUTHENTICATED, "Token is invalid or expired");
        }
        return actor;
    }

    /** 后台只保存登录指纹，不能将此指纹作为 Bearer 凭据使用。 */
    public String loginReference(String token) {
        authenticate(token);
        return fingerprint(token);
    }

    /** 注销或进程重启后演示登录失效，不从账号目录重新授予后台权限。 */
    public Optional<Actor> actorForLoginReference(String reference) {
        if (!demoEnabled) return Optional.empty();
        Instant now = Instant.now();
        return tokens.entrySet().stream().filter(entry -> {
                    Instant expiresAt = expiries.get(entry.getKey());
                    return expiresAt != null && !expired(expiresAt, now);
                })
                .filter(entry -> fingerprint(entry.getKey()).equals(reference))
                .map(Map.Entry::getValue).findFirst();
    }

    private static String fingerprint(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }

    /** 注销当前 token。 */
    public void logout(String token) {
        tokens.remove(token);
        expiries.remove(token);
    }

    /**
     * 登录结果。
     * @author owlzhangfq@gmail.com
     */
    public record LoginResult(String token, Actor actor) { }

    private static boolean expired(Instant expiresAt, Instant now) { return !expiresAt.isAfter(now); }
}
