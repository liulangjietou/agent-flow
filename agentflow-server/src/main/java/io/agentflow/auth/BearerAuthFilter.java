package io.agentflow.auth;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/** 将 Bearer 认证主体写入请求线程上下文，并保证请求结束后清理。 */
@Component
public class BearerAuthFilter extends OncePerRequestFilter {
    private final AuthService authService;
    private final CurrentActor currentActor;
    private final JsonUtil jsonUtil;

    /** 创建过滤器。 */
    public BearerAuthFilter(AuthService authService, CurrentActor currentActor, JsonUtil jsonUtil) {
        this.authService = authService;
        this.currentActor = currentActor;
        this.jsonUtil = jsonUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isPublic(request)) {
            chain.doFilter(request, response);
            return;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            writeUnauthorized(response, request, "Authentication is required");
            return;
        }
        final io.agentflow.common.Actor actor;
        try {
            actor = authService.authenticate(authorization.substring(7));
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
            chain.doFilter(request, response);
        } finally {
            currentActor.clear();
        }
    }

    private void writeUnauthorized(HttpServletResponse response, HttpServletRequest request, String message)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(jsonUtil.write(Map.of(
                "code", "UNAUTHENTICATED", "message", message,
                "traceId", java.util.UUID.randomUUID().toString(), "path", request.getRequestURI())));
    }

    private boolean isPublic(HttpServletRequest request) {
        String path = request.getRequestURI();
        return (HttpMethod.POST.matches(request.getMethod()) && path.equals("/api/v1/auth/login"))
                || path.equals("/actuator/health") || path.equals("/error");
    }
}
