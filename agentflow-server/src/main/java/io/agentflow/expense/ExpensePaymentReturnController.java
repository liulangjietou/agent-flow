package io.agentflow.expense;

import io.agentflow.api.idempotency.IdempotencyExecutor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 原报销付款的银行退回核验及登记入口，不执行退款、重付或资源冲销。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/expense-reports/{id}/payment-return")
public class ExpensePaymentReturnController {
    private final ExpensePaymentReturnWorkspace workspace;
    private final ExpensePaymentReturnService service;
    private final IdempotencyExecutor idempotency;
    public ExpensePaymentReturnController(ExpensePaymentReturnWorkspace workspace, ExpensePaymentReturnService service, IdempotencyExecutor idempotency) {
        this.workspace = workspace; this.service = service; this.idempotency = idempotency;
    }
    /** 原轮次完整字段权限同时约束银行入款与人工登记历史。 */
    @GetMapping
    public ResponseEntity<ExpensePaymentReturnWorkspace.View> read(@PathVariable UUID id, @RequestParam Map<String, String> parameters) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(workspace.read(id, parameters));
    }
    /** 202 仅表示查询意图已经保存，不表示查到或登记了银行退回。 */
    @PostMapping("/checks")
    public ResponseEntity<String> queue(@PathVariable UUID id, @Valid @RequestBody ExpensePaymentReturnService.QueryInput input, HttpServletRequest request) {
        service.authorize(id); var result = idempotency.execute(request, HttpStatus.ACCEPTED, () -> service.queue(id, input));
        return ResponseEntity.status(result.getStatusCode()).headers(result.getHeaders()).cacheControl(CacheControl.noStore()).body(result.getBody());
    }
    /** 回放前重新授权，明确登记只能单次消费当前财务自己的原件查询。 */
    @PostMapping("/registrations")
    public ResponseEntity<String> register(@PathVariable UUID id, @Valid @RequestBody ExpensePaymentReturnService.RegisterInput input, HttpServletRequest request) {
        service.authorize(id); var result = idempotency.execute(request, HttpStatus.ACCEPTED, () -> service.register(id, input));
        return ResponseEntity.status(result.getStatusCode()).headers(result.getHeaders()).cacheControl(CacheControl.noStore()).body(result.getBody());
    }
}
