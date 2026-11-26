package io.agentflow.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentflow.auth.mapper.LocalIdentityMapper;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.ErrorCode;
import io.agentflow.common.JsonUtil;
import io.agentflow.common.SystemRole;
import io.agentflow.mybatis.SqlRow;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 本地独立账号认证、TOTP 多因素认证与持久化 Bearer 会话服务。
 * 与演示认证解耦，可在多副本部署下共享数据库会话与锁定状态。
 * @author owlzhangfq@gmail.com
 */
@Service
public class LocalIdentityService {
    private static final Logger LOG = LoggerFactory.getLogger(LocalIdentityService.class);
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    private static final String TOTP_ISSUER = "AgentFlow";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final TypeReference<List<String>> ROLE_LIST_TYPE = new TypeReference<>() { };

    private final LocalIdentityMapper mapper;
    private final JsonUtil json;
    private final Duration sessionTtl;

    /** 构造本地身份服务；会话有效期默认 12 小时。 */
    public LocalIdentityService(
            LocalIdentityMapper mapper,
            JsonUtil json,
            @Value("${agentflow.auth.local-session-ttl:PT12H}") Duration sessionTtl) {
        if (sessionTtl == null || sessionTtl.isNegative() || sessionTtl.isZero()) {
            throw new IllegalArgumentException("agentflow.auth.local-session-ttl must be positive");
        }
        this.mapper = mapper;
        this.json = json;
        this.sessionTtl = sessionTtl;
    }

    /** 管理员在本租户创建本地账号，初始口令须满足复杂度且以 PBKDF2 哈希入库。 */
    @Transactional
    public UserView createUser(Actor admin, String username, String displayName, String password, Set<String> roles) {
        admin.requireRole(SystemRole.ADMIN);
        String normalizedUser = normalizeUsername(username);
        String normalizedName = normalizeDisplayName(displayName);
        Set<String> validRoles = validateRoles(roles);
        if (!mapper.findByUsername(admin.tenantId(), normalizedUser).isEmpty()) {
            throw new DomainException(ErrorCode.LOCAL_USER_EXISTS, "Local user already exists in this tenant");
        }
        String hash = LocalPasswordHasher.hash(password);
        UUID id = UUID.randomUUID();
        Instant now = now();
        mapper.insertUser(
                id.toString(),
                admin.tenantId(),
                normalizedUser,
                normalizedName,
                hash,
                json.write(List.copyOf(validRoles)),
                true,
                Timestamp.from(now));
        LOG.info("Local user created, tenantId={}, userId={}", admin.tenantId(), id);
        return new UserView(id, admin.tenantId(), normalizedUser, normalizedName, validRoles, true, false, 1L, now, now);
    }

    /** 管理员查看本租户全部本地账号（不返回口令哈希或 TOTP 密钥）。 */
    @Transactional(readOnly = true)
    public List<UserView> listUsers(Actor admin) {
        admin.requireRole(SystemRole.ADMIN);
        return mapper.listByTenant(admin.tenantId()).stream().map(this::toUserView).toList();
    }

    /** 管理员调整本地账号启停与角色，禁用时同步吊销其全部活跃会话。 */
    @Transactional
    public UserView updateUser(Actor admin, UUID userId, boolean enabled, Set<String> roles, long expectedVersion) {
        admin.requireRole(SystemRole.ADMIN);
        Set<String> validRoles = validateRoles(roles);
        AccountRecord existing = requireUserById(admin.tenantId(), userId);
        Instant now = now();
        int updated = mapper.updateAccountStatus(
                admin.tenantId(),
                existing.id().toString(),
                enabled,
                json.write(List.copyOf(validRoles)),
                expectedVersion,
                Timestamp.from(now));
        if (updated != 1) {
            throw new DomainException(ErrorCode.CONCURRENCY_CONFLICT, "Local user version has changed");
        }
        if (!enabled) {
            mapper.revokeAllUserSessions(admin.tenantId(), existing.username(), Timestamp.from(now));
        }
        return toUserView(mapper.findById(admin.tenantId(), existing.id().toString()).get(0));
    }

