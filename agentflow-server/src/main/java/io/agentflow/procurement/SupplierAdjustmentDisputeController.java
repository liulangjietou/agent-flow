package io.agentflow.procurement;

import io.agentflow.api.idempotency.IdempotencyExecutor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 原 ERP 调整争议的最小读取及明确裁决入口，幂等回放前重新验证权限。
 * @author owlzhangfq@gmail.com
 */
@RestController
public class SupplierAdjustmentDisputeController {
    private final SupplierAdjustmentDisputeService service;
    private final SupplierAdjustmentAccess access;
    private final IdempotencyExecutor idempotency;
    /** 当前权限与幂等保存复用既有结算基础。 */
    public SupplierAdjustmentDisputeController(SupplierAdjustmentDisputeService service, SupplierAdjustmentAccess access, IdempotencyExecutor idempotency) {
        this.service = service; this.access = access; this.idempotency = idempotency;
    }
    /** 原调整事实和候选分开显示，响应禁止缓存。 */
    @GetMapping("/api/v1/supplier-adjustments/{id}/dispute")
    public ResponseEntity<SupplierAdjustmentDisputeService.View> read(@PathVariable UUID id, @RequestParam Map<String, String> parameters) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.read(id, parameters));
    }
    /** 裁决只采用原号近期终态，不能注入金额或凭证事实。 */
    @PostMapping("/api/v1/supplier-adjustments/{id}/dispute/resolutions")
    public ResponseEntity<String> resolve(@PathVariable UUID id, @Valid @RequestBody SupplierAdjustmentDisputeService.ResolveInput input, HttpServletRequest request) {
        access.requireAdjustment(id);
        if (request.getQueryString() != null) throw new io.agentflow.common.DomainException("INVALID_SUPPLIER_ADJUSTMENT_DISPUTE_QUERY", "Supplier adjustment resolution does not accept query parameters");
        var response = idempotency.execute(request, HttpStatus.ACCEPTED, () -> service.resolve(id, input));
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).cacheControl(CacheControl.noStore()).body(response.getBody());
    }
}
