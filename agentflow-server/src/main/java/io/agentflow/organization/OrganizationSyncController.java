package io.agentflow.organization;

import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

/**
 * 租户管理员同步入口；鉴权先于幂等回放，写回执不缓存组织来源正文。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/organization/synchronization")
public class OrganizationSyncController {
    private final CurrentActor actors;
    private final OrganizationSyncService service;
    private final OrganizationSyncApplicationService applications;
    private final IdempotencyExecutor idempotency;

    /** 复用原认证、CSRF 与幂等事务，不提供替代登录入口。 */
    public OrganizationSyncController(CurrentActor actors, OrganizationSyncService service,
                                      OrganizationSyncApplicationService applications, IdempotencyExecutor idempotency) {
        this.actors = actors; this.service = service; this.applications = applications; this.idempotency = idempotency;
    }

    /** 只读状态不触发来源请求或隐式注册。 */
    @GetMapping
    public ResponseEntity<OrganizationSyncService.Overview> status(@RequestParam MultiValueMap<String, String> query) {
        var actor = admin(); empty(query); return noStore(service.status(actor));
    }

    /** 202 只表示批次已排队，组织事实尚未改变。 */
    @PostMapping("/batches")
    public ResponseEntity<String> queue(@Valid @RequestBody QueueRequest body, HttpServletRequest request) {
        var actor = admin(); noQuery(request);
        return idempotency.execute(request, HttpStatus.ACCEPTED, () -> service.queue(actor, body.expectedSourceVersion(), body.targetDigest()));
    }

    /** 批次索引拒绝租户、主体及重复筛选参数。 */
    @GetMapping("/batches")
    public ResponseEntity<JdbcOrganizationSyncRepository.Page> batches(@RequestParam MultiValueMap<String, String> query) {
        var actor = admin(); var page = page(query); return noStore(service.list(actor, page[0], page[1]));
    }

    /** 详情保留原来源事实和人工决定，不要求来源当前可达。 */
    @GetMapping("/batches/{id}")
    public ResponseEntity<OrganizationSyncService.Detail> get(@PathVariable UUID id, @RequestParam MultiValueMap<String, String> query) {
        var actor = admin(); empty(query); return noStore(service.get(actor, id));
    }

    /** 重试明确指向原失败或取消批次，不覆盖原轨迹。 */
    @PostMapping("/batches/{id}/retry")
    public ResponseEntity<String> retry(@PathVariable UUID id, @Valid @RequestBody RetryRequest body, HttpServletRequest request) {
        var actor = admin(); noQuery(request);
        return idempotency.execute(request, HttpStatus.ACCEPTED, () -> service.retry(actor, id, body.expectedVersion(), body.expectedSourceVersion(), body.targetDigest()));
    }

    /** 原配置不可用时仍允许具名取消，迟到结果不得覆盖决定。 */
    @PostMapping("/batches/{id}/cancel")
    public ResponseEntity<String> cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest body, HttpServletRequest request) {
        var actor = admin(); noQuery(request);
        return idempotency.execute(request, HttpStatus.OK, () -> service.cancel(actor, id, body.expectedVersion(), body.comment()));
    }

    /** 预检保存可再次读取的计划；相同幂等请求返回原计划标识。 */
    @PostMapping("/batches/{id}/preflight")
    public ResponseEntity<String> preflight(@PathVariable UUID id, @Valid @RequestBody PreflightRequest body, HttpServletRequest request) {
        var actor = admin(); noQuery(request);
        return idempotency.execute(request, HttpStatus.CREATED, () -> {
            var saved = applications.preflight(actor, id, body.expectedVersion(), body.selections().stream().map(SelectionRequest::selection).toList());
            return new PlanReceipt(saved.plan().id(), saved.plan().ready());
        });
    }

    /** 应用只引用服务端计划，不接受客户端重新提供组织字段。 */
    @PostMapping("/batches/{id}/apply")
    public ResponseEntity<String> apply(@PathVariable UUID id, @Valid @RequestBody ApplyRequest body, HttpServletRequest request) {
        var actor = admin(); noQuery(request);
        return idempotency.execute(request, HttpStatus.OK, () -> {
            var state = applications.apply(actor, id, body.expectedVersion(), body.planId(), body.comment());
            return new OrganizationSyncService.Receipt(id, state.status(), state.version());
        });
    }

    /** 计划正文通过当前管理员权限读取，不能仅凭旧回执取得。 */
    @GetMapping("/plans/{planId}")
    public ResponseEntity<JdbcOrganizationSyncPlanRepository.Saved> plan(@PathVariable UUID planId, @RequestParam MultiValueMap<String, String> query) {
        var actor = admin(); empty(query); return noStore(applications.plan(actor, planId));
    }

    /** 批次轨迹不重复返回每一步的完整组织正文。 */
    @GetMapping("/batches/{id}/transitions")
    public ResponseEntity<List<JdbcOrganizationSyncRepository.Transition>> transitions(@PathVariable UUID id, @RequestParam MultiValueMap<String, String> query) {
        var actor = admin(); empty(query); return noStore(service.transitions(actor, id));
    }

    /** 分页读取所有预检，不因后续计划产生而丢失旧核对记录。 */
    @GetMapping("/batches/{id}/plans")
    public ResponseEntity<JdbcOrganizationSyncPlanRepository.Page> plans(@PathVariable UUID id, @RequestParam MultiValueMap<String, String> query) {
        var actor = admin(); var page = page(query); return noStore(service.plans(actor, id, page[0], page[1]));
    }

    private Actor admin() { var actor = actors.actor(); actor.requireRole("ADMIN"); return actor; }
    private static <T> ResponseEntity<T> noStore(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    private static void noQuery(HttpServletRequest request) { if (request.getQueryString() != null) throw query(); }
    private static void empty(MultiValueMap<String, String> values) { if (!values.isEmpty()) throw query(); }
    private static int[] page(MultiValueMap<String, String> values) {
        if (!Set.of("page", "pageSize").containsAll(values.keySet()) || values.values().stream().anyMatch(list -> list.size() != 1)) throw query();
        return new int[] { integer(values.getFirst("page"), 0, 0, 10000), integer(values.getFirst("pageSize"), 20, 1, 50) };
    }
    private static int integer(String value, int fallback, int minimum, int maximum) {
        if (value == null) return fallback; if (!value.matches("0|[1-9][0-9]{0,4}")) throw query();
        int result = Integer.parseInt(value); if (result < minimum || result > maximum) throw query(); return result;
    }
    private static DomainException query() { return new DomainException("INVALID_ORGANIZATION_SYNC_QUERY", "Organization synchronization query is invalid"); }

    /**
     * 初次注册使用来源版本零，后续必须确认当前已注册来源版本和目标。
     * @author owlzhangfq@gmail.com
     */
    public record QueueRequest(@NotNull @PositiveOrZero Long expectedSourceVersion, @NotNull @Pattern(regexp = "[a-f0-9]{64}") String targetDigest) { }
    /**
     * 重试同时确认原终态和当前来源，不能复用旧目标意图。
     * @author owlzhangfq@gmail.com
     */
    public record RetryRequest(@NotNull @Positive Long expectedVersion, @NotNull @Positive Long expectedSourceVersion,
                               @NotNull @Pattern(regexp = "[a-f0-9]{64}") String targetDigest) { }
    /**
     * 取消只接收原版本及具名意见，不携带来源正文。
     * @author owlzhangfq@gmail.com
     */
    public record CancelRequest(@NotNull @Positive Long expectedVersion, @Size(max = OrganizationSyncBatch.MAX_COMMENT_LENGTH) String comment) { }
    /**
     * 明确采用的本地对象必须携带核对过的实体修订。
     * @author owlzhangfq@gmail.com
     */
    public record SelectionRequest(@NotNull OrganizationSyncKey.Kind kind, @NotBlank @Size(max = 128) String externalId,
                                   @NotNull UUID localId, @NotNull @Positive Long expectedRevision) {
        /** 领域键保持原值，不使用客户端显示名进行匹配。 */
        public OrganizationSyncPlan.Selection selection() { return new OrganizationSyncPlan.Selection(new OrganizationSyncKey(kind, externalId), localId, expectedRevision); }
    }
    /**
     * 没有人工采用选择时也执行完整来源预检，不等同于自动应用。
     * @author owlzhangfq@gmail.com
     */
    public record PreflightRequest(@NotNull @Positive Long expectedVersion, @NotNull @Size(max = OrganizationSyncDelta.MAX_RECORDS) List<@NotNull @Valid SelectionRequest> selections) { }
    /**
     * 应用引用服务端已保存计划及原批次修订。
     * @author owlzhangfq@gmail.com
     */
    public record ApplyRequest(@NotNull @Positive Long expectedVersion, @NotNull UUID planId, @Size(max = OrganizationSyncBatch.MAX_COMMENT_LENGTH) String comment) { }
    /**
     * 预检幂等回执不缓存敏感组织前后值。
     * @author owlzhangfq@gmail.com
     */
    public record PlanReceipt(UUID id, boolean ready) { }
}
