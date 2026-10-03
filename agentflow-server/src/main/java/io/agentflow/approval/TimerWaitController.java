package io.agentflow.approval;

import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.approval.process.TimerWaitService;
import io.agentflow.common.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/**
 * 等待事实查询和原失败重试，不开放任意引擎任务执行或提前到期接口。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/applications/{id}/rounds/{roundNo}/timers")
public class TimerWaitController {
    private final TimerWaitService waits;
    private final IdempotencyExecutor idempotency;

    /** 写请求的未知结果继续使用平台原请求恢复机制。 */
    public TimerWaitController(TimerWaitService waits, IdempotencyExecutor idempotency) { this.waits = waits; this.idempotency = idempotency; }

    /** 查询只读且禁止缓存，轮次和筛选在入口一次校验。 */
    @GetMapping
    public ResponseEntity<TimerWaitService.View> read(@PathVariable UUID id, @PathVariable String roundNo, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(waits.read(id, round(roundNo, request)));
    }

    /** 原成功重放仍先检查当前管理员身份，不强求原任务继续存在。 */
    @PostMapping("/{jobId}/retry")
    public ResponseEntity<String> retry(@PathVariable UUID id, @PathVariable String roundNo, @PathVariable String jobId,
            @Valid @RequestBody TimerWaitService.RetryInput input, HttpServletRequest request) {
        int number = round(roundNo, request); waits.requireAdministrator();
        var result = idempotency.execute(request, HttpStatus.OK, () -> waits.retry(id, number, jobId, input));
        return ResponseEntity.status(result.getStatusCode()).headers(result.getHeaders()).cacheControl(CacheControl.noStore()).body(result.getBody());
    }

    private static int round(String value, HttpServletRequest request) {
        try {
            if (request.getQueryString() != null || !value.matches("[1-9][0-9]{0,9}")) throw new NumberFormatException();
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) { throw new DomainException("INVALID_TIMER_QUERY", "A positive round number and no query parameters are required"); }
    }
}
