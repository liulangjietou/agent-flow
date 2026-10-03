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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 供应商付款出纳专用入口，不通过管理员或财务角色扩大数据范围。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/cashier/supplier-payments")
public class SupplierCashierController {
    private final SupplierCashierWorkspace workspace;
    private final SupplierCashierActions actions;
    private final IdempotencyExecutor idempotency;
    /** 回放之前校验当前访问资格，全部资金响应禁止缓存。 */
    public SupplierCashierController(SupplierCashierWorkspace workspace, SupplierCashierActions actions, IdempotencyExecutor idempotency) {
        this.workspace = workspace; this.actions = actions; this.idempotency = idempotency;
    }
    /** 当前法人内的最小付款目录。 */
    @GetMapping
    public ResponseEntity<SupplierCashierWorkspace.Page> list(@RequestParam Map<String, String> parameters) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(workspace.list(parameters));
    }
    /** 已知原授权仍须满足当前租户、角色和法人任职。 */
    @GetMapping("/{id}")
    public ResponseEntity<SupplierCashierWorkspace.View> get(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(workspace.get(id));
    }
    /** 目录只是供人工选择，不产生银行指令或授权。 */
    @GetMapping("/{id}/accounts")
    public ResponseEntity<SupplierCashierWorkspace.Accounts> accounts(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(workspace.accountOptions(id));
    }
    /** 202 表示办理意图已保存，银行结果通过后续原交易查询确认。 */
    @PostMapping("/{id}/actions")
    public ResponseEntity<String> act(@PathVariable UUID id, @Valid @RequestBody SupplierCashierActions.Input input, HttpServletRequest request) {
        actions.requireAccess(id, input.action()); var result = idempotency.execute(request, HttpStatus.ACCEPTED, () -> actions.act(id, input));
        return ResponseEntity.status(result.getStatusCode()).headers(result.getHeaders()).cacheControl(CacheControl.noStore()).body(result.getBody());
    }
}
