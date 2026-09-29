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
 * 独立资源调整的公开办理入口，所有幂等回放先重核原轮次字段和当前财务资格。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/expense-reports/{id}/resource-adjustment")
public class ExpenseResourceAdjustmentController {
    private final ExpenseResourceAdjustmentWorkspace workspace;
    private final ExpenseResourceAdjustmentAccess access;
    private final ExpenseResourceAdjustmentPreparationService preparations;
    private final ExpenseResourceAdjustmentActionService actions;
    private final IdempotencyExecutor idempotency;
    /** 控制器只承担协议、入口校验与幂等响应，财务编排保留在应用服务。 */
    public ExpenseResourceAdjustmentController(ExpenseResourceAdjustmentWorkspace workspace, ExpenseResourceAdjustmentAccess access,
            ExpenseResourceAdjustmentPreparationService preparations, ExpenseResourceAdjustmentActionService actions, IdempotencyExecutor idempotency) {
        this.workspace = workspace; this.access = access; this.preparations = preparations; this.actions = actions; this.idempotency = idempotency;
    }
    /** 来源变化也保留原调整结果与查询入口，不返回完整命令或账户信息。 */
    @GetMapping
    public ResponseEntity<ExpenseResourceAdjustmentWorkspace.View> read(@PathVariable UUID id, @RequestParam Map<String, String> parameters) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(workspace.read(id, parameters));
    }
    /** 仅接受期间读取意图，不代表授权、预算冲正或资源冲回。 */
    @PostMapping("/preparations")
    public ResponseEntity<String> prepare(@PathVariable UUID id, @Valid @RequestBody ExpenseResourceAdjustmentPreparationService.PrepareInput input, HttpServletRequest request) {
        access.requireFinance(id, input.roundNo()); return response(idempotency.execute(request, HttpStatus.ACCEPTED, () -> preparations.prepare(id, input)));
    }
    /** 独立财务明确消费自己的已展示准备，实际预算结果随后由工作器确认。 */
    @PostMapping("/authorizations")
    public ResponseEntity<String> authorize(@PathVariable UUID id, @Valid @RequestBody ExpenseResourceAdjustmentPreparationService.AuthorizeInput input, HttpServletRequest request) {
        access.requireFinance(id, input.roundNo()); return response(idempotency.execute(request, HttpStatus.ACCEPTED, () -> preparations.authorize(id, input)));
    }
    /** 查询、明确重发和资源恢复保留原命令身份，不接受外部状态覆盖字段。 */
    @PostMapping("/actions")
    public ResponseEntity<String> act(@PathVariable UUID id, @Valid @RequestBody ExpenseResourceAdjustmentActionService.OperationInput input, HttpServletRequest request) {
        access.requireFinance(id, input.roundNo()); return response(idempotency.execute(request, HttpStatus.ACCEPTED, () -> actions.act(id, input)));
    }
    /** 确定无副作用的调整才可安全结束，原授权和历史始终保留。 */
    @PostMapping("/retirements")
    public ResponseEntity<String> retire(@PathVariable UUID id, @Valid @RequestBody ExpenseResourceAdjustmentActionService.RetireInput input, HttpServletRequest request) {
        access.requireFinance(id, input.roundNo()); return response(idempotency.execute(request, HttpStatus.ACCEPTED, () -> actions.retire(id, input)));
    }
    private static ResponseEntity<String> response(ResponseEntity<String> result) {
        return ResponseEntity.status(result.getStatusCode()).headers(result.getHeaders()).cacheControl(CacheControl.noStore()).body(result.getBody());
    }
}
