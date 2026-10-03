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
    private final PaymentAccess payments;
    private final IdempotencyExecutor idempotency;
    private final VoucherDisputeService disputes;
    /** 查询与幂等回放都复核实时字段权限。 */
    public VoucherController(VoucherWorkspace workspace, VoucherActions actions, VoucherAccess access, PaymentAccess payments, IdempotencyExecutor idempotency, VoucherDisputeService disputes) {
        this.workspace = workspace; this.actions = actions; this.access = access; this.idempotency = idempotency;
        this.payments = payments;
        this.disputes = disputes;
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
    /** 付款会计状态独立于原费用或借款挂账，零应付不会伪造付款凭证。 */
    @GetMapping("/payment")
    public ResponseEntity<VoucherWorkspace.View> payment(@PathVariable UUID id, @RequestParam Map<String, String> parameters) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(workspace.payment(id, parameters));
    }
    /** 幂等回放前也重新核验当轮敏感字段、财务角色和当前法人任职。 */
    @PostMapping("/payment/actions")
    public ResponseEntity<String> paymentAction(@PathVariable UUID id, @Valid @RequestBody VoucherActions.Input input, HttpServletRequest request) {
        payments.requireFinance(id, input.roundNo());
        var result = idempotency.execute(request, HttpStatus.ACCEPTED, () -> actions.payment(id, input));
        return ResponseEntity.status(result.getStatusCode()).headers(result.getHeaders()).cacheControl(CacheControl.noStore()).body(result.getBody());
    }

    /** 明确采用原查询终态，回放前重新核验当前敏感字段和独立财务权限。 */
    @PostMapping("/{operationId}/dispute-resolutions")
    public ResponseEntity<String> resolve(@PathVariable UUID id, @PathVariable UUID operationId, @Valid @RequestBody VoucherDisputeService.Input input, HttpServletRequest request) {
        disputes.requireFinance(id, operationId, input.roundNo());
        var result = idempotency.execute(request, HttpStatus.ACCEPTED, () -> disputes.resolve(id, operationId, input));
        return ResponseEntity.status(result.getStatusCode()).headers(result.getHeaders()).cacheControl(CacheControl.noStore()).body(result.getBody());
    }
}
