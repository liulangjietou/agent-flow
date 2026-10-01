package io.agentflow.organization;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionDraftRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 租户管理员具名管理有期限代理；读取按当前时刻展示状态，写回执不缓存可变授权结论。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/organization/approval-proxies")
public class ApprovalProxyController {
    private final CurrentActor actors;
    private final ApprovalProxyService service;
    private final ApprovalProxyRepository proxies;
    private final OrganizationRepository organization;
    private final DefinitionDraftRepository definitions;
    private final IdempotencyExecutor idempotency;

    /** 复用组织、身份、定义和幂等边界，不让客户端指定租户或代替办理人。 */
    public ApprovalProxyController(CurrentActor actors, ApprovalProxyService service, ApprovalProxyRepository proxies,
                                   OrganizationRepository organization, DefinitionDraftRepository definitions,
                                   IdempotencyExecutor idempotency) {
        this.actors = actors; this.service = service; this.proxies = proxies; this.organization = organization;
        this.definitions = definitions; this.idempotency = idempotency;
    }

    /** 一页使用相同观察时刻；按任一参与人员过滤，游标保持原记录稳定顺序。 */
    @GetMapping
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ResponseEntity<Page> list(@RequestParam Map<String, String> raw) {
        Actor actor = administrator();
        var query = OrganizationQuery.parse(raw, Set.of("personId", "afterId", "limit"));
        var values = proxies.list(actor.tenantId(), query.personId(), query.afterId(), query.limit());
        Instant observedAt = Instant.now();
        int count = Math.min(values.size(), query.limit());
        var items = values.subList(0, count).stream().map(proxy -> view(actor.tenantId(), proxy, observedAt)).toList();
        String next = values.size() > count ? values.get(count - 1).id().toString() : null;
        return noStore(new Page(items, next, observedAt));
    }

    /** 创建或撤销后的原号刷新展示最新期限和撤销状态，不回放旧的生效结论。 */
    @GetMapping("/{id}")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ResponseEntity<View> get(@PathVariable UUID id) {
        var actor = administrator();
        var proxy = proxies.find(actor.tenantId(), id).orElseThrow(ApprovalProxyController::notFound);
        return noStore(view(actor.tenantId(), proxy, Instant.now()));
    }

    /** 原键重放只返回首次创建编号；最新状态必须另行读取。 */
    @PostMapping
    public ResponseEntity<String> create(@RequestBody CreateInput body, HttpServletRequest request) {
        var actor = administrator();
        return idempotency.execute(request, HttpStatus.CREATED, () -> receipt(service.create(actor,
                body.definitionId(), body.principalId(), body.substituteId(), body.startsAt(), body.endsAt(), body.reason())));
    }

    /** 重放前仍要求管理员资格，终态撤销不能复活或悄悄改变原期限。 */
    @PostMapping("/{id}/revoke")
    public ResponseEntity<String> revoke(@PathVariable UUID id, @Valid @RequestBody RevokeInput body, HttpServletRequest request) {
        var actor = administrator();
        return idempotency.execute(request, HttpStatus.OK, () -> receipt(service.revoke(actor, id, body.expectedRevision(), body.reason())));
    }

    private View view(String tenantId, ApprovalProxy proxy, Instant observedAt) {
        var definition = definitions.findById(tenantId, proxy.definitionId()).orElseThrow(ApprovalProxyController::notFound);
        return new View(proxy, proxy.statusAt(observedAt), observedAt, definition.key(), definition.version(), definition.name(),
                person(tenantId, proxy.principalId()), person(tenantId, proxy.substituteId()));
    }

    private Person person(String tenantId, UUID id) {
        var person = organization.person(tenantId, id).orElseThrow(ApprovalProxyController::notFound);
        return new Person(person.id(), person.subject(), person.displayName(), person.canApprove());
    }

    private Actor administrator() { var actor = actors.actor(); actor.requireRole("ADMIN"); return actor; }
    private static Receipt receipt(ApprovalProxy proxy) { return new Receipt(proxy.id(), proxy.revision()); }
    private static <T> ResponseEntity<T> noStore(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Approval proxy or its tenant references were not found"); }

    /**
     * 原授权及动态状态分开展示，人员当前资格不改写原记录。
     * @author owlzhangfq@gmail.com
     */
    public record View(ApprovalProxy proxy, ApprovalProxy.Status status, Instant observedAt, String processKey,
                       long definitionVersion, String definitionName, Person principal, Person substitute) { }

    /**
     * 人员身份保持稳定绑定，显示名与本地资格按读取时刻返回。
     * @author owlzhangfq@gmail.com
     */
    public record Person(UUID id, String subject, String displayName, boolean approvalEligible) { }

    /**
     * 分页记录和统一观察时间，不用后台任务维护到期标志。
     * @author owlzhangfq@gmail.com
     */
    public record Page(List<View> items, String nextAfterId, Instant observedAt) { }

    /**
     * 创建只接受范围、人员、期限与原因；字段不变量由领域入口统一验证。
     * @author owlzhangfq@gmail.com
     */
    public record CreateInput(UUID definitionId, UUID principalId, UUID substituteId, Instant startsAt, Instant endsAt, String reason) {
        /** 未知字段显式失败，不能夹带认证身份、角色或伪造撤销状态。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown approval proxy creation field"); }
    }

    /**
     * 撤销必须针对用户已经读取过的修订并补充原因。
     * @author owlzhangfq@gmail.com
     */
    public record RevokeInput(@NotNull @Positive Long expectedRevision, String reason) {
        /** 撤销不能修改原创建范围或模拟执行时间。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown approval proxy revocation field"); }
    }

    /**
     * 幂等回执只标识原写入结果，不携带可能已到期的可办理结论。
     * @author owlzhangfq@gmail.com
     */
    public record Receipt(UUID proxyId, long revision) { }
}
