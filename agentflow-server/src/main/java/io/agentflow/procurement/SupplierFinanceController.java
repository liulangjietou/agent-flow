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
 * 供应商财务入口复用认证、字段权限及幂等事务，202 仅表示人工意图已经保存。
 * @author owlzhangfq@gmail.com
 */
@RestController
public class SupplierFinanceController {
    private final SupplierPaymentAccess access;
    private final SupplierFinanceWorkspace workspace;
    private final SupplierFinanceActions actions;
    private final IdempotencyExecutor idempotency;

    /** 缓存回放前检查原轮次当前权限，响应不进入浏览器持久缓存。 */
    public SupplierFinanceController(SupplierPaymentAccess access, SupplierFinanceWorkspace workspace, SupplierFinanceActions actions, IdempotencyExecutor idempotency) {
        this.access = access; this.workspace = workspace; this.actions = actions; this.idempotency = idempotency;
    }
    /** 可读原业务的人才能看到办理状态，只有独立财务得到操作能力。 */
    @GetMapping("/api/v1/procurement-payments/{id}/supplier-payment")
    public ResponseEntity<SupplierFinanceWorkspace.View> get(@PathVariable UUID id, @RequestParam Map<String, String> parameters) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(workspace.get(id, parameters));
    }
    /** 保存复核意图，后台读取不能由同步接口直接替代。 */
    @PostMapping("/api/v1/procurement-payments/{id}/supplier-payment/reviews")
    public ResponseEntity<String> review(@PathVariable UUID id, @Valid @RequestBody SupplierFinanceActions.ReviewInput input, HttpServletRequest request) {
        access.requireFinance(id, input.roundNo()); return noStore(idempotency.execute(request, HttpStatus.ACCEPTED, () -> actions.review(id, input)));
    }
    /** 单次消费新鲜证据，原批准、资金事实及操作人由服务端派生。 */
    @PostMapping("/api/v1/procurement-payments/{id}/supplier-payment/authorizations")
    public ResponseEntity<String> authorize(@PathVariable UUID id, @Valid @RequestBody SupplierFinanceActions.AuthorizeInput input, HttpServletRequest request) {
        access.requireFinance(id, input.roundNo()); return noStore(idempotency.execute(request, HttpStatus.ACCEPTED, () -> actions.authorize(id, input)));
    }
    /** 每次恢复都定位同一原授权，不允许换号重付或凭本地状态解除 ERP 预留。 */
    @PostMapping("/api/v1/supplier-payments/{id}/finance-actions")
    public ResponseEntity<String> act(@PathVariable UUID id, @Valid @RequestBody SupplierFinanceActions.ActionInput input, HttpServletRequest request) {
        access.requireAuthorization(id); return noStore(idempotency.execute(request, HttpStatus.ACCEPTED, () -> actions.act(id, input)));
    }
    private static ResponseEntity<String> noStore(ResponseEntity<String> response) {
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).cacheControl(CacheControl.noStore()).body(response.getBody());
    }
}
