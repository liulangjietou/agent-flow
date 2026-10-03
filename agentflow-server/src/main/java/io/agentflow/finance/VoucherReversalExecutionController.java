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
 * 独立冲销办理将准备、明确授权及原命令恢复分别设为幂等操作，写前重新检查原权限。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/applications/{id}/vouchers/{operationId}/reversal-execution")
public class VoucherReversalExecutionController {
    private final VoucherReversalExecutionWorkspace workspace;
    private final VoucherReversalPreparationService service;
    private final VoucherReversalRetirementService retirement;
    private final IdempotencyExecutor idempotency;
    /** HTTP 只持久本地意图，后台提交后再执行 ERP 网络调用。 */
    public VoucherReversalExecutionController(VoucherReversalExecutionWorkspace workspace, VoucherReversalPreparationService service, VoucherReversalRetirementService retirement, IdempotencyExecutor idempotency) {
        this.workspace = workspace; this.service = service; this.retirement = retirement; this.idempotency = idempotency;
    }
    /** 敏感财务状态禁止共享缓存，读取不会开始准备或写操作。 */
    @GetMapping
    public ResponseEntity<VoucherReversalExecutionWorkspace.View> read(@PathVariable UUID id, @PathVariable UUID operationId, @RequestParam Map<String, String> parameters) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(workspace.read(id, operationId, parameters));
    }
    /** 只排队核对原件和期间，仍等待财务审阅反向分录。 */
    @PostMapping("/preparations")
    public ResponseEntity<String> prepare(@PathVariable UUID id, @PathVariable UUID operationId, @Valid @RequestBody VoucherReversalPreparationService.PrepareInput input, HttpServletRequest request) {
        service.authorizeAccess(id, operationId, input.roundNo());
        var result = idempotency.execute(request, HttpStatus.ACCEPTED, () -> service.prepare(id, operationId, input));
        return ResponseEntity.status(result.getStatusCode()).headers(result.getHeaders()).cacheControl(CacheControl.noStore()).body(result.getBody());
    }
    /** 财务明确授权实际 ERP 冲销，原件停用与原命令登记同事务保存。 */
    @PostMapping("/authorizations")
    public ResponseEntity<String> authorize(@PathVariable UUID id, @PathVariable UUID operationId, @Valid @RequestBody VoucherReversalPreparationService.AuthorizeInput input, HttpServletRequest request) {
        service.authorizeAccess(id, operationId, input.roundNo());
        var result = idempotency.execute(request, HttpStatus.ACCEPTED, () -> service.authorize(id, operationId, input));
        return ResponseEntity.status(result.getStatusCode()).headers(result.getHeaders()).cacheControl(CacheControl.noStore()).body(result.getBody());
    }
    /** 查询与权威查无后的原命令重发分别记录，不自动创建新编号。 */
    @PostMapping("/actions")
    public ResponseEntity<String> action(@PathVariable UUID id, @PathVariable UUID operationId, @Valid @RequestBody VoucherReversalPreparationService.OperationInput input, HttpServletRequest request) {
        service.authorizeAccess(id, operationId, input.roundNo());
        var result = idempotency.execute(request, HttpStatus.ACCEPTED, () -> service.act(id, operationId, input));
        return ResponseEntity.status(result.getStatusCode()).headers(result.getHeaders()).cacheControl(CacheControl.noStore()).body(result.getBody());
    }
    /** 结束必须有不可执行的依据和新鲜原件，幂等回放仍重新核对当前财务权限。 */
    @PostMapping("/retirements")
    public ResponseEntity<String> retire(@PathVariable UUID id, @PathVariable UUID operationId, @Valid @RequestBody VoucherReversalRetirementService.Input input, HttpServletRequest request) {
        service.authorizeAccess(id, operationId, input.roundNo());
        var result = idempotency.execute(request, HttpStatus.ACCEPTED, () -> retirement.retire(id, operationId, input));
        return ResponseEntity.status(result.getStatusCode()).headers(result.getHeaders()).cacheControl(CacheControl.noStore()).body(result.getBody());
    }
}
