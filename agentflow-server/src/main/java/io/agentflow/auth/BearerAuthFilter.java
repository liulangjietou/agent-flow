package io.agentflow.auth;

import io.agentflow.signature.SignatureCallbackVerifier;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.Actor;
import io.agentflow.observability.RequestTrace;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.callback.PaymentCallbackVerifier;
import io.agentflow.event.EventIngressVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.util.Map;

/**
 * 将演示 Bearer 或已验证 OIDC 会话主体写入请求线程上下文，并保证请求结束后清理。
 * @author owlzhangfq@gmail.com
 */
@Component
public class BearerAuthFilter extends OncePerRequestFilter {
    private static final int MAX_ACTOR_HEADER_LENGTH = 4096;
    private static final String ACTOR_HEADER = "X-AgentFlow-Actor";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Set<String> PUBLIC_HEALTH_PATHS = Set.of(
            "/actuator/health", "/actuator/health/readiness", "/actuator/health/liveness");
    private final AuthService authService;
    private final CurrentActor currentActor;
    private final JsonUtil jsonUtil;
    private final OidcProperties oidc;
    private final OidcActorMapper oidcActors;
    private final ObjectProvider<OidcLogoutScopes> logoutScopes;
    private final MetricsScrapeAuthentication metrics;

    /** 创建过滤器。 */
    public BearerAuthFilter(AuthService authService, CurrentActor currentActor, JsonUtil jsonUtil, OidcProperties oidc,
                           ObjectProvider<OidcLogoutScopes> logoutScopes, MetricsScrapeAuthentication metrics) {
        this.authService = authService;
        this.currentActor = currentActor;
        this.jsonUtil = jsonUtil;
        this.oidc = oidc;
        this.oidcActors = new OidcActorMapper(oidc);
        this.logoutScopes = logoutScopes;
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (metrics.matches(request)) {
            if (metrics.authorize(request, response)) chain.doFilter(request, response);
            return;
        }
        if (isPublic(request)) {
            chain.doFilter(request, response);
            return;
        }
        final Actor actor;
        try {
            actor = authenticate(request);
            if (oidc.enabled() && (request.getHeader(ACTOR_HEADER) != null
                    || !SAFE_METHODS.contains(request.getMethod()))) {
                String binding = request.getHeader(ACTOR_HEADER);
                String expected = jsonUtil.write(List.of(actor.tenantId(), actor.userId()));
                try {
                    if (binding == null || binding.length() > MAX_ACTOR_HEADER_LENGTH || !expected.equals(URLDecoder.decode(binding,
                            StandardCharsets.UTF_8))) throw new IllegalArgumentException();
                } catch (IllegalArgumentException exception) {
                    throw new DomainException("UNAUTHENTICATED", "Session identity changed; restore the original account before retrying");
                }
            }
        } catch (DomainException exception) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(jsonUtil.write(Map.of(
                    "code", "UNAUTHENTICATED", "message", exception.getMessage(),
                    "traceId", RequestTrace.id(request), "path", request.getRequestURI())));
            return;
        } catch (DataAccessException unavailable) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write(jsonUtil.write(Map.of("code", "AUTHENTICATION_UNAVAILABLE",
                    "message", "Authentication storage is unavailable; retry later",
                    "traceId", RequestTrace.id(request), "path", request.getRequestURI())));
            return;
        }
        RequestTrace.authenticated(request, actor.tenantId());
        try (var scope = RequestTrace.context(request).open()) {
            currentActor.set(actor);
            // 仅认证后缓存完整原始字节，业务写接口用它核对重试；不以固定前缀代替真实 body。
            boolean fileUpload = HttpMethod.PUT.matches(request.getMethod()) && (request.getRequestURI()
                    .matches("/api/v1/applications/[0-9a-f-]{36}/attachments/[0-9a-f-]{36}/content")
                    || request.getRequestURI().matches("/api/v1/invoices/[0-9a-f-]{36}/content"));
            // 二进制内容由附件服务流式限长并核对登记摘要，不复制整份文件到 JSON 幂等缓存。
            chain.doFilter(fileUpload ? request : new ContentCachingRequestWrapper(request, Integer.MAX_VALUE), response);
        } finally {
            currentActor.clear();
        }
    }

    private Actor authenticate(HttpServletRequest request) {
        if (oidc.enabled()) {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated()
                    && authentication.getPrincipal() instanceof PlatformOidcUser user) {
                try {
                    Actor actor = oidcActors.restoreSession(user, Instant.now());
                    var revocations = logoutScopes.getIfAvailable();
                    if (revocations != null) {
                        var session = request.getSession(false);
                        Long order = session == null ? null : (Long) session.getAttribute(OidcLogoutScopes.SESSION_ORDER);
                        revocations.requireActive(user.getIdToken(), order);
                    }
                    return actor;
                } catch (OAuth2AuthenticationException exception) {
                    var session = request.getSession(false);
                    if (session != null) session.invalidate();
                    SecurityContextHolder.clearContext();
                }
            }
            throw new DomainException("UNAUTHENTICATED", "Enterprise session is missing or expired");
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new DomainException("UNAUTHENTICATED", "Authentication is required");
        }
        return authService.authenticate(authorization.substring(7));
    }

    private boolean isPublic(HttpServletRequest request) {
        String path = request.getRequestURI();
        return PaymentCallbackVerifier.matches(request) || EventIngressVerifier.matches(request) || SignatureCallbackVerifier.matches(request) || (HttpMethod.POST.matches(request.getMethod()) && path.equals("/api/v1/auth/login"))
                || (HttpMethod.GET.matches(request.getMethod()) && path.equals("/api/v1/auth/options"))
                || ((HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod()))
                && PUBLIC_HEALTH_PATHS.contains(path)) || path.equals("/error");
    }
}
