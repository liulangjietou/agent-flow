package io.agentflow.expense;

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
 * 本人费用预检入口；202 只代表任务登记成功，不代表费用或预算通过。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/expense-reports/{id}")
public class ExpensePrecheckController {
    private final ExpensePrecheckService prechecks;
    private final IdempotencyExecutor idempotency;
    private final AdvanceOffsetSuggestionService offsets;
    /** 幂等事务仅登记输入，后台获取事实。 */
    public ExpensePrecheckController(ExpensePrecheckService prechecks, IdempotencyExecutor idempotency, AdvanceOffsetSuggestionService offsets) { this.prechecks = prechecks; this.idempotency = idempotency; this.offsets = offsets; }
    /** 推荐金额只来自有效预检，不接受客户端指定申请人、额度或排序。 */
    @GetMapping("/prechecks/{jobId}/advance-offset-suggestion")
    public ResponseEntity<AdvanceOffsetSuggestionService.Suggestion> offsets(@PathVariable UUID id, @PathVariable UUID jobId, @RequestParam Map<String, String> parameters) {
        if (!parameters.isEmpty()) throw new io.agentflow.common.DomainException("INVALID_QUERY", "Advance offset suggestion does not accept query parameters");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(offsets.suggest(id, jobId));
    }
    /** 公开实际目标和当前双版本供申请人确认。 */
    @GetMapping("/precheck-options")
    public ResponseEntity<ExpensePrecheckService.Options> options(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(prechecks.options(id));
    }
    /** 排队不产生发票占用、额度预留或预算冻结。 */
    @PostMapping("/precheck")
    public ResponseEntity<String> queue(@PathVariable UUID id, @Valid @RequestBody ExpensePrecheckService.QueueInput input, HttpServletRequest request) {
        var result = idempotency.execute(request, HttpStatus.ACCEPTED, () -> prechecks.queue(id, input));
        return ResponseEntity.status(result.getStatusCode()).headers(result.getHeaders()).cacheControl(CacheControl.noStore()).body(result.getBody());
    }
    /** 当前授权下读取状态、逐行结果及脱敏账户预览。 */
    @GetMapping("/prechecks/{jobId}")
    public ResponseEntity<ExpensePrecheckService.View> get(@PathVariable UUID id, @PathVariable UUID jobId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(prechecks.get(id, jobId));
    }
    /** 一页只返回轻量历史状态。 */
    @GetMapping("/prechecks")
    public ResponseEntity<ExpensePrecheckService.Page> list(@PathVariable UUID id, @RequestParam Map<String, String> parameters) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(prechecks.list(id, parameters));
    }
}
