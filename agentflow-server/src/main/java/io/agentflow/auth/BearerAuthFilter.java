package io.agentflow.auth;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.Actor;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.context.SecurityContextHolder;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
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

    /** 创建过滤器。 */
    public BearerAuthFilter(AuthService authService, CurrentActor currentActor, JsonUtil jsonUtil, OidcProperties oidc) {
        this.authService = authService;
        this.currentActor = currentActor;
        this.jsonUtil = jsonUtil;
        this.oidc = oidc;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
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
                    "traceId", java.util.UUID.randomUUID().toString(), "path", request.getRequestURI())));
            return;
        }
        try {
            currentActor.set(actor);
            // 仅认证后缓存完整原始字节，业务写接口用它核对重试；不以固定前缀代替真实 body。
            chain.doFilter(new ContentCachingRequestWrapper(request, Integer.MAX_VALUE), response);
        } finally {
            currentActor.clear();
        }
    }

    private Actor authenticate(HttpServletRequest request) {
        if (oidc.enabled()) {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated()
                    && authentication.getPrincipal() instanceof PlatformOidcUser user) {
                if (user.getIdToken().getExpiresAt().isAfter(Instant.now())) return user.actor();
                var session = request.getSession(false);
                if (session != null) session.invalidate();
                SecurityContextHolder.clearContext();
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
        return (HttpMethod.POST.matches(request.getMethod()) && path.equals("/api/v1/auth/login"))
                || (HttpMethod.GET.matches(request.getMethod()) && path.equals("/api/v1/auth/options"))
                || ((HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod()))
                && PUBLIC_HEALTH_PATHS.contains(path)) || path.equals("/error");
    }
}
