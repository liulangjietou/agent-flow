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
 * 原供应商应付调整入口，202 表示财务决定已保存，查询与回放始终重新验证当前权限。
 * @author owlzhangfq@gmail.com
 */
@RestController
public class SupplierAdjustmentController {
    private final SupplierAdjustmentAccess access;
    private final SupplierAdjustmentWorkspace workspace;
    private final SupplierAdjustmentActions actions;
    private final IdempotencyExecutor idempotency;

    /** 原字段权限、持久幂等及人工决定共用已有认证基础。 */
    public SupplierAdjustmentController(SupplierAdjustmentAccess access, SupplierAdjustmentWorkspace workspace, SupplierAdjustmentActions actions, IdempotencyExecutor idempotency) {
        this.access = access; this.workspace = workspace; this.actions = actions; this.idempotency = idempotency;
    }
    /** 原银行的准备、调整历史及本地完成状态分别返回，资金响应禁止缓存。 */
    @GetMapping("/api/v1/supplier-payments/{id}/adjustments")
    public ResponseEntity<SupplierAdjustmentWorkspace.View> get(@PathVariable UUID id, @RequestParam Map<String, String> parameters) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(workspace.get(id, parameters));
    }
    /** 保存明确日期和原银行版本，后台异步复查不会在请求事务内调用 ERP。 */
    @PostMapping("/api/v1/supplier-payments/{id}/adjustment-preparations")
    public ResponseEntity<String> prepare(@PathVariable UUID id, @Valid @RequestBody SupplierAdjustmentActions.PrepareInput input, HttpServletRequest request) {
        access.requireFinance(id); requireNoQuery(request); return noStore(idempotency.execute(request, HttpStatus.ACCEPTED, () -> actions.prepare(id, input)));
    }
    /** 明确查询、原号重试或安全结束，不能借幂等回放绕过人员及字段权限变化。 */
    @PostMapping("/api/v1/supplier-adjustments/{id}/finance-actions")
    public ResponseEntity<String> act(@PathVariable UUID id, @Valid @RequestBody SupplierAdjustmentActions.ActionInput input, HttpServletRequest request) {
        access.requireAdjustment(id); requireNoQuery(request); return noStore(idempotency.execute(request, HttpStatus.ACCEPTED, () -> actions.act(id, input)));
    }
    private static void requireNoQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) throw new io.agentflow.common.DomainException("INVALID_SUPPLIER_ADJUSTMENT_QUERY", "Supplier adjustment decisions do not accept query parameters");
    }
    private static ResponseEntity<String> noStore(ResponseEntity<String> response) {
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).cacheControl(CacheControl.noStore()).body(response.getBody());
    }
}
