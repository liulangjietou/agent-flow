package io.agentflow.finance;

import io.agentflow.api.idempotency.IdempotencyExecutor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;
import java.util.UUID;

/**
 * 财务付款入口独立于出纳能力，读取和回放均以原轮次的当前权限为准。
 * @author owlzhangfq@gmail.com
 */
@RestController
public class FinancePaymentController {
    private final FinancePaymentWorkspace workspace;
    private final FinancePaymentActions actions;
    private final PaymentAccess access;
    private final IdempotencyExecutor idempotency;
    /** 202 回执只确认人工决定已经持久保存。 */
    public FinancePaymentController(FinancePaymentWorkspace workspace, FinancePaymentActions actions, PaymentAccess access, IdempotencyExecutor idempotency) {
        this.workspace = workspace; this.actions = actions; this.access = access; this.idempotency = idempotency;
    }
    /** 申请人只能通过原业务字段权限读取，出纳岗位不扩大本接口权限。 */
    @GetMapping("/api/v1/applications/{id}/payments")
    public ResponseEntity<FinancePaymentWorkspace.View> get(@PathVariable UUID id, @RequestParam Map<String, String> parameters) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(workspace.get(id, parameters));
    }
    /** 财务不能提交账户、金额或替代操作者。 */
    @PostMapping("/api/v1/applications/{id}/payments/authorizations")
    public ResponseEntity<String> authorize(@PathVariable UUID id, @Valid @RequestBody FinancePaymentActions.Authorize input, HttpServletRequest request) {
        access.requireFinance(id, input.roundNo());
        return noStore(idempotency.execute(request, HttpStatus.ACCEPTED, () -> actions.authorize(id, input)));
    }
    /** 原授权的查询与作废不能经幂等记录绕过当前字段权限。 */
    @PostMapping("/api/v1/payments/{id}/finance-actions")
    public ResponseEntity<String> act(@PathVariable UUID id, @Valid @RequestBody FinancePaymentActions.Input input, HttpServletRequest request) {
        access.requireFinanceAuthorization(id);
        return noStore(idempotency.execute(request, HttpStatus.ACCEPTED, () -> actions.act(id, input)));
    }
    private static ResponseEntity<String> noStore(ResponseEntity<String> result) {
        return ResponseEntity.status(result.getStatusCode()).headers(result.getHeaders()).cacheControl(CacheControl.noStore()).body(result.getBody());
    }
    /** 回放前仍核对原轮次财务权限，回执只确认复核意图入库。 */
    @PostMapping("/api/v1/payments/{id}/payee-reviews")
    public ResponseEntity<String> reviewPayee(@PathVariable UUID id, @Valid @RequestBody FinancePaymentActions.ReviewInput input, HttpServletRequest request) {
        access.requireFinanceAuthorization(id);
        return noStore(idempotency.execute(request, HttpStatus.ACCEPTED, () -> actions.reviewPayee(id, input)));
    }
}
