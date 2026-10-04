package io.agentflow.auth;

import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * 延迟首次外发绑定原登录；保存不可用于登录的引用，不复制令牌或伪造后台角色。
 * @author owlzhangfq@gmail.com
 */
@Service
public class DeferredActorAuthentication {
    private final AuthService demo;
    private final OidcProperties oidc;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<JdbcIndexedSessionRepository> sessions;
    private final ObjectProvider<OidcLogoutScopes> logouts;

    /** 企业模式必须能跨进程复核原登录，因此使用已配置的共享会话仓储。 */
    public DeferredActorAuthentication(AuthService demo, OidcProperties oidc, JdbcTemplate jdbc,
            ObjectProvider<JdbcIndexedSessionRepository> sessions, ObjectProvider<OidcLogoutScopes> logouts) {
        this.demo = demo; this.oidc = oidc; this.jdbc = jdbc; this.sessions = sessions; this.logouts = logouts;
    }

    /** 捕获当前认证请求对应的原登录；企业引用不是浏览器使用的 SESSION_ID。 */
    public LoginReference capture(HttpServletRequest request, Actor actor, Instant now) {
        LoginReference reference;
        if (oidc.enabled()) {
            if (sessions.getIfAvailable() == null) throw new DomainException("DEFERRED_AUTHENTICATION_UNAVAILABLE", "Deferred execution requires shared OIDC sessions");
            var session = request.getSession(false);
            if (session == null) throw unauthenticated();
            String primary = jdbc.query("SELECT PRIMARY_ID FROM AF_HTTP_SESSION WHERE SESSION_ID=?",
                    (row, index) -> row.getString(1), session.getId()).stream().findFirst().orElseThrow(DeferredActorAuthentication::unauthenticated);
            reference = new LoginReference(Kind.OIDC_SESSION, primary);
        } else {
            String header = request.getHeader("Authorization");
            if (header == null || !header.startsWith("Bearer ")) throw unauthenticated();
            reference = new LoginReference(Kind.DEMO_LOGIN, demo.loginReference(header.substring(7)));
        }
        if (!resolve(reference, actor.tenantId(), actor.userId(), now).filter(actor::equals).isPresent()) throw unauthenticated();
        return reference;
    }

    /** 只返回仍有效且身份完全匹配的原登录；数据库不可用向上抛出，不能当作权限已撤销。 */
    public Optional<Actor> resolve(LoginReference reference, String tenant, String user, Instant now) {
        Optional<Actor> actor = switch (reference.kind()) {
            case DEMO_LOGIN -> oidc.enabled() ? Optional.empty() : demo.actorForLoginReference(reference.value());
            case OIDC_SESSION -> restoreSession(reference.value(), now);
        };
        return actor.filter(value -> value.tenantId().equals(tenant) && value.userId().equals(user));
    }

    private Optional<Actor> restoreSession(String primary, Instant now) {
        var repository = sessions.getIfAvailable();
        if (!oidc.enabled() || repository == null) return Optional.empty();
        var ids = jdbc.query("SELECT SESSION_ID FROM AF_HTTP_SESSION WHERE PRIMARY_ID=? AND EXPIRY_TIME>?",
                (row, index) -> row.getString(1), primary, now.toEpochMilli());
        if (ids.isEmpty()) return Optional.empty();
        Session session = repository.findById(ids.get(0));
        if (session == null || session.isExpired()) return Optional.empty();
        Object stored = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (!(stored instanceof SecurityContext context) || context.getAuthentication() == null || !context.getAuthentication().isAuthenticated()
                || !(context.getAuthentication().getPrincipal() instanceof PlatformOidcUser principal)) return Optional.empty();
        try {
            Actor actor = new OidcActorMapper(oidc).restoreSession(principal, now);
            var scopes = logouts.getIfAvailable();
            if (scopes != null) {
                Object order = session.getAttribute(OidcLogoutScopes.SESSION_ORDER);
                if (!(order instanceof Long value)) return Optional.empty();
                scopes.requireActive(principal.idToken(), value);
            }
            return Optional.of(actor);
        } catch (OAuth2AuthenticationException invalid) { return Optional.empty(); }
    }

    private static DomainException unauthenticated() { return new DomainException("UNAUTHENTICATED", "Original authenticated login is no longer available"); }

    /**
     * 引用只能用于内部权限复核，不能恢复浏览器凭据，也不作为公开接口参数。
     * @author owlzhangfq@gmail.com
     */
    public record LoginReference(Kind kind, String value) {
        public LoginReference {
            if (kind == null || value == null || !(kind == Kind.DEMO_LOGIN ? value.matches("[0-9a-f]{64}")
                    : value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) throw new IllegalArgumentException("Invalid deferred authentication reference");
        }
        @Override public String toString() { return "LoginReference[kind=" + kind + "]"; }
    }

    /**
     * 不允许企业与演示模式相互回退。
     * @author owlzhangfq@gmail.com
     */
    public enum Kind { DEMO_LOGIN, OIDC_SESSION }
}
