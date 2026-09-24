package io.agentflow.auth;

import io.agentflow.common.JsonUtil;
import io.agentflow.common.DomainException;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 身份源直连入口，以签名通知认证，不依赖浏览器 Cookie、CSRF 或租户请求头。
 * @author owlzhangfq@gmail.com
 */
public final class OidcBackchannelLogoutFilter extends OncePerRequestFilter {
    static final String PATH = "/api/v1/auth/oidc/backchannel-logout/enterprise";
    private static final int MAX_BODY_BYTES = 32768;
    private final JwtDecoder decoder;
    private final OidcLogoutScopes scopes;
    private final JsonUtil json;

    /** 过滤器仅装入企业安全链，避免被 Servlet 容器重复注册。 */
    public OidcBackchannelLogoutFilter(JwtDecoder decoder, OidcLogoutScopes scopes, JsonUtil json) {
        this.decoder = decoder;
        this.scopes = scopes;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!PATH.equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        if (!"POST".equals(request.getMethod())) {
            response.setHeader("Allow", "POST");
            error(response, 405, "method_not_allowed");
            return;
        }
        try {
            String token = logoutToken(request);
            // 这里只拒绝结构不合法的输入；任何状态变更仍必须经过完整验签和协议校验。
            OidcLogoutTokenValidator.requireShape(json.map(SignedJWT.parse(token).getPayload().toString()));
            scopes.revoke(decoder.decode(token));
            response.setStatus(HttpServletResponse.SC_OK);
        } catch (IllegalArgumentException | BadJwtException | ParseException | DomainException invalid) {
            error(response, 400, "invalid_logout_token");
        } catch (JwtException | DataAccessException unavailable) {
            // 公钥或共享数据库不可用时不返回成功，身份源可以重试；不泄露令牌或基础设施细节。
            error(response, 503, "logout_unavailable");
        }
    }

    private String logoutToken(HttpServletRequest request) throws IOException {
        if (request.getContentType() == null || !MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(
                MediaType.parseMediaType(request.getContentType()))) throw new IllegalArgumentException();
        byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) throw new IllegalArgumentException();
        String token = null;
        for (String part : new String(bytes, StandardCharsets.UTF_8).split("&")) {
            String[] pair = part.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            if (!"logout_token".equals(key)) continue;
            if (token != null || pair.length != 2) throw new IllegalArgumentException();
            token = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
        }
        if (token == null || token.isBlank()) throw new IllegalArgumentException();
        return token;
    }

    private void error(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(json.write(Map.of("error", code)));
    }
}
