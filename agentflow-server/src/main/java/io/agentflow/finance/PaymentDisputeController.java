package io.agentflow.finance;

import io.agentflow.api.idempotency.IdempotencyExecutor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

/**
 * 人工裁决和普通查询分属独立接口，幂等回放仍须当前原轮次财务权限。
 * @author owlzhangfq@gmail.com
 */
@RestController
public class PaymentDisputeController {
    private final PaymentAccess access;
    private final PaymentDisputeService service;
    private final IdempotencyExecutor idempotency;
    /** 202 表示本地裁决保存，不代表发生新的银行付款。 */
    public PaymentDisputeController(PaymentAccess access, PaymentDisputeService service, IdempotencyExecutor idempotency) { this.access = access; this.service = service; this.idempotency = idempotency; }
    /** 认证角色和原字段读取权限先于幂等回执检查。 */
    @PostMapping("/api/v1/payments/{id}/dispute-resolutions")
    public ResponseEntity<String> resolve(@PathVariable UUID id, @Valid @RequestBody PaymentDisputeService.Input input, HttpServletRequest request) {
        access.requireFinanceAuthorization(id);
        var result = idempotency.execute(request, HttpStatus.ACCEPTED, () -> service.resolve(id, input));
        return ResponseEntity.status(result.getStatusCode()).headers(result.getHeaders()).cacheControl(CacheControl.noStore()).body(result.getBody());
    }
}
