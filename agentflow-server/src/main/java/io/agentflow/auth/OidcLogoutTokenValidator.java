package io.agentflow.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 注销协议的声明边界；签名、算法和可信公钥获取由 NimbusJwtDecoder 负责。
 * @author owlzhangfq@gmail.com
 */
public final class OidcLogoutTokenValidator implements OAuth2TokenValidator<Jwt> {
    static final String EVENT = "http://schemas.openid.net/event/backchannel-logout";
    static final Duration CLOCK_SKEW = Duration.ofSeconds(60);
    private static final Duration MAX_AGE = Duration.ofMinutes(5);
    private final String issuer;
    private final String clientId;
    private final Clock clock;

    /** 固定部署配置和时钟，禁止使用未验证声明选择签发方或公钥地址。 */
    public OidcLogoutTokenValidator(String issuer, String clientId, Clock clock) {
        this.issuer = issuer;
        this.clientId = clientId;
        this.clock = clock;
    }

    /** 依当前协议要求检查过期、注销事件和目标，ID Token 不能冒充 Logout Token。 */
    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        Map<String, Object> claims = token.getClaims();
        Object audience = claims.get("aud");
        boolean audienceMatches = audience instanceof String value ? clientId.equals(value)
                : audience instanceof Collection<?> values && values.stream().allMatch(String.class::isInstance)
                        && values.contains(clientId);
        Instant now = clock.instant();
        Instant issuedAt = token.getIssuedAt();
        Instant expiresAt = token.getExpiresAt();
        boolean valid = issuer.equals(claims.get("iss")) && audienceMatches
                && issuedAt != null && !issuedAt.isAfter(now.plus(CLOCK_SKEW))
                && !issuedAt.isBefore(now.minus(MAX_AGE)) && expiresAt != null && expiresAt.isAfter(issuedAt)
                && expiresAt.isAfter(now.minus(CLOCK_SKEW))
                && (token.getNotBefore() == null || !token.getNotBefore().isAfter(now.plus(CLOCK_SKEW)));
        return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_logout_token", "Logout token validation failed", null));
    }

    /** 先检查原始结构；底层声明转换会删除空值或转换类型，不能用转换结果判断字段是否出现。 */
    static void requireShape(Map<String, Object> claims) {
        Object subject = claims.get("sub");
        Object sid = claims.get("sid");
        Object audience = claims.get("aud");
        Object events = claims.get("events");
        boolean valid = claims.get("iss") instanceof String && identifier(claims.get("jti"), 512)
                && (audience instanceof String || audience instanceof Collection<?> values
                        && values.stream().allMatch(String.class::isInstance))
                && (!claims.containsKey("sub") || identifier(subject, 128))
                && (!claims.containsKey("sid") || identifier(sid, 512)) && (subject != null || sid != null)
                && !claims.containsKey("nonce") && events instanceof Map<?, ?> values
                && values.get(EVENT) instanceof Map<?, ?>
                && claims.get("iat") instanceof Number && claims.get("exp") instanceof Number
                && (!claims.containsKey("nbf") || claims.get("nbf") instanceof Number);
        if (!valid) throw new IllegalArgumentException("Invalid logout token claims");
    }

    static boolean identifier(Object value, int limit) {
        return value instanceof String text && StringUtils.isNotBlank(text) && text.length() <= limit
                && text.chars().noneMatch(Character::isISOControl);
    }
}
