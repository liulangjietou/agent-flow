package io.agentflow.auth;

import io.agentflow.common.Actor;
import java.io.Serializable;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * 会话中的已验证企业身份；不保存 access token、refresh token 或用户密码。
 * @author owlzhangfq@gmail.com
 */
public record PlatformOidcUser(OidcIdToken idToken, String tenantId, String userId, Set<String> roles)
        implements OidcUser, Serializable {
    public PlatformOidcUser { roles = Set.copyOf(roles); }
    public OidcIdToken getIdToken() { return idToken; }
    public OidcUserInfo getUserInfo() { return null; }
    public Map<String, Object> getClaims() { return idToken.getClaims(); }
    public Map<String, Object> getAttributes() { return getClaims(); }
    public String getName() { return userId; }
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream().map(SimpleGrantedAuthority::new).toList();
    }
    /** 当前请求仍进入既有领域资源授权，不从 ID Token 直接办理审批。 */
    public Actor actor() { return new Actor(tenantId, userId, roles); }
}
