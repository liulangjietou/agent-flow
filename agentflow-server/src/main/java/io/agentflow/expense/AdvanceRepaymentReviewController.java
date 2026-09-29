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
 * 原还款复核与明确裁决使用独立接口，不复用原还款记账或出纳付款入口。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/advance-requests/{id}/repayments/{repaymentId}")
public class AdvanceRepaymentReviewController {
    private final AdvanceRepaymentReviewWorkspace workspace;
    private final AdvanceRepaymentReviewService service;
    private final IdempotencyExecutor idempotency;
    /** 权限先于幂等回放，避免失去权限后获取旧金融材料。 */
    public AdvanceRepaymentReviewController(AdvanceRepaymentReviewWorkspace workspace, AdvanceRepaymentReviewService service, IdempotencyExecutor idempotency) { this.workspace = workspace; this.service = service; this.idempotency = idempotency; }
    /** 原件、追加退回与当前财务自己的候选复核。 */
    @GetMapping("/review")
    public ResponseEntity<AdvanceRepaymentReviewWorkspace.View> read(@PathVariable UUID id, @PathVariable UUID repaymentId, @RequestParam Map<String, String> parameters) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(workspace.read(id, repaymentId, parameters));
    }
    /** 202 仅说明只读查询意图已持久化。 */
    @PostMapping("/review-checks")
    public ResponseEntity<String> queue(@PathVariable UUID id, @PathVariable UUID repaymentId, @Valid @RequestBody AdvanceRepaymentReviewService.QueryInput input, HttpServletRequest request) {
        service.authorize(id, repaymentId); var response = idempotency.execute(request, HttpStatus.ACCEPTED, () -> service.queue(id, repaymentId, input));
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).cacheControl(CacheControl.noStore()).body(response.getBody());
    }
    /** 接受当前事实或确认真实全额退回，不对外发起任何支付或会计写入。 */
    @PostMapping("/resolutions")
    public ResponseEntity<String> resolve(@PathVariable UUID id, @PathVariable UUID repaymentId, @Valid @RequestBody AdvanceRepaymentReviewService.ResolveInput input, HttpServletRequest request) {
        service.authorize(id, repaymentId); var response = idempotency.execute(request, HttpStatus.ACCEPTED, () -> service.resolve(id, repaymentId, input));
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).cacheControl(CacheControl.noStore()).body(response.getBody());
    }
}