    /** 管理员重置本地账号口令并吊销旧会话。 */
    @Transactional
    public UserView resetPassword(Actor admin, UUID userId, String newPassword, long expectedVersion) {
        admin.requireRole(SystemRole.ADMIN);
        AccountRecord existing = requireUserById(admin.tenantId(), userId);
        String hash = LocalPasswordHasher.hash(newPassword);
        Instant now = now();
        int updated = mapper.updatePassword(
                admin.tenantId(), existing.id().toString(), hash, expectedVersion, Timestamp.from(now));
        if (updated != 1) {
            throw new DomainException(ErrorCode.CONCURRENCY_CONFLICT, "Local user version has changed");
        }
        mapper.revokeAllUserSessions(admin.tenantId(), existing.username(), Timestamp.from(now));
        LOG.info("Local user password reset by admin, tenantId={}, userId={}", admin.tenantId(), existing.id());
        return toUserView(mapper.findById(admin.tenantId(), existing.id().toString()).get(0));
    }

    /** 本地账号口令 + 可选 TOTP 登录；支持失败锁定与多副本共享数据库会话。 */
    @Transactional(noRollbackFor = DomainException.class)
    public AuthService.LoginResult login(String tenantId, String username, String password, String totpCode) {
        if (StringUtils.isBlank(tenantId) || StringUtils.isBlank(username) || StringUtils.isEmpty(password)) {
            throw new DomainException(ErrorCode.UNAUTHENTICATED, "Invalid credentials");
        }
        var rows = mapper.findByUsername(tenantId.trim(), username.trim());
        if (rows.isEmpty()) {
            throw new DomainException(ErrorCode.UNAUTHENTICATED, "Invalid credentials");
        }
        AccountRecord account = toAccountRecord(rows.get(0));
        if (!account.enabled()) {
            throw new DomainException(ErrorCode.UNAUTHENTICATED, "Account is disabled");
        }
        Instant now = now();
        if (account.lockedUntil() != null && account.lockedUntil().isAfter(now)) {
            throw new DomainException(ErrorCode.ACCOUNT_LOCKED, "Account is temporarily locked due to repeated login failures");
        }
        if (!LocalPasswordHasher.verify(password, account.passwordHash())) {
            int nextAttempts = account.failedAttempts() + 1;
            Timestamp lockedUntil = nextAttempts >= MAX_FAILED_ATTEMPTS
                    ? Timestamp.from(now.plus(LOCK_DURATION)) : null;
            mapper.updateLoginFailure(account.tenantId(), account.id().toString(), nextAttempts, lockedUntil, Timestamp.from(now));
            if (lockedUntil != null) {
                LOG.info("Local account locked after repeated failures, tenantId={}, userId={}", account.tenantId(), account.id());
                throw new DomainException(ErrorCode.ACCOUNT_LOCKED, "Account is temporarily locked due to repeated login failures");
            }
            throw new DomainException(ErrorCode.UNAUTHENTICATED, "Invalid credentials");
        }
        if (account.mfaEnabled()) {
            if (StringUtils.isBlank(totpCode)) {
                throw new DomainException(ErrorCode.MFA_CODE_REQUIRED, "MFA TOTP verification code is required");
            }
            if (!TotpAuthenticator.verify(account.mfaSecret(), totpCode.trim(), now)) {
                throw new DomainException(ErrorCode.MFA_CODE_INVALID, "Invalid MFA TOTP verification code");
            }
        }
        if (account.failedAttempts() > 0 || account.lockedUntil() != null) {
            mapper.clearLoginFailure(account.tenantId(), account.id().toString(), Timestamp.from(now));
        }
        String rawToken = generateOpaqueToken();
        String tokenHash = sha256Hex(rawToken);
        mapper.insertSession(
                tokenHash,
                account.tenantId(),
                account.username(),
                json.write(List.copyOf(account.roles())),
                Timestamp.from(now),
                Timestamp.from(now.plus(sessionTtl)));
        Actor actor = new Actor(account.tenantId(), account.username(), account.roles());
        return new AuthService.LoginResult(rawToken, actor);
    }

