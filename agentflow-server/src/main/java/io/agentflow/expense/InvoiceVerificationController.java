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
 * 本人发票验票入口；不接收票面结论、外部地址或可伪造原件字节。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/invoices/{id}")
public class InvoiceVerificationController {
    private final InvoiceVerificationService verification;
    private final IdempotencyExecutor idempotency;
    /** 幂等事务只登记任务，实际查验由后台事务外执行。 */
    public InvoiceVerificationController(InvoiceVerificationService verification, IdempotencyExecutor idempotency) {
        this.verification = verification; this.idempotency = idempotency;
    }
    /** 展示实际目的地和原件可验状态。 */
    @GetMapping("/verification-options")
    public ResponseEntity<InvoiceVerificationService.Options> options(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(verification.options(id));
    }
    /** 202 仅表示任务已保存，不能解释为票面通过。 */
    @PostMapping("/verifications")
    public ResponseEntity<String> queue(@PathVariable UUID id, @Valid @RequestBody InvoiceVerificationService.QueueInput input, HttpServletRequest request) {
        var result = idempotency.execute(request, HttpStatus.ACCEPTED, () -> verification.queue(id, input));
        return ResponseEntity.status(result.getStatusCode()).headers(result.getHeaders()).cacheControl(CacheControl.noStore()).body(result.getBody());
    }
    /** 当前权限查询明确进度与业务拒绝或不可用分类。 */
    @GetMapping("/verifications/{jobId}")
    public ResponseEntity<InvoiceVerificationService.View> get(@PathVariable UUID id, @PathVariable UUID jobId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(verification.get(id, jobId));
    }
    /** 历史只读并有界，不提供重置原任务接口。 */
    @GetMapping("/verifications")
    public ResponseEntity<InvoiceVerificationService.Page> list(@PathVariable UUID id, @RequestParam Map<String, String> parameters) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(verification.list(id, parameters));
    }
}
