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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;
import java.util.UUID;

/**
 * 出纳专用付款目录和办理入口，角色不隐式授予审批、财务授权或完整申请读取。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/cashier/payments")
public class CashierPaymentController {
    private final CashierPaymentWorkspace workspace;
    private final CashierPaymentActions actions;
    private final IdempotencyExecutor idempotency;
    /** 写入先复查当前角色、任职和操作人，再处理原请求回放。 */
    public CashierPaymentController(CashierPaymentWorkspace workspace, CashierPaymentActions actions, IdempotencyExecutor idempotency) {
        this.workspace = workspace; this.actions = actions; this.idempotency = idempotency;
    }
    /** SQL 内按当前法人任职分页过滤。 */
    @GetMapping
    public ResponseEntity<CashierPaymentWorkspace.Page> list(@RequestParam Map<String, String> parameters) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(workspace.list(parameters));
    }
    /** 当前任职法人及历史已固定账户均从受控事实读取，响应不含原始账户引用。 */
    @GetMapping("/filter-options")
    public ResponseEntity<CashierPaymentWorkspace.FilterOptions> filterOptions(@RequestParam Map<String, String> parameters) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(workspace.filterOptions(parameters));
    }
    /** 不返回付款命令、完整账号或完整申请。 */
    @GetMapping("/{id}")
    public ResponseEntity<CashierPaymentWorkspace.View> get(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(workspace.get(id));
    }
    /** 外部目录只供选择，本次读取不产生付款授权或执行事实。 */
    @GetMapping("/{id}/accounts")
    public ResponseEntity<CashierPaymentWorkspace.Accounts> accounts(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(workspace.accountOptions(id));
    }
    /** 同一幂等请求保留原账户意图，读取失败由后台恢复。 */
    @PostMapping("/{id}/actions")
    public ResponseEntity<String> act(@PathVariable UUID id, @Valid @RequestBody CashierPaymentActions.Input input, HttpServletRequest request) {
        actions.requireAccess(id, input.action());
        var result = idempotency.execute(request, HttpStatus.ACCEPTED, () -> actions.act(id, input));
        return ResponseEntity.status(result.getStatusCode()).headers(result.getHeaders()).cacheControl(CacheControl.noStore()).body(result.getBody());
    }
}
