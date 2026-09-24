package io.agentflow.auth;

import io.agentflow.common.JsonUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.FormPostRedirectStrategy;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.HtmlUtils;

/**
 * 在注销处理器清理会话前核对企业退出意图，协议表单由 Spring 生成。
 * @author owlzhangfq@gmail.com
 */
public final class OidcProviderLogoutFilter extends OncePerRequestFilter {
    static final String PATH = "/api/v1/auth/oidc/logout/enterprise";
    static final String END_SESSION_ENDPOINT = "end_session_endpoint";
    private final boolean available;
    private final JsonUtil json;
    private final String home;
    private final String issuer;
    private final String clientId;
    private final OidcClientInitiatedLogoutSuccessHandler handler;

    /** 只使用启动时验证过的身份源配置和固定前端地址。 */
    public OidcProviderLogoutFilter(ClientRegistrationRepository clients, JsonUtil json, String home) {
        this.available = available(clients);
        this.json = json;
        this.home = home;
        var registration = clients.findByRegistrationId(OidcClientConfiguration.REGISTRATION_ID);
        issuer = registration.getProviderDetails().getIssuerUri();
        clientId = registration.getClientId();
        handler = new OidcClientInitiatedLogoutSuccessHandler(clients);
        handler.setPostLogoutRedirectUri(home);
        handler.setRedirectStrategy(new FormPostRedirectStrategy());
    }

    /** 缺少退出元数据时不向用户提供企业退出能力。 */
    public static boolean available(ClientRegistrationRepository clients) {
        var registration = clients == null ? null : clients.findByRegistrationId(OidcClientConfiguration.REGISTRATION_ID);
        return registration != null && registration.getProviderDetails().getConfigurationMetadata()
                .get(END_SESSION_ENDPOINT) instanceof String;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!PATH.equals(request.getRequestURI())) { chain.doFilter(request, response); return; }
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        if (!"POST".equals(request.getMethod())) {
            response.setHeader("Allow", "POST");
            reject(response, 405, "请从工作台的退出窗口发起企业账号退出。"); return;
        }
        if (!available) { reject(response, 409, "身份服务尚未提供企业退出入口，请返回后选择仅退出平台。"); return; }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof PlatformOidcUser user)) {
            reject(response, 401, "平台会话已经失效，无法确定需要退出的企业账号。请到企业身份服务检查登录状态。"); return;
        }
        // 共享会话可能来自配置更新前；不能把旧身份源的令牌发送给新身份源。
        if (user.getIdToken().getIssuer() == null || !issuer.equals(user.getIdToken().getIssuer().toString())
                || !user.getIdToken().getAudience().contains(clientId)) {
            reject(response, 409, "身份服务配置已经变化，尚未执行企业退出。请返回后选择仅退出平台。"); return;
        }
        String[] actors = request.getParameterValues("actor");
        if (actors == null || actors.length != 1 || !json.write(List.of(user.tenantId(), user.userId())).equals(actors[0])) {
            reject(response, 409, "当前账号与原页面不一致，尚未执行退出。请返回并恢复原账号后重试。"); return;
        }
        chain.doFilter(request, response);
    }

    /** 本地注销完成后以表单 POST 进入身份源；不将身份令牌放进浏览器地址。 */
    public void complete(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException, ServletException {
        handler.onLogoutSuccess(request, response, authentication);
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("text/html;charset=UTF-8");
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        response.getWriter().write("<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>企业退出未完成</title><h1>企业退出未完成</h1><p>" + message
                + "</p><a href=\"" + HtmlUtils.htmlEscape(home) + "\">返回工作台</a></html>");
    }
}
