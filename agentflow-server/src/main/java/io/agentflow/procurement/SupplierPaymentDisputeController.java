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
 * 原供应商银行争议只通过当前权限和幂等人工决定办理，响应禁止缓存。
 * @author owlzhangfq@gmail.com
 */
@RestController
public class SupplierPaymentDisputeController {
    private final SupplierPaymentDisputeService service;
    private final IdempotencyExecutor idempotency;
    /** 幂等结果回放之前仍要检查原轮次当前权限。 */
    public SupplierPaymentDisputeController(SupplierPaymentDisputeService service, IdempotencyExecutor idempotency) { this.service = service; this.idempotency = idempotency; }

    /** 当前银行回执、候选和历史决定分别投影。 */
    @GetMapping("/api/v1/supplier-payments/{id}/dispute")
    public ResponseEntity<SupplierPaymentDisputeService.View> read(@PathVariable UUID id, @RequestParam Map<String, String> parameters) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.read(id, parameters));
    }
    /** 财务查询只入原银行只读队列，不发送新的资金指令。 */
    @PostMapping("/api/v1/supplier-payments/{id}/dispute/queries")
    public ResponseEntity<String> query(@PathVariable UUID id, @Valid @RequestBody SupplierPaymentDisputeService.QueryInput input, HttpServletRequest request) {
        service.requireFinance(id); return noStore(idempotency.execute(request, HttpStatus.ACCEPTED, () -> service.query(id, input)));
    }
    /** 显式裁决采用原号近期终态，不能由客户端定义金融事实。 */
    @PostMapping("/api/v1/supplier-payments/{id}/dispute/resolutions")
    public ResponseEntity<String> resolve(@PathVariable UUID id, @Valid @RequestBody SupplierPaymentDisputeService.ResolveInput input, HttpServletRequest request) {
        service.requireFinance(id); return noStore(idempotency.execute(request, HttpStatus.ACCEPTED, () -> service.resolve(id, input)));
    }
    private static ResponseEntity<String> noStore(ResponseEntity<String> response) {
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).cacheControl(CacheControl.noStore()).body(response.getBody());
    }
}
