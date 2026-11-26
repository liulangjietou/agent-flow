package io.agentflow.auth;

import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.common.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 本地用户管理与密码/TOTP MFA 认证接口。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/auth")
public class LocalIdentityController {
    private final LocalIdentityService service;
    private final CurrentActor currentActor;
    private final IdempotencyExecutor idempotency;

    /** 构造本地身份控制器。 */
    public LocalIdentityController(LocalIdentityService service, CurrentActor currentActor, IdempotencyExecutor idempotency) {
        this.service = service;
        this.currentActor = currentActor;
        this.idempotency = idempotency;
    }

    /** 本地账号口令与 TOTP 登录入口（公开路径，返回数据库持久化会话令牌）。 */
    @PostMapping("/local/login")
    public ResponseEntity<AuthController.LoginResponse> login(@Valid @RequestBody LocalLoginRequest request) {
        AuthService.LoginResult result = service.login(
                request.tenantId(), request.username(), request.password(), request.totpCode());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new AuthController.LoginResponse(result.token(), result.actor()));
    }

    /** 管理员列出当前租户本地账号。 */
    @GetMapping("/local-users")
    public ResponseEntity<List<LocalIdentityService.UserView>> listUsers() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.listUsers(currentActor.actor()));
    }

    /** 管理员在本租户创建本地账号。 */
    @PostMapping("/local-users")
    public ResponseEntity<String> createUser(@Valid @RequestBody CreateLocalUserRequest request, HttpServletRequest http) {
        return idempotency.execute(http, HttpStatus.CREATED, () -> service.createUser(
                currentActor.actor(), request.username(), request.displayName(), request.password(), request.roles()));
    }

    /** 管理员修改本地账号启停状态与角色。 */
    @PatchMapping("/local-users/{id}")
    public ResponseEntity<String> updateUser(@PathVariable UUID id, @Valid @RequestBody UpdateLocalUserRequest request,
                                             HttpServletRequest http) {
        return idempotency.execute(http, HttpStatus.OK, () -> service.updateUser(
                currentActor.actor(), id, request.enabled(), request.roles(), request.expectedVersion()));
    }

    /** 管理员重置本地账号口令。 */
    @PostMapping("/local-users/{id}/reset-password")
    public ResponseEntity<String> resetPassword(@PathVariable UUID id, @Valid @RequestBody ResetPasswordRequest request,
                                                HttpServletRequest http) {
        return idempotency.execute(http, HttpStatus.OK, () -> service.resetPassword(
                currentActor.actor(), id, request.newPassword(), request.expectedVersion()));
    }

    /** 当前用户修改自身口令。 */
    @PostMapping("/local/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        service.changeOwnPassword(currentActor.actor(), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /** 当前用户初始化 TOTP MFA 绑定密钥。 */
    @PostMapping("/local/mfa/enroll")
    public ResponseEntity<LocalIdentityService.MfaEnrollment> enrollMfa() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.enrollMfa(currentActor.actor()));
    }

    /** 当前用户校验首个动态码并启用 MFA。 */
    @PostMapping("/local/mfa/enable")
    public ResponseEntity<LocalIdentityService.UserView> enableMfa(@Valid @RequestBody EnableMfaRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.enableMfa(currentActor.actor(), request.totpCode()));
    }

    /** 当前用户核对口令与动态码后关闭 MFA。 */
    @PostMapping("/local/mfa/disable")
    public ResponseEntity<LocalIdentityService.UserView> disableMfa(@Valid @RequestBody DisableMfaRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.disableMfa(currentActor.actor(), request.currentPassword(), request.totpCode()));
    }

    /**
     * 本地登录请求；开启 MFA 时须传入 6 位 totpCode。
     * @author owlzhangfq@gmail.com
     */
    public record LocalLoginRequest(
            @NotBlank @Size(max = 64) String tenantId,
            @NotBlank @Size(max = 128) String username,
            @NotBlank @Size(max = 128) String password,
            @Size(max = 16) String totpCode) { }

    /**
     * 管理员创建本地账号请求。
     * @author owlzhangfq@gmail.com
     */
    public record CreateLocalUserRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 128) String displayName,
            @NotBlank @Size(min = 10, max = 128) String password,
            @NotEmpty Set<String> roles) { }

    /**
     * 管理员修改本地账号启停与角色请求。
     * @author owlzhangfq@gmail.com
     */
    public record UpdateLocalUserRequest(
            boolean enabled,
            @NotEmpty Set<String> roles,
            @NotNull @Positive Long expectedVersion) { }

    /**
     * 管理员重置口令请求。
     * @author owlzhangfq@gmail.com
     */
    public record ResetPasswordRequest(
            @NotBlank @Size(min = 10, max = 128) String newPassword,
            @NotNull @Positive Long expectedVersion) { }

    /**
     * 个人修改口令请求。
     * @author owlzhangfq@gmail.com
     */
    public record ChangePasswordRequest(
            @NotBlank @Size(max = 128) String currentPassword,
            @NotBlank @Size(min = 10, max = 128) String newPassword) { }

    /**
     * 启用 MFA 请求。
     * @author owlzhangfq@gmail.com
     */
    public record EnableMfaRequest(@NotBlank @Size(max = 16) String totpCode) { }

    /**
     * 关闭 MFA 请求。
     * @author owlzhangfq@gmail.com
     */
    public record DisableMfaRequest(
            @NotBlank @Size(max = 128) String currentPassword,
            @Size(max = 16) String totpCode) { }
}
