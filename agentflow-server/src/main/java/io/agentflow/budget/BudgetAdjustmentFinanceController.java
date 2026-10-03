package io.agentflow.budget;

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
 * 预算财务办理共用原认证、字段权限和幂等边界，202 只表示人工决定已保存。
 * @author owlzhangfq@gmail.com
 */
@RestController
public class BudgetAdjustmentFinanceController {
    private final BudgetAdjustmentFinanceAccess access;
    private final BudgetAdjustmentFinanceWorkspace workspace;
    private final BudgetAdjustmentFinanceActions actions;
    private final IdempotencyExecutor idempotency;
    /** 缓存回放前先核对当前权限，敏感结果不进入浏览器持久缓存。 */
    public BudgetAdjustmentFinanceController(BudgetAdjustmentFinanceAccess access, BudgetAdjustmentFinanceWorkspace workspace,
            BudgetAdjustmentFinanceActions actions, IdempotencyExecutor idempotency) {
        this.access = access; this.workspace = workspace; this.actions = actions; this.idempotency = idempotency;
    }
    /** 可读原预算轮次的人才能查看财务执行状态。 */
    @GetMapping("/api/v1/budget-adjustments/{id}/execution")
    public ResponseEntity<BudgetAdjustmentFinanceWorkspace.View> get(@PathVariable UUID id, @RequestParam Map<String, String> parameters) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(workspace.get(id, parameters));
    }
    /** 安全结束的旧指令仍有独立历史，按原轮次权限有界读取。 */
    @GetMapping("/api/v1/budget-adjustments/{id}/execution/history")
    public ResponseEntity<BudgetAdjustmentFinanceWorkspace.Page> history(@PathVariable UUID id, @RequestParam Map<String, String> parameters) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(workspace.history(id, parameters));
    }
    /** 读取意图不携带台账或授权状态，后继由后台访问原目标。 */
    @PostMapping("/api/v1/budget-adjustments/{id}/execution/reviews")
    public ResponseEntity<String> review(@PathVariable UUID id, @Valid @RequestBody BudgetAdjustmentFinanceActions.ReviewInput input, HttpServletRequest request) {
        access.requireFinance(id, input.roundNo()); return noStore(idempotency.execute(request, HttpStatus.ACCEPTED, () -> actions.review(id, input)));
    }
    /** 明确消费本人最新原台账，幂等重放不会登记第二条调整。 */
    @PostMapping("/api/v1/budget-adjustments/{id}/execution/authorizations")
    public ResponseEntity<String> authorize(@PathVariable UUID id, @Valid @RequestBody BudgetAdjustmentFinanceActions.AuthorizeInput input, HttpServletRequest request) {
        access.requireFinance(id, input.roundNo()); return noStore(idempotency.execute(request, HttpStatus.ACCEPTED, () -> actions.authorize(id, input)));
    }
    /** 原号查询、明确重试或安全结束都须再次核对当前原轮次权限。 */
    @PostMapping("/api/v1/budget-adjustment-operations/{id}/actions")
    public ResponseEntity<String> act(@PathVariable UUID id, @Valid @RequestBody BudgetAdjustmentFinanceActions.ActionInput input, HttpServletRequest request) {
        access.requireOperation(id); return noStore(idempotency.execute(request, HttpStatus.ACCEPTED, () -> actions.act(id, input)));
    }
    private static ResponseEntity<String> noStore(ResponseEntity<String> response) {
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).cacheControl(CacheControl.noStore()).body(response.getBody());
    }
}
