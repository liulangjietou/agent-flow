package io.agentflow.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.agentflow.common.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * 开发环境认证接口，生产接入 OIDC 后保留同一响应契约。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService authService;
    private final CurrentActor currentActor;
    private final OidcProperties oidc;
    private final boolean demoEnabled;
    private final ObjectProvider<ClientRegistrationRepository> clients;

    /** 创建控制器。 */
    public AuthController(AuthService authService, CurrentActor currentActor, OidcProperties oidc,
                          ObjectProvider<ClientRegistrationRepository> clients,
                          @Value("${agentflow.auth.demo-enabled:false}") boolean demoEnabled) {
        this.authService = authService;
        this.currentActor = currentActor;
        this.oidc = oidc;
        this.demoEnabled = demoEnabled;
        this.clients = clients;
    }

    /** 前端按服务端实际配置展示登录入口；CSRF 令牌只供当前同源会话使用。 */
    @GetMapping("/options")
    public AuthOptions options(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        CsrfToken csrf = oidc.enabled() ? (CsrfToken) request.getAttribute(CsrfToken.class.getName()) : null;
        return new AuthOptions(oidc.enabled() ? "OIDC" : demoEnabled ? "DEMO" : "UNCONFIGURED",
                oidc.enabled() ? OidcClientConfiguration.AUTHORIZATION_BASE + "/" + OidcClientConfiguration.REGISTRATION_ID : null,
                csrf == null ? null : csrf.getHeaderName(), csrf == null ? null : csrf.getToken(),
                csrf == null ? null : csrf.getParameterName(),
                oidc.enabled() && OidcProviderLogoutFilter.available(clients.getIfAvailable()) ? OidcProviderLogoutFilter.PATH : null);
    }

    /** 登录。 */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        AuthService.LoginResult result = authService.login(request.tenantId(), request.username(), request.password());
        return new LoginResponse(result.token(), result.actor());
    }

    /** 返回当前登录主体。 */
    @GetMapping("/me")
    public AuthService.LoginResult me() {
        return new AuthService.LoginResult(null, currentActor.actor());
    }

    /** 注销。 */
    @PostMapping("/logout")
    public void logout(@RequestHeader("Authorization") String authorization) {
        authService.logout(token(authorization));
    }

    private String token(String authorization) {
        return authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : authorization;
    }

    /**
     * 登录请求。
     * @author owlzhangfq@gmail.com
     */
    public record LoginRequest(@NotBlank String tenantId, @NotBlank String username, @NotBlank String password) { }
    /**
     * 登录响应。
     * @author owlzhangfq@gmail.com
     */
    public record LoginResponse(String token, io.agentflow.common.Actor user) { }
    /** 服务端登录模式与当前会话防伪令牌。
     * @author owlzhangfq@gmail.com
     */
    public record AuthOptions(String mode, String loginUrl, String csrfHeader, String csrfToken,
                              String csrfParameter, String providerLogoutUrl) { }
}
