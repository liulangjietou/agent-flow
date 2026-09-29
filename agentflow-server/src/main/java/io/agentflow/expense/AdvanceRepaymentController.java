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
 * 借款还款读取和明确确认分属独立接口，幂等回放前仍复核当前财务权限。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/advance-requests/{id}")
public class AdvanceRepaymentController {
    private final AdvanceRepaymentWorkspace workspace;
    private final AdvanceRepaymentService service;
    private final IdempotencyExecutor idempotency;
    /** 所有页面响应禁止缓存，写入响应仅返回可核对的编号。 */
    public AdvanceRepaymentController(AdvanceRepaymentWorkspace workspace, AdvanceRepaymentService service, IdempotencyExecutor idempotency) { this.workspace = workspace; this.service = service; this.idempotency = idempotency; }
    /** 当前或指定原轮次下的借款余额与还款历史。 */
    @GetMapping("/repayments")
    public ResponseEntity<AdvanceRepaymentWorkspace.View> read(@PathVariable UUID id, @RequestParam Map<String, String> parameters) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(workspace.read(id, parameters));
    }
    /** 202 只表示查询意图持久化，不代表还款已确认。 */
    @PostMapping("/repayment-checks")
    public ResponseEntity<String> queue(@PathVariable UUID id, @Valid @RequestBody AdvanceRepaymentService.QueryInput input, HttpServletRequest request) {
        service.authorize(id); var response = idempotency.execute(request, HttpStatus.ACCEPTED, () -> service.queue(id, input));
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).cacheControl(CacheControl.noStore()).body(response.getBody());
    }
    /** 确认原外部收款与冲减分录，不发起转账或重复推送凭证。 */
    @PostMapping("/repayments")
    public ResponseEntity<String> record(@PathVariable UUID id, @Valid @RequestBody AdvanceRepaymentService.RecordInput input, HttpServletRequest request) {
        service.authorize(id); var response = idempotency.execute(request, HttpStatus.ACCEPTED, () -> service.record(id, input));
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).cacheControl(CacheControl.noStore()).body(response.getBody());
    }
}