    /** 校验持久化本地会话令牌，不匹配、过期或已撤销均返回空。 */
    @Transactional(readOnly = true)
    public Optional<Actor> authenticateSession(String rawToken) {
        if (StringUtils.isBlank(rawToken)) return Optional.empty();
        var rows = mapper.findSession(sha256Hex(rawToken));
        if (rows.isEmpty()) return Optional.empty();
        SqlRow row = rows.get(0);
        if (row.getTimestamp("revoked_at") != null) return Optional.empty();
        Timestamp expiresAt = row.getTimestamp("expires_at");
        if (expiresAt == null || !expiresAt.toInstant().isAfter(Instant.now())) return Optional.empty();
        String tenantId = row.getString("tenant_id");
        String username = row.getString("username");
        List<String> roleList = parseRoles(row.getString("roles_json"));
        return Optional.of(new Actor(tenantId, username, Set.copyOf(roleList)));
    }

    /** 吊销单条本地会话令牌。 */
    @Transactional
    public void revokeSession(String rawToken) {
        if (StringUtils.isBlank(rawToken)) return;
        mapper.revokeSession(sha256Hex(rawToken), Timestamp.from(now()));
    }

    /** 已登录本地用户自行修改口令，成功后吊销旧会话。 */
    @Transactional
    public void changeOwnPassword(Actor actor, String currentPassword, String newPassword) {
        AccountRecord account = requireUserByUsername(actor.tenantId(), actor.userId());
        if (!LocalPasswordHasher.verify(currentPassword, account.passwordHash())) {
            throw new DomainException(ErrorCode.UNAUTHENTICATED, "Current password is incorrect");
        }
        String nextHash = LocalPasswordHasher.hash(newPassword);
        Instant now = now();
        int updated = mapper.updatePassword(
                actor.tenantId(), account.id().toString(), nextHash, account.version(), Timestamp.from(now));
        if (updated != 1) {
            throw new DomainException(ErrorCode.CONCURRENCY_CONFLICT, "Account state changed; retry password change");
        }
        mapper.revokeAllUserSessions(actor.tenantId(), actor.userId(), Timestamp.from(now));
    }

    /** 生成新的 TOTP 密钥（未正式启用），返回一次性密钥与扫码 URI。 */
    @Transactional
    public MfaEnrollment enrollMfa(Actor actor) {
        AccountRecord account = requireUserByUsername(actor.tenantId(), actor.userId());
        String secret = TotpAuthenticator.generateSecret();
        Instant now = now();
        int updated = mapper.updateMfa(
                actor.tenantId(), account.id().toString(), false, secret, account.version(), Timestamp.from(now));
        if (updated != 1) {
            throw new DomainException(ErrorCode.CONCURRENCY_CONFLICT, "Account state changed; retry MFA enrollment");
        }
        String uri = TotpAuthenticator.provisioningUri(TOTP_ISSUER, actor.tenantId() + ":" + actor.userId(), secret);
        return new MfaEnrollment(secret, uri, account.version() + 1);
    }

    /** 核对首个 TOTP 验证码后正式开启 MFA。 */
    @Transactional
    public UserView enableMfa(Actor actor, String totpCode) {
        AccountRecord account = requireUserByUsername(actor.tenantId(), actor.userId());
        if (StringUtils.isBlank(account.mfaSecret())) {
            throw new DomainException(ErrorCode.DOMAIN_RULE_VIOLATION, "Enroll MFA before enabling it");
        }
        Instant now = now();
        if (!TotpAuthenticator.verify(account.mfaSecret(), totpCode == null ? null : totpCode.trim(), now)) {
            throw new DomainException(ErrorCode.MFA_CODE_INVALID, "Invalid MFA TOTP verification code");
        }
        int updated = mapper.updateMfa(
                actor.tenantId(), account.id().toString(), true, account.mfaSecret(), account.version(), Timestamp.from(now));
        if (updated != 1) {
            throw new DomainException(ErrorCode.CONCURRENCY_CONFLICT, "Account state changed; retry MFA activation");
        }
        return toUserView(mapper.findById(actor.tenantId(), account.id().toString()).get(0));
    }

