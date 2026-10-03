package io.agentflow.event;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 签名接收与管理员读取共用资源，只有精确接收入口使用外部来源身份。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping(EventIngressVerifier.PATH)
public class EventInboxController {
    private final EventIngressVerifier verifier;
    private final EventInboxService service;
    private final EventInboxRepository inbox;
    private final CurrentActor actors;
    private final IdempotencyExecutor idempotency;
    /** 接收入口不反序列化请求体，由验签器在有界读取后处理。 */
    public EventInboxController(EventIngressVerifier verifier, EventInboxService service, EventInboxRepository inbox,
            CurrentActor actors, IdempotencyExecutor idempotency) {
        this.verifier = verifier; this.service = service; this.inbox = inbox; this.actors = actors; this.idempotency = idempotency;
    }
    /** 稳定 202 回执仅证明已经持久接收，不声明等待已推进或申请已批准。 */
    @PostMapping
    public ResponseEntity<Receipt> receive(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        var verified = verifier.verify(request, Instant.now()); var saved = service.accept(verified, Instant.now());
        return ResponseEntity.accepted().header("Cache-Control", "no-store").body(new Receipt(saved.id(), saved.input().eventId()));
    }
    /** 管理员按租户分页读取运行元数据，不扩大表单和附件权限。 */
    @GetMapping
    public ResponseEntity<Page> list(HttpServletRequest request) {
        var actor = actors.actor(); actor.requireRole("ADMIN"); var query = query(request, false);
        var rows = inbox.page(actor.tenantId(), query.limit(), query.beforeId()); int count = Math.min(rows.size(), query.limit());
        return noStore(new Page(rows.subList(0, count).stream().map(View::from).toList(), rows.size() > count ? rows.get(count - 1).id() : null));
    }
    /** 查看原事件当前处理状态，原始摘要和签名不进入响应。 */
    @GetMapping("/{id}")
    public ResponseEntity<View> detail(@PathVariable UUID id, HttpServletRequest request) {
        var actor = actors.actor(); actor.requireRole("ADMIN"); requireNoQuery(request);
        return noStore(View.from(inbox.get(actor.tenantId(), id)));
    }
    /** 追加历史按真实修订翻页，不把最新五十条冒充完整历史。 */
    @GetMapping("/{id}/history")
    public ResponseEntity<History> history(@PathVariable UUID id, HttpServletRequest request) {
        var actor = actors.actor(); actor.requireRole("ADMIN"); var query = query(request, true);
        inbox.get(actor.tenantId(), id); var rows = inbox.history(actor.tenantId(), id, query.limit(), query.beforeVersion());
        int count = Math.min(rows.size(), query.limit());
        return noStore(new History(rows.subList(0, count).stream().map(View::from).toList(), rows.size() > count ? rows.get(count - 1).version() : null));
    }
    /** 权限先于幂等回放检查；原事件恢复必须有明确原因及当前处理修订。 */
    @PostMapping("/{id}/retry")
    public ResponseEntity<String> retry(@PathVariable UUID id, @Valid @RequestBody RetryInput input, HttpServletRequest request) {
        var actor = actors.actor(); actor.requireRole("ADMIN"); requireNoQuery(request);
        var response = idempotency.execute(request, HttpStatus.OK, () -> View.from(service.retry(actor, id, input.expectedVersion(), input.reason(), Instant.now())));
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).header("Cache-Control", "no-store").body(response.getBody());
    }
    private static Query query(HttpServletRequest request, boolean history) {
        var raw = request.getParameterMap();
        if (!(history ? Set.of("limit", "beforeVersion") : Set.of("limit", "beforeId")).containsAll(raw.keySet())
                || raw.values().stream().anyMatch(values -> values.length != 1)) throw invalidQuery();
        try {
            int limit = 30; UUID beforeId = null; Long beforeVersion = null;
            if (raw.containsKey("limit")) {
                String text = raw.get("limit")[0]; if (!text.matches("[1-9][0-9]{0,2}")) throw invalidQuery();
                limit = Integer.parseInt(text); if (limit > 100) throw invalidQuery();
            }
            if (raw.containsKey("beforeId")) {
                String text = raw.get("beforeId")[0]; beforeId = UUID.fromString(text); if (!beforeId.toString().equals(text)) throw invalidQuery();
            }
            if (raw.containsKey("beforeVersion")) {
                String text = raw.get("beforeVersion")[0]; if (!text.matches("[1-9][0-9]{0,18}")) throw invalidQuery(); beforeVersion = Long.parseLong(text);
            }
            return new Query(limit, beforeId, beforeVersion);
        } catch (IllegalArgumentException failure) { throw invalidQuery(); }
    }
    private static void requireNoQuery(HttpServletRequest request) { if (request.getQueryString() != null) throw invalidQuery(); }
    private static DomainException invalidQuery() { return new DomainException("INVALID_EVENT_INBOX_QUERY", "Event inbox query is invalid"); }
    private static <T> ResponseEntity<T> noStore(T value) { return ResponseEntity.ok().header("Cache-Control", "no-store").body(value); }
    /** @author owlzhangfq@gmail.com */
    private record Query(int limit, UUID beforeId, Long beforeVersion) { }
    /** @author owlzhangfq@gmail.com */
    public record Receipt(UUID id, String eventId) { }
    /** @author owlzhangfq@gmail.com */
    public record View(UUID id, String eventId, String sourceKey, long trustRevision, String eventType, UUID applicationId,
                       int roundNo, String waitId, String contractKey, long contractVersion, long version, EventInboxItem.Status status,
                       Instant receivedAt, Instant updatedAt, Instant nextAttemptAt, int failures, EventInboxItem.Reason reason,
                       String errorCode, String requestedBy, String requestReason) {
        static View from(EventInboxItem value) {
            var signal = value.input().signal();
            return new View(value.id(), value.input().eventId(), signal.sourceKey(), value.input().trustRevision(), signal.eventType(),
                    signal.applicationId(), signal.roundNo(), signal.waitId(), signal.contractKey(), signal.contractVersion(), value.version(), value.status(),
                    value.receivedAt(), value.updatedAt(), value.nextAttemptAt(), value.failures(), value.reason(), value.errorCode(), value.requestedBy(), value.requestReason());
        }
    }
    /** @author owlzhangfq@gmail.com */
    public record Page(List<View> items, UUID nextBeforeId) { }
    /** @author owlzhangfq@gmail.com */
    public record History(List<View> items, Long nextBeforeVersion) { }
    /** @author owlzhangfq@gmail.com */
    public record RetryInput(@NotNull @Positive Long expectedVersion, @NotBlank @Size(max = 500) String reason) {
        /** 原号恢复不接受更换来源、信任修订、契约或激活标识。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown event retry field"); }
    }
}
