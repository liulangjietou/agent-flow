package io.agentflow.finance;

import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.common.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.Set;
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
 * 出纳明确组批入口；重复提交回放原批次，银行执行始终沿用原授权编号。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/payment-batches")
public class PaymentBatchController {
    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;
    private final PaymentBatchService service;
    private final IdempotencyExecutor idempotency;

    /** 批次和每笔原执行登记参加统一请求幂等事务。 */
    public PaymentBatchController(PaymentBatchService service, IdempotencyExecutor idempotency) { this.service = service; this.idempotency = idempotency; }

    /** 返回登记回执；202 不代表账户已复查或银行已受理。 */
    @PostMapping
    public ResponseEntity<String> submit(@Valid @RequestBody PaymentBatchService.Input input, HttpServletRequest request) {
        service.requireAccess(input);
        var result = idempotency.execute(request, HttpStatus.ACCEPTED, () -> service.submit(input));
        return ResponseEntity.status(result.getStatusCode()).headers(result.getHeaders()).cacheControl(CacheControl.noStore()).body(result.getBody());
    }

    /** 分页只接受有界页长与规范 UUID，读取范围由当前出纳任职决定。 */
    @GetMapping
    public ResponseEntity<PaymentBatchService.Page> list(@RequestParam Map<String, String> parameters) {
        if (!Set.of("limit", "beforeId").containsAll(parameters.keySet())) throw invalid();
        int limit = DEFAULT_LIMIT; UUID before = null;
        try {
            if (parameters.containsKey("limit")) {
                if (!parameters.get("limit").matches("[1-9][0-9]{0,2}")) throw invalid();
                limit = Integer.parseInt(parameters.get("limit")); if (limit > MAX_LIMIT) throw invalid();
            }
            if (parameters.containsKey("beforeId")) {
                before = UUID.fromString(parameters.get("beforeId")); if (!before.toString().equals(parameters.get("beforeId"))) throw invalid();
            }
        } catch (IllegalArgumentException invalid) { throw invalid(); }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(limit, before));
    }

    /** 批次详情不授予完整申请权限；原付款状态按当前权限重新读取。 */
    @GetMapping("/{id}")
    public ResponseEntity<PaymentBatchService.Detail> get(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(id));
    }
    private static DomainException invalid() { return new DomainException("INVALID_PAYMENT_BATCH", "Payment batch query accepts only a bounded limit and canonical beforeId"); }
}
