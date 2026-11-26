package io.agentflow.auth;

import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.common.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 多租户动态 OIDC 身份提供方管理接口（仅当前租户 ADMIN）。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/auth/oidc-providers")
public class TenantOidcConfigController {
    private final TenantOidcConfigService service;
    private final CurrentActor currentActor;
    private final IdempotencyExecutor idempotency;

    /** 构造多租户 OIDC 控制器。 */
    public TenantOidcConfigController(TenantOidcConfigService service, CurrentActor currentActor, IdempotencyExecutor idempotency) {
        this.service = service;
        this.currentActor = currentActor;
        this.idempotency = idempotency;
    }

    /** 列出本租户 OIDC 提供方配置（不含明文密钥）。 */
    @GetMapping
    public ResponseEntity<List<TenantOidcConfigService.ProviderView>> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.list(currentActor.actor()));
    }

    /** 新增或更新租户 OIDC 提供方配置。 */
    @PutMapping
    public ResponseEntity<String> upsert(@Valid @RequestBody UpsertOidcProviderRequest request, HttpServletRequest http) {
        return idempotency.execute(http, HttpStatus.OK, () -> service.upsert(
                currentActor.actor(), request.providerKey(), request.displayName(), request.issuerUri(),
                request.clientId(), request.clientSecret(), request.scopes(), request.enabled(), request.expectedVersion()));
    }

    /** 删除本租户 OIDC 提供方配置。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(currentActor.actor(), id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /**
     * 租户 OIDC 提供方维护请求。
     * @author owlzhangfq@gmail.com
     */
    public record UpsertOidcProviderRequest(
            @NotBlank @Size(max = 64) String providerKey,
            @NotBlank @Size(max = 128) String displayName,
            @NotBlank @Size(max = 512) String issuerUri,
            @NotBlank @Size(max = 256) String clientId,
            @Size(max = 1024) String clientSecret,
            @Size(max = 512) String scopes,
            boolean enabled,
            Long expectedVersion) { }
}
