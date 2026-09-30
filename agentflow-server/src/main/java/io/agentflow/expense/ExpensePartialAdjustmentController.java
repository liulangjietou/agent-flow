package io.agentflow.expense;

import io.agentflow.api.idempotency.IdempotencyExecutor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 部分调整的公开入口在幂等回放前重核当前独立财务与原轮次字段权限。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/expense-reports/{id}/partial-adjustments")
public class ExpensePartialAdjustmentController {
    private final ExpenseResourceAdjustmentAccess access;
    private final ExpensePartialAdjustmentInitiation initiation;
    private final ExpensePartialAdjustmentDecisions decisions;
    private final IdempotencyExecutor idempotency;
    /** 协议入口只做校验、当前权限与幂等响应，财务编排留在应用服务。 */
    public ExpensePartialAdjustmentController(ExpenseResourceAdjustmentAccess access, ExpensePartialAdjustmentInitiation initiation,
            ExpensePartialAdjustmentDecisions decisions, IdempotencyExecutor idempotency) {
        this.access = access; this.initiation = initiation; this.decisions = decisions; this.idempotency = idempotency;
    }
    /** 初始原件刷新沿原号只读查询，绝不触发付款或凭证重发。 */
    @PostMapping("/original-queries")
    public ResponseEntity<String> refresh(@PathVariable UUID id, @Valid @RequestBody ExpensePartialAdjustmentInitiation.RefreshInput input, HttpServletRequest request) {
        access.requireFinance(id, input.roundNo()); return response(idempotency.execute(request, HttpStatus.ACCEPTED, () -> initiation.refresh(id, input)));
    }
    /** 创建固定金额及完整回款意图，两个财务写入仍等待各自明确授权。 */
    @PostMapping
    public ResponseEntity<String> create(@PathVariable UUID id, @Valid @RequestBody ExpensePartialAdjustmentInitiation.CreateInput input, HttpServletRequest request) {
        access.requireFinance(id, input.roundNo()); return response(idempotency.execute(request, HttpStatus.ACCEPTED, () -> initiation.create(id, input)));
    }
    /** 本人准备一侧的最新原件及期间，不自动产生写命令。 */
    @PostMapping("/preparations")
    public ResponseEntity<String> prepare(@PathVariable UUID id, @Valid @RequestBody ExpensePartialAdjustmentDecisions.PrepareInput input, HttpServletRequest request) {
        access.requireFinance(id, input.roundNo()); return response(idempotency.execute(request, HttpStatus.ACCEPTED, () -> decisions.prepare(id, input)));
    }
    /** 明确消费本人就绪修订，预算和挂账可以先后独立授权。 */
    @PostMapping("/authorizations")
    public ResponseEntity<String> authorize(@PathVariable UUID id, @Valid @RequestBody ExpensePartialAdjustmentDecisions.AuthorizeInput input, HttpServletRequest request) {
        access.requireFinance(id, input.roundNo()); return response(idempotency.execute(request, HttpStatus.ACCEPTED, () -> decisions.authorize(id, input)));
    }
    private static ResponseEntity<String> response(ResponseEntity<String> value) {
        return ResponseEntity.status(value.getStatusCode()).headers(value.getHeaders()).cacheControl(CacheControl.noStore()).body(value.getBody());
    }
}
