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
 * 凭证状态与财务调度入口，202 仅表示操作已登记，不表示 ERP 已完成。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/applications/{id}/vouchers")
public class VoucherController {
    private final VoucherWorkspace workspace;
    private final VoucherActions actions;
    private final VoucherAccess access;
    private final IdempotencyExecutor idempotency;
    /** 查询与幂等回放都复核实时字段权限。 */
    public VoucherController(VoucherWorkspace workspace, VoucherActions actions, VoucherAccess access, IdempotencyExecutor idempotency) {
        this.workspace = workspace; this.actions = actions; this.access = access; this.idempotency = idempotency;
    }
    /** 可读状态不包含账户、财务目标或完整命令。 */
    @GetMapping
    public ResponseEntity<VoucherWorkspace.View> get(@PathVariable UUID id, @RequestParam Map<String, String> parameters) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(workspace.get(id, parameters));
    }
    /** 金融写入的幂等回执不替代最新授权，后台按固定原事实执行。 */
    @PostMapping("/actions")
    public ResponseEntity<String> act(@PathVariable UUID id, @Valid @RequestBody VoucherActions.Input input, HttpServletRequest request) {
        access.requireFinance(id, input.roundNo());
        var result = idempotency.execute(request, HttpStatus.ACCEPTED, () -> actions.act(id, input));
        return ResponseEntity.status(result.getStatusCode()).headers(result.getHeaders()).cacheControl(CacheControl.noStore()).body(result.getBody());
    }
}
