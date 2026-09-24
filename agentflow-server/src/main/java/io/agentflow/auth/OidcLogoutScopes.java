package io.agentflow.auth;

import io.agentflow.common.JsonUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 注销水位属于认证基础设施；跨实例查询同一数据库，不缓存允许结果、不存储原始令牌。
 * @author owlzhangfq@gmail.com
 */
public final class OidcLogoutScopes {
    static final String SESSION_ORDER = OidcLogoutScopes.class.getName() + ".loginOrder";
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final OidcProperties oidc;
    private final TransactionTemplate transaction;

    /** 独立事务保证签名通知的持久化不加入任何审批业务事务。 */
    public OidcLogoutScopes(JdbcTemplate jdbc, JsonUtil json, OidcProperties oidc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.json = json;
        this.oidc = oidc;
        this.transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 授权请求开始前取得已提交的注销顺序；原授权请求负责跨实例保存这个基线。 */
    public long beginLogin() {
        return jdbc.queryForObject("SELECT LAST_ORDER FROM AF_OIDC_LOGOUT_ORDER WHERE ID=1", Long.class);
    }

    /** 仅接收入口已验签和校验过的令牌；通知去重、推进顺序与作用域在同一事务提交。 */
    public void revoke(Jwt token) {
        String subject = token.getSubject();
        String sid = token.getClaimAsString("sid");
        String scope = subject == null ? hash("session", sid) : sid == null ? hash("subject", subject)
                : hash("session_subject", subject, sid);
        long issuedAt = token.getIssuedAt().getEpochSecond();
        String event = hash("event", token.getId());
        transaction.executeWithoutResult(status -> {
            // 只串行化很短的注销事务；登录读取已提交顺序，不持有外部身份服务调用期间的锁。
            long current = jdbc.queryForObject("SELECT LAST_ORDER FROM AF_OIDC_LOGOUT_ORDER WHERE ID=1 FOR UPDATE", Long.class);
            if (jdbc.queryForObject("SELECT COUNT(*) FROM AF_OIDC_LOGOUT_EVENT WHERE EVENT_HASH=?", Integer.class, event) > 0) return;
            long next = Math.incrementExact(current);
            jdbc.update("UPDATE AF_OIDC_LOGOUT_ORDER SET LAST_ORDER=? WHERE ID=1", next);
            jdbc.update("INSERT INTO AF_OIDC_LOGOUT_EVENT (EVENT_HASH) VALUES (?)", event);
            int changed = jdbc.update("""
                    UPDATE AF_OIDC_LOGOUT_SCOPE
                    SET LOGGED_OUT_AT = CASE WHEN LOGGED_OUT_AT < ? THEN ? ELSE LOGGED_OUT_AT END, LOGOUT_ORDER=?
                    WHERE SCOPE_HASH = ?
                    """, issuedAt, issuedAt, next, scope);
            if (changed == 0) jdbc.update("""
                    INSERT INTO AF_OIDC_LOGOUT_SCOPE (SCOPE_HASH, LOGGED_OUT_AT, LOGOUT_ORDER) VALUES (?, ?, ?)
                    """, scope, issuedAt, next);
        });
    }

    /** 登录回调和每次业务请求均检查水位，覆盖通知先于回调落库的情况。 */
    public void requireActive(OidcIdToken token, Long loginOrder) {
        Object rawSid = token.getClaims().get("sid");
        if (loginOrder == null || loginOrder < 0 || (token.getClaims().containsKey("sid")
                && !OidcLogoutTokenValidator.identifier(rawSid, OidcLogoutTokenValidator.MAX_SESSION_ID_LENGTH))) {
            throw new OAuth2AuthenticationException("invalid_session_identity");
        }
        String subjectScope = hash("subject", token.getSubject());
        String sidScope = rawSid instanceof String sid ? hash("session", sid) : subjectScope;
        String combinedScope = rawSid instanceof String sid ? hash("session_subject", token.getSubject(), sid) : subjectScope;
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM AF_OIDC_LOGOUT_SCOPE
                WHERE SCOPE_HASH IN (?, ?, ?) AND LOGOUT_ORDER > ?
                """, Integer.class, subjectScope, sidScope, combinedScope, loginOrder);
        if (count > 0) throw new OAuth2AuthenticationException("session_revoked");
    }

    private String hash(String kind, String... identifiers) {
        try {
            byte[] bytes = json.write(List.of(oidc.issuer(), oidc.clientId(), kind, List.of(identifiers)))
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }
}
