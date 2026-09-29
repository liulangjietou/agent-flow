package io.agentflow.expense;

import io.agentflow.api.idempotency.IdempotencyExecutor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

/**
 * 结算读取及幂等重试均沿用原轮次字段权限。
 * @author owlzhangfq@gmail.com
 */
@RestController
public class ExpenseSettlementController {
    private final ExpenseSettlementWorkspace workspace;
    private final ExpenseSettlementActions actions;
    private final ExpenseSettlementAccess access;
    private final IdempotencyExecutor idempotency;
    /** 权限先于回放检查，不能通过旧幂等键绕过已撤销的财务范围。 */
    public ExpenseSettlementController(ExpenseSettlementWorkspace workspace, ExpenseSettlementActions actions, ExpenseSettlementAccess access, IdempotencyExecutor idempotency) {
        this.workspace = workspace; this.actions = actions; this.access = access; this.idempotency = idempotency;
    }
    /** 状态响应不允许浏览器或中间代理缓存敏感业务进度。 */
    @GetMapping("/api/v1/expense-reports/{id}/settlement")
    public ResponseEntity<ExpenseSettlementWorkspace.View> get(@PathVariable UUID id, @RequestParam Map<String, String> parameters) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(workspace.get(id, parameters));
    }
    /** 接受明确理由和当前版本，回执不代表核销或预算已经完成。 */
    @PostMapping("/api/v1/expense-reports/{id}/settlement/retry")
    public ResponseEntity<String> retry(@PathVariable UUID id, @Valid @RequestBody ExpenseSettlementActions.Input input, HttpServletRequest request) {
        access.requireFinance(id, input.roundNo());
        var result = idempotency.execute(request, HttpStatus.ACCEPTED, () -> actions.retry(id, input));
        return ResponseEntity.status(result.getStatusCode()).headers(result.getHeaders()).cacheControl(CacheControl.noStore()).body(result.getBody());
    }
}
