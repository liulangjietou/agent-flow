package io.agentflow.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * 在原授权请求中记录数据库顺序；保留 Spring 的 state、PKCE、nonce 与一次性回调校验。
 * @author owlzhangfq@gmail.com
 */
public final class LogoutAwareAuthorizationRequestRepository implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
    static final String CALLBACK_ORDER = LogoutAwareAuthorizationRequestRepository.class.getName() + ".loginOrder";
    private final HttpSessionOAuth2AuthorizationRequestRepository delegate = new HttpSessionOAuth2AuthorizationRequestRepository();
    private final OidcLogoutScopes scopes;

    /** 通过组合扩展协议存储，不替换授权请求的安全验证。 */
    public LogoutAwareAuthorizationRequestRepository(OidcLogoutScopes scopes) { this.scopes = scopes; }

    /** 读取仍由原仓储核对请求 state。 */
    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return delegate.loadAuthorizationRequest(request);
    }

    /** 在发送授权请求前绑定已经提交的注销顺序，随共享会话跨实例恢复。 */
    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorization, HttpServletRequest request, HttpServletResponse response) {
        if (authorization != null) {
            long order = scopes.beginLogin();
            authorization = OAuth2AuthorizationRequest.from(authorization)
                    .attributes(values -> values.put(CALLBACK_ORDER, order)).build();
        }
        delegate.saveAuthorizationRequest(authorization, request, response);
    }

    /** 验证并移除原请求后，将顺序交给同一回调的成功处理器，绝不从 URL 参数读取。 */
    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request, HttpServletResponse response) {
        var authorization = delegate.removeAuthorizationRequest(request, response);
        if (authorization != null) request.setAttribute(CALLBACK_ORDER, authorization.getAttribute(CALLBACK_ORDER));
        return authorization;
    }
}
