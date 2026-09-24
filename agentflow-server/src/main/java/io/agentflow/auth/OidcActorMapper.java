package io.agentflow.auth;

import io.agentflow.common.Actor;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;

/**
 * 将协议层已验证的 ID Token 映射为本平台身份；不自动建租户、同步组织或授予默认角色。
 * @author owlzhangfq@gmail.com
 */
public final class OidcActorMapper {
    private static final int MAX_SUBJECT_LENGTH = 128;
    private static final int MAX_ROLE_CLAIMS = 256;
    private final OidcProperties properties;

    /** 映射只使用部署时明确配置的可信声明及白名单。 */
    public OidcActorMapper(OidcProperties properties) { this.properties = properties; }

    /** 只接受稳定 sub、单一租户字符串与角色字符串数组，拒绝类型转换和空权限。 */
    public Actor map(OidcIdToken token, Instant now) {
        Object subject = token.getClaims().get("sub");
        Object tenant = token.getClaims().get(properties.tenantClaim());
        Object claimedRoles = token.getClaims().get(properties.rolesClaim());
        if (!(subject instanceof String user) || StringUtils.isBlank(user) || user.length() > MAX_SUBJECT_LENGTH
                || user.chars().anyMatch(Character::isISOControl) || !(tenant instanceof String externalTenant)
                || !(claimedRoles instanceof Collection<?> roles) || roles.size() > MAX_ROLE_CLAIMS
                || token.getExpiresAt() == null || !token.getExpiresAt().isAfter(now)) throw unmapped();
        String tenantId = properties.tenantMappings().get(externalTenant);
        if (tenantId == null) throw unmapped();
        Set<String> mapped = new LinkedHashSet<>();
        for (Object role : roles) {
            if (!(role instanceof String value)) throw unmapped();
            mapped.addAll(properties.roleMappings().getOrDefault(value, Set.of()));
        }
        if (mapped.isEmpty()) throw unmapped();
        return new Actor(tenantId, user, mapped);
    }

    private OAuth2AuthenticationException unmapped() { return new OAuth2AuthenticationException("identity_not_mapped"); }
}
