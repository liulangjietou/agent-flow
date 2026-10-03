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
 * 部分调整的公开入口在幂等回放前重核当前独立财务与原轮次字段权限。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/expense-reports/{id}/partial-adjustments")
public class ExpensePartialAdjustmentController {
    private final ExpensePartialAdjustmentWorkspace workspace;
    private final ExpenseResourceAdjustmentAccess access;
    private final ExpensePartialAdjustmentInitiation initiation;
    private final ExpensePartialAdjustmentDecisions decisions;
    private final ExpensePartialAdjustmentActions actions;
    private final ExpensePartialAdjustmentDisputes disputes;
    private final IdempotencyExecutor idempotency;
    /** 协议入口只做校验、当前权限与幂等响应，财务编排留在应用服务。 */
    public ExpensePartialAdjustmentController(ExpensePartialAdjustmentWorkspace workspace, ExpenseResourceAdjustmentAccess access, ExpensePartialAdjustmentInitiation initiation,
            ExpensePartialAdjustmentDecisions decisions, ExpensePartialAdjustmentActions actions, ExpensePartialAdjustmentDisputes disputes, IdempotencyExecutor idempotency) {
        this.workspace = workspace; this.access = access; this.initiation = initiation; this.decisions = decisions; this.actions = actions; this.disputes = disputes; this.idempotency = idempotency;
    }
    /** 原轮次读取独立于办理资格，敏感财务字段和个人准备分别控制。 */
    @GetMapping
    public ResponseEntity<ExpensePartialAdjustmentWorkspace.View> read(@PathVariable UUID id, @RequestParam Map<String, String> parameters) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(workspace.read(id, parameters));
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
    /** 财务差额查询、原号重发及当前来源确认各自表达明确意图。 */
    @PostMapping("/actions")
    public ResponseEntity<String> act(@PathVariable UUID id, @Valid @RequestBody ExpensePartialAdjustmentActions.OperationInput input, HttpServletRequest request) {
        access.requireFinance(id, input.roundNo()); return response(idempotency.execute(request, HttpStatus.ACCEPTED, () -> actions.act(id, input)));
    }
    /** 活动调整的原件恢复只查询实际原号，不放开初始意图的互斥。 */
    @PostMapping("/source-queries")
    public ResponseEntity<String> queryOriginals(@PathVariable UUID id, @Valid @RequestBody ExpensePartialAdjustmentActions.SourceQueryInput input, HttpServletRequest request) {
        access.requireFinance(id, input.roundNo()); return response(idempotency.execute(request, HttpStatus.ACCEPTED, () -> actions.queryOriginals(id, input)));
    }
    /** 无效果的原调整才能明确结束，幂等回放前仍复核当前权限。 */
    @PostMapping("/retirements")
    public ResponseEntity<String> retire(@PathVariable UUID id, @Valid @RequestBody ExpensePartialAdjustmentActions.RetireInput input, HttpServletRequest request) {
        access.requireFinance(id, input.roundNo()); return response(idempotency.execute(request, HttpStatus.ACCEPTED, () -> actions.retire(id, input)));
    }
    /** 每次具名决定只采用当前一侧候选，成功幂等回放仍要求当前独立财务和原字段权限。 */
    @PostMapping("/disputes")
    public ResponseEntity<String> resolve(@PathVariable UUID id, @Valid @RequestBody ExpensePartialAdjustmentDisputes.Input input, HttpServletRequest request) {
        access.requireFinance(id, input.roundNo()); return response(idempotency.execute(request, HttpStatus.ACCEPTED, () -> disputes.resolve(id, input)));
    }
    private static ResponseEntity<String> response(ResponseEntity<String> value) {
        return ResponseEntity.status(value.getStatusCode()).headers(value.getHeaders()).cacheControl(CacheControl.noStore()).body(value.getBody());
    }
}
