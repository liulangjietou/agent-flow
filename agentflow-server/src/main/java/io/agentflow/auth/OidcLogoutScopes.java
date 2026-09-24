package io.agentflow.auth;

import io.agentflow.common.JsonUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
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

    /** 仅接收入口已验签和校验过的注销令牌；水位只前进，重复和乱序通知不会扩大范围。 */
    public void revoke(Jwt token) {
        String subject = token.getSubject();
        String sid = token.getClaimAsString("sid");
        String scope = subject == null ? hash("session", sid) : sid == null ? hash("subject", subject)
                : hash("session_subject", subject, sid);
        long issuedAt = token.getIssuedAt().getEpochSecond();
        try {
            transaction.executeWithoutResult(status -> {
                if (advance(scope, issuedAt) == 0) {
                    jdbc.update("INSERT INTO AF_OIDC_LOGOUT_SCOPE (SCOPE_HASH, LOGGED_OUT_AT) VALUES (?, ?)", scope, issuedAt);
                }
            });
        } catch (DuplicateKeyException concurrentInsert) {
            // 首次通知可同时到达多个实例；失败事务退出后，用新事务更新已经建立的水位。
            transaction.executeWithoutResult(status -> advance(scope, issuedAt));
        }
    }

    /** 登录回调和每次业务请求均检查水位，覆盖通知先于回调落库的情况。 */
    public void requireActive(OidcIdToken token, Instant now) {
        Object rawSid = token.getClaims().get("sid");
        if (token.getIssuedAt() == null || token.getIssuedAt().isAfter(now.plus(OidcLogoutTokenValidator.CLOCK_SKEW))
                || (token.getClaims().containsKey("sid") && !OidcLogoutTokenValidator.identifier(rawSid, 512))) {
            throw new OAuth2AuthenticationException("invalid_session_identity");
        }
        String subjectScope = hash("subject", token.getSubject());
        String sidScope = rawSid instanceof String sid ? hash("session", sid) : subjectScope;
        String combinedScope = rawSid instanceof String sid ? hash("session_subject", token.getSubject(), sid) : subjectScope;
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM AF_OIDC_LOGOUT_SCOPE
                WHERE SCOPE_HASH IN (?, ?, ?) AND LOGGED_OUT_AT >= ?
                """, Integer.class, subjectScope, sidScope, combinedScope, token.getIssuedAt().getEpochSecond());
        if (count > 0) throw new OAuth2AuthenticationException("session_revoked");
    }

    private int advance(String scope, long issuedAt) {
        return jdbc.update("""
                UPDATE AF_OIDC_LOGOUT_SCOPE
                SET LOGGED_OUT_AT = CASE WHEN LOGGED_OUT_AT < ? THEN ? ELSE LOGGED_OUT_AT END
                WHERE SCOPE_HASH = ?
                """, issuedAt, issuedAt, scope);
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
