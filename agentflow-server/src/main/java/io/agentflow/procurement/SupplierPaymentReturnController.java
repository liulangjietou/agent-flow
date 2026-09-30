package io.agentflow.procurement;

import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.common.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 原供应商付款回款取证和明确登记入口，受理查询不等于 ERP 已调整。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/supplier-payments/{id}/returns")
public class SupplierPaymentReturnController {
    private final SupplierPaymentReturnWorkspace workspace;
    private final SupplierPaymentReturnService service;
    private final IdempotencyExecutor idempotency;

    /** 原轮次权限和具名决定继续使用既有认证与幂等事务。 */
    public SupplierPaymentReturnController(SupplierPaymentReturnWorkspace workspace, SupplierPaymentReturnService service, IdempotencyExecutor idempotency) {
        this.workspace = workspace; this.service = service; this.idempotency = idempotency;
    }

    /** 当前累计资金与有界历史分别投影，响应禁止缓存。 */
    @GetMapping
    public ResponseEntity<SupplierPaymentReturnWorkspace.View> read(@PathVariable UUID id, @RequestParam Map<String, String> parameters) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(workspace.read(id, parameters));
    }

    /** 查询仅登记读取意图；幂等回放前同样复核当前财务权限。 */
    @PostMapping("/checks")
    public ResponseEntity<String> queue(@PathVariable UUID id, @Valid @RequestBody SupplierPaymentReturnService.QueryInput input, HttpServletRequest request) {
        requireNoParameters(request); service.requireFinance(id);
        var response = idempotency.execute(request, HttpStatus.ACCEPTED, () -> service.queue(id, input));
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).cacheControl(CacheControl.noStore()).body(response.getBody());
    }

    /** 当前财务单次消费自己的原件，客户端不能提供账号、金额或 ERP 结论。 */
    @PostMapping("/registrations")
    public ResponseEntity<String> register(@PathVariable UUID id, @Valid @RequestBody SupplierPaymentReturnService.RegisterInput input, HttpServletRequest request) {
        requireNoParameters(request); service.requireFinance(id);
        var response = idempotency.execute(request, HttpStatus.ACCEPTED, () -> service.register(id, input));
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).cacheControl(CacheControl.noStore()).body(response.getBody());
    }

    private static void requireNoParameters(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw new DomainException("INVALID_SUPPLIER_PAYMENT_RETURN_QUERY", "Supplier return writes do not accept query parameters");
    }
}
