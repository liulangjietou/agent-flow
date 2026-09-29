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
 * 银行退回原放款的专用只读核对与独立确认，不复用主动还款或付款写入入口。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/advance-requests/{id}")
public class AdvanceDisbursementReturnController {
    private final AdvanceDisbursementReturnWorkspace workspace;
    private final AdvanceDisbursementReturnService service;
    private final IdempotencyExecutor idempotency;
    /** 权限检查先于幂等回放。 */
    public AdvanceDisbursementReturnController(AdvanceDisbursementReturnWorkspace workspace, AdvanceDisbursementReturnService service, IdempotencyExecutor idempotency) { this.workspace = workspace; this.service = service; this.idempotency = idempotency; }
    /** 原申请人只读已确认事实，当前独立财务另见自己的候选。 */
    @GetMapping("/disbursement-review")
    public ResponseEntity<AdvanceDisbursementReturnWorkspace.View> read(@PathVariable UUID id, @RequestParam Map<String, String> parameters) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(workspace.read(id, parameters));
    }
    /** 202 仅代表已保存查询意图。 */
    @PostMapping("/disbursement-review-checks")
    public ResponseEntity<String> queue(@PathVariable UUID id, @Valid @RequestBody AdvanceDisbursementReturnService.QueryInput input, HttpServletRequest request) {
        service.authorize(id); var response = idempotency.execute(request, HttpStatus.ACCEPTED, () -> service.queue(id, input));
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).cacheControl(CacheControl.noStore()).body(response.getBody());
    }
    /** 只采纳已经保存的完整退回证据，不能手填金额或发送退款命令。 */
    @PostMapping("/disbursement-resolutions")
    public ResponseEntity<String> resolve(@PathVariable UUID id, @Valid @RequestBody AdvanceDisbursementReturnService.ResolveInput input, HttpServletRequest request) {
        service.authorize(id); var response = idempotency.execute(request, HttpStatus.ACCEPTED, () -> service.resolve(id, input));
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).cacheControl(CacheControl.noStore()).body(response.getBody());
    }
}
