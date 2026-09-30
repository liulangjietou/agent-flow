package io.agentflow.finance.callback;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 银行签名入口与管理员收件箱共用路径；仅精确 POST 接收使用 HMAC 身份。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping(PaymentCallbackVerifier.PATH)
public class PaymentCallbackController {
    private final PaymentCallbackVerifier verifier;
    private final PaymentCallbackService service;
    private final JdbcPaymentCallbackRepository callbacks;
    private final CurrentActor currentActor;
    private final IdempotencyExecutor idempotency;

    /** 原始请求仅交验签器有界读取，管理写接口沿用统一用户请求幂等。 */
    public PaymentCallbackController(PaymentCallbackVerifier verifier, PaymentCallbackService service, JdbcPaymentCallbackRepository callbacks,
            CurrentActor currentActor, IdempotencyExecutor idempotency) {
        this.verifier = verifier; this.service = service; this.callbacks = callbacks; this.currentActor = currentActor; this.idempotency = idempotency;
    }
    /** 返回持久接收编号；202 仅代表接收，资金状态必须查询原交易。 */
    @PostMapping
    public ResponseEntity<Receipt> receive(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        var input = verifier.verify(request, Instant.now()); var saved = service.accept(input, Instant.now());
        return ResponseEntity.accepted().header("Cache-Control", "no-store").body(new Receipt(saved.id(), saved.input().eventId(), saved.status()));
    }
    /** 管理员只能查看最小运行元数据，读取权限不包含付款金额和账户快照。 */
    @GetMapping
    public ResponseEntity<Page> list(@RequestParam Map<String, String> raw) {
        var actor = currentActor.actor(); actor.requireRole("ADMIN");
        if (!Set.of("limit", "beforeId").containsAll(raw.keySet())) throw invalidQuery();
        int limit = 25; UUID before = null;
        try {
            if (raw.containsKey("limit")) {
                if (!raw.get("limit").matches("[1-9][0-9]{0,2}")) throw invalidQuery();
                limit = Integer.parseInt(raw.get("limit")); if (limit > 100) throw invalidQuery();
            }
            if (raw.containsKey("beforeId")) {
                before = UUID.fromString(raw.get("beforeId")); if (!before.toString().equals(raw.get("beforeId"))) throw invalidQuery();
            }
        } catch (IllegalArgumentException invalid) { throw invalidQuery(); }
        var rows = callbacks.page(actor.tenantId(), limit, before); int count = Math.min(limit, rows.size());
        return noStore(new Page(rows.subList(0, count).stream().map(View::of).toList(), rows.size() > limit ? rows.get(count - 1).id() : null));
    }
    /** 回调及最多五十次处理历史均按当前租户隔离。 */
    @GetMapping("/{id}")
    public ResponseEntity<Detail> detail(@PathVariable UUID id) {
        var actor = currentActor.actor(); actor.requireRole("ADMIN");
        var current = callbacks.get(actor.tenantId(), id);
        return noStore(new Detail(View.of(current), callbacks.history(actor.tenantId(), id).stream().map(View::of).toList()));
    }
    /** 重试要求明确原因和期望版本，重复点击仍回放同一操作结果。 */
    @PostMapping("/{id}/retry")
    public ResponseEntity<String> retry(@PathVariable UUID id, @Valid @RequestBody RetryInput input, HttpServletRequest request) {
        var actor = currentActor.actor(); actor.requireRole("ADMIN");
        var response = idempotency.execute(request, HttpStatus.OK,
                () -> View.of(service.retry(actor, id, input.expectedVersion(), input.reason(), Instant.now())));
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).header("Cache-Control", "no-store").body(response.getBody());
    }
    private static DomainException invalidQuery() { return new DomainException("PAYMENT_CALLBACK_INVALID", "Invalid payment callback query"); }
    private static <T> ResponseEntity<T> noStore(T value) { return ResponseEntity.ok().header("Cache-Control", "no-store").body(value); }

    /**
     * 接收回执不返回付款结果和资金信息。
     * @author owlzhangfq@gmail.com
     */
    public record Receipt(UUID id, String eventId, PaymentCallback.Status status) { }
    /**
     * 运行状态投影，管理员的权限不扩展到财务原件。
     * @author owlzhangfq@gmail.com
     */
    public record View(UUID id, String eventId, PaymentCallbackVerifier.Kind kind, UUID authorizationId, long sourceRevision,
                       long version, PaymentCallback.Status status, Instant receivedAt, Instant updatedAt, Instant nextAttemptAt,
                       int failures, Long queryVersion, PaymentCallback.Reason reason, String requestedBy, String requestReason) {
        static View of(PaymentCallback value) {
            var signal = value.input().signal();
            return new View(value.id(), value.input().eventId(), signal.kind(), signal.authorizationId(), signal.sourceRevision(), value.version(), value.status(),
                    value.receivedAt(), value.updatedAt(), value.nextAttemptAt(), value.failures(), value.queryVersion(), value.reason(), value.requestedBy(), value.requestReason());
        }
    }
    /**
     * 历史翻页只返回当前页和下一页锚点。
     * @author owlzhangfq@gmail.com
     */
    public record Page(List<View> items, UUID nextBeforeId) { }
    /**
     * 处理历史有界返回，版本标识仍为真实持久版本。
     * @author owlzhangfq@gmail.com
     */
    public record Detail(View callback, List<View> history) { }
    /**
     * 原事件的人工恢复不允许传入新的银行命令或业务数据。
     * @author owlzhangfq@gmail.com
     */
    public record RetryInput(@NotNull @Min(1) Long expectedVersion, @NotBlank @Size(max = 500) String reason) {
        /** 恢复只接受原版本与原因，不静默忽略客户端附加的资金声明。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown payment callback retry field"); }
    }
}