    /** 核对当前口令与 TOTP 验证码后关闭 MFA 并清除密钥。 */
    @Transactional
    public UserView disableMfa(Actor actor, String currentPassword, String totpCode) {
        AccountRecord account = requireUserByUsername(actor.tenantId(), actor.userId());
        if (!LocalPasswordHasher.verify(currentPassword, account.passwordHash())) {
            throw new DomainException(ErrorCode.UNAUTHENTICATED, "Current password is incorrect");
        }
        Instant now = now();
        if (account.mfaEnabled() && !TotpAuthenticator.verify(account.mfaSecret(), totpCode == null ? null : totpCode.trim(), now)) {
            throw new DomainException(ErrorCode.MFA_CODE_INVALID, "Invalid MFA TOTP verification code");
        }
        int updated = mapper.updateMfa(
                actor.tenantId(), account.id().toString(), false, null, account.version(), Timestamp.from(now));
        if (updated != 1) {
            throw new DomainException(ErrorCode.CONCURRENCY_CONFLICT, "Account state changed; retry MFA disable");
        }
        return toUserView(mapper.findById(actor.tenantId(), account.id().toString()).get(0));
    }

    private AccountRecord requireUserById(String tenantId, UUID id) {
        var rows = mapper.findById(tenantId, id.toString());
        if (rows.isEmpty()) throw new DomainException(ErrorCode.NOT_FOUND, "Local user not found");
        return toAccountRecord(rows.get(0));
    }

    private AccountRecord requireUserByUsername(String tenantId, String username) {
        var rows = mapper.findByUsername(tenantId, username);
        if (rows.isEmpty()) throw new DomainException(ErrorCode.NOT_FOUND, "Local user not found");
        return toAccountRecord(rows.get(0));
    }

    private AccountRecord toAccountRecord(SqlRow row) {
        Timestamp lockedTs = row.getTimestamp("locked_until");
        return new AccountRecord(
                UUID.fromString(row.getString("id")),
                row.getString("tenant_id"),
                row.getString("username"),
                row.getString("display_name"),
                row.getString("password_hash"),
                Set.copyOf(parseRoles(row.getString("roles_json"))),
                Boolean.TRUE.equals(row.getBoolean("enabled")),
                row.getInt("failed_attempts"),
                lockedTs == null ? null : lockedTs.toInstant(),
                Boolean.TRUE.equals(row.getBoolean("mfa_enabled")),
                row.getString("mfa_secret"),
                row.getLong("version"),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant());
    }

    private UserView toUserView(SqlRow row) {
        AccountRecord rec = toAccountRecord(row);
        return new UserView(rec.id(), rec.tenantId(), rec.username(), rec.displayName(),
                rec.roles(), rec.enabled(), rec.mfaEnabled(), rec.version(), rec.createdAt(), rec.updatedAt());
    }

    private List<String> parseRoles(String rolesJson) {
        return json.read(rolesJson, ROLE_LIST_TYPE);
    }

    private static String normalizeUsername(String username) {
        if (StringUtils.isBlank(username) || !username.trim().matches("[A-Za-z0-9][A-Za-z0-9._@-]{1,63}")) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, "Username format is invalid");
        }
        return username.trim();
    }

    private static String normalizeDisplayName(String displayName) {
        if (StringUtils.isBlank(displayName) || displayName.trim().length() > 128) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, "Display name is required and at most 128 characters");
        }
        return displayName.trim();
    }

    private static Set<String> validateRoles(Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, "At least one system role is required");
        }
        Set<String> result = new LinkedHashSet<>();
        for (String role : roles) {
            try {
                result.add(SystemRole.valueOf(role.trim()).name());
            } catch (Exception invalid) {
                throw new DomainException(ErrorCode.INVALID_REQUEST, "Unsupported system role: " + role);
            }
        }
        return Set.copyOf(result);
    }

    private static String generateOpaqueToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "afl_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * 内部账户持久化记录，不对外序列化。
     * @author owlzhangfq@gmail.com
     */
    private record AccountRecord(
            UUID id, String tenantId, String username, String displayName, String passwordHash,
            Set<String> roles, boolean enabled, int failedAttempts, Instant lockedUntil,
            boolean mfaEnabled, String mfaSecret, long version, Instant createdAt, Instant updatedAt) { }

    /**
     * 对外安全视图，不含口令哈希与 MFA 密钥。
     * @author owlzhangfq@gmail.com
     */
    public record UserView(
            UUID id, String tenantId, String username, String displayName,
            Set<String> roles, boolean enabled, boolean mfaEnabled, long version,
            Instant createdAt, Instant updatedAt) { }

    /**
     * MFA 绑定初始化响应（仅在 enroll 时刻返回一次）。
     * @author owlzhangfq@gmail.com
     */
    public record MfaEnrollment(String secret, String otpauthUri, long accountVersion) { }
}
