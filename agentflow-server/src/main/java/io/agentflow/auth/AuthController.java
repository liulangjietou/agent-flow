package io.agentflow.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开发环境认证接口，生产接入 OIDC 后保留同一响应契约。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService authService;

    /** 创建控制器。 */
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 登录。 */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        AuthService.LoginResult result = authService.login(request.tenantId(), request.username(), request.password());
        return new LoginResponse(result.token(), result.actor());
    }

    /** 返回当前登录主体。 */
    @GetMapping("/me")
    public AuthService.LoginResult me(@RequestHeader("Authorization") String authorization) {
        return new AuthService.LoginResult(null, authService.authenticate(token(authorization)));
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
}
