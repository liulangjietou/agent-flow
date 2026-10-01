package io.agentflow.event;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * 管理员发布事件白名单和明确启停；幂等回放之前重新检查当前管理员权限。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/event-contracts")
public class EventContractController {
    private final EventContractService service;
    private final CurrentActor actors;
    private final IdempotencyExecutor idempotency;

    /** 复用认证租户和写请求原号恢复协议。 */
    public EventContractController(EventContractService service, CurrentActor actors, IdempotencyExecutor idempotency) {
        this.service = service; this.actors = actors; this.idempotency = idempotency;
    }

    /** 管理目录不接受租户覆盖参数。 */
    @GetMapping
    public ResponseEntity<EventContractViews.Directory> list(@RequestParam Map<String, String> query) {
        var actor = administrator(); return noStore(service.list(actor.tenantId(), EventContractQuery.directory(query)));
    }

    /** 明确发布新版本；首次发布 expectedVersion 为零。 */
    @PostMapping("/{key}/versions")
    public ResponseEntity<String> publish(@PathVariable String key, @Valid @RequestBody PublishInput input, HttpServletRequest request) {
        var actor = administrator();
        return idempotency.execute(request, HttpStatus.CREATED, () -> EventContractViews.Detail.from(service.publish(actor, key,
                input.expectedVersion(), input.name(), input.sourceKey(), input.eventType(), input.reason())));
    }

    /** 历史发布目录保留明确的来源、事件类型和可用性。 */
    @GetMapping("/{key}/versions")
    public ResponseEntity<EventContractViews.Versions> versions(@PathVariable String key, @RequestParam Map<String, String> query) {
        var actor = administrator(); return noStore(service.versions(actor.tenantId(), key, EventContractQuery.versions(query, false)));
    }

    /** 查看精确版本的不可变正文及当前可用性。 */
    @GetMapping("/{key}/versions/{version}")
    public ResponseEntity<EventContractViews.Detail> version(@PathVariable String key, @PathVariable long version) {
        var actor = administrator(); return noStore(EventContractViews.Detail.from(service.version(actor.tenantId(), key, version)));
    }

    /** 启停只针对请求路径中的版本，并且必须填写原因及原可用性修订。 */
    @PostMapping("/{key}/versions/{version}/availability")
    public ResponseEntity<String> availability(@PathVariable String key, @PathVariable long version,
                                               @Valid @RequestBody AvailabilityInput input, HttpServletRequest request) {
        var actor = administrator();
        return idempotency.execute(request, HttpStatus.OK, () -> EventContractViews.Detail.from(service.changeAvailability(actor, key, version,
                input.expectedRevision(), input.enabled(), input.reason())));
    }

    /** 读取追加的发布和启停历史，不读取其他契约的操作。 */
    @GetMapping("/{key}/versions/{version}/history")
    public ResponseEntity<EventContractViews.History> history(@PathVariable String key, @PathVariable long version, @RequestParam Map<String, String> query) {
        var actor = administrator(); return noStore(service.history(actor.tenantId(), key, version, EventContractQuery.versions(query, true)));
    }

    private Actor administrator() { var actor = actors.actor(); actor.requireRole("ADMIN"); return actor; }
    private static <T> ResponseEntity<T> noStore(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }

    /**
     * 来源是租户内逻辑标识；本接口既不接收密钥，也不授权任意地址或事件正文。
     * @author owlzhangfq@gmail.com
     */
    public record PublishInput(@NotNull @PositiveOrZero Long expectedVersion, String name, String sourceKey, String eventType, String reason) {
        /** 不允许忽略正文中的租户、作者或其他未声明字段。 */
        @JsonAnySetter public void reject(String key, Object value) { throw new DomainException("INVALID_EVENT_CONTRACT", "Unsupported event contract field"); }
    }

    /**
     * 显式布尔值避免漏传被解释成停用；可用性修订与发布版本分开提交。
     * @author owlzhangfq@gmail.com
     */
    public record AvailabilityInput(@NotNull @Positive Long expectedRevision, @NotNull Boolean enabled, String reason) {
        /** 启停不能夹带契约修改或历史覆盖。 */
        @JsonAnySetter public void reject(String key, Object value) { throw new DomainException("INVALID_EVENT_CONTRACT", "Unsupported event contract availability field"); }
    }
}
