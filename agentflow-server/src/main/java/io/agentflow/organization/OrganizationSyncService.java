package io.agentflow.organization;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static io.agentflow.organization.OrganizationSyncBatch.*;

/**
 * 同步批次的管理与持久读取编排；工作器只领取一次，人工应用仍由独立组织用例执行。
 * @author owlzhangfq@gmail.com
 */
@Service
public class OrganizationSyncService {
    private static final long LEASE_GRACE_SECONDS = 5;
    private final OrganizationRepository organization;
    private final JdbcOrganizationSyncRepository batches;
    private final JdbcOrganizationSyncPlanRepository plans;
    private final OrganizationSyncConfiguration configuration;

    /** 不持有网络客户端，所有持锁方法只处理本地状态。 */
    public OrganizationSyncService(OrganizationRepository organization, JdbcOrganizationSyncRepository batches,
                                    JdbcOrganizationSyncPlanRepository plans, OrganizationSyncConfiguration configuration) {
        this.organization = organization; this.batches = batches; this.plans = plans; this.configuration = configuration;
    }

    /** 配置检查不访问来源或创建记录；首次来源注册仅发生在明确排队时。 */
    @Transactional(readOnly = true)
    public Overview status(Actor actor) {
        actor.requireRole("ADMIN"); var target = configuration.destination(actor.tenantId()); var registered = batches.source(actor.tenantId());
        return new Overview(organization.initialized(actor.tenantId()), target.isPresent(), configuration.isEnabled() && configuration.isWorkerEnabled(),
                target.map(OrganizationSyncConfiguration.Destination::sourceKey).orElse(null), registered.map(OrganizationSyncSource::sourceKey).orElse(null),
                registered.map(OrganizationSyncSource::version).orElse(0L), registered.map(OrganizationSyncSource::appliedRevision).orElse(0L),
                target.map(value -> value.digest(actor.tenantId())).orElse(null), batches.pendingId(actor.tenantId()).orElse(null));
    }

    /** 管理员确认当前目标后排队；一个租户的未处理批次不能被另一个批次覆盖。 */
    @Transactional
    public Receipt queue(Actor actor, long expectedSourceVersion, String targetDigest) {
        actor.requireRole("ADMIN"); organization.lock(actor.tenantId());
        return create(actor, expectedSourceVersion, targetDigest, null);
    }

    /** 重试追加新批次与原批次引用，原失败、取消和人工意见保持不变。 */
    @Transactional
    public Receipt retry(Actor actor, UUID id, long expectedVersion, long expectedSourceVersion, String targetDigest) {
        actor.requireRole("ADMIN"); organization.lock(actor.tenantId()); source(actor.tenantId()); var old = locked(actor.tenantId(), id);
        if (old.state().version() != expectedVersion) throw conflict();
        if (old.state().status() != Status.FAILED && old.state().status() != Status.CANCELLED) throw stateConflict();
        return create(actor, expectedSourceVersion, targetDigest, id);
    }

    /** 即使来源配置已经撤销，也允许管理员取消未应用批次。 */
    @Transactional
    public Receipt cancel(Actor actor, UUID id, long expectedVersion, String comment) {
        actor.requireRole("ADMIN"); organization.lock(actor.tenantId()); source(actor.tenantId()); var batch = locked(actor.tenantId(), id);
        batch.cancel(expectedVersion, actor.userId(), comment, time(Instant.now())); batches.update(batch, expectedVersion); return receipt(batch);
    }

    /** 有界历史索引只限当前租户，读取不依赖外部来源可用性。 */
    @Transactional(readOnly = true)
    public JdbcOrganizationSyncRepository.Page list(Actor actor, int page, int size) {
        actor.requireRole("ADMIN"); return batches.page(actor.tenantId(), page, size);
    }

    /** 历史返回原事实与实际采用计划，当前可预检状态不替代应用时的版本复核。 */
    @Transactional(readOnly = true)
    public Detail get(Actor actor, UUID id) {
        actor.requireRole("ADMIN"); var batch = batches.find(actor.tenantId(), id).orElseThrow(OrganizationSyncService::notFound);
        String reason = batch.state().status() != Status.RECEIVED ? "ORGANIZATION_SYNC_NOT_RECEIVED"
                : !configuration.matches(batch.context()) ? "ORGANIZATION_SYNC_SOURCE_CHANGED" : null;
        var applied = batch.state().status() == Status.APPLIED ? plans.appliedPlan(actor.tenantId(), id) : java.util.Optional.<JdbcOrganizationSyncPlanRepository.Saved>empty();
        var context = batch.context();
        return new Detail(new Request(context.id(), context.sourceKey(), context.afterRevision(), context.requestedBy(), context.createdAt(), context.retryOf()),
                batch.state(), applied.map(value -> value.plan().id()).orElse(null), reason == null, reason);
    }

    /** 轨迹按本次读取的批次版本返回，失败及取消均保留原时间。 */
    @Transactional(readOnly = true)
    public List<JdbcOrganizationSyncRepository.Transition> transitions(Actor actor, UUID id) {
        actor.requireRole("ADMIN"); return batches.transitions(batches.find(actor.tenantId(), id).orElseThrow(OrganizationSyncService::notFound));
    }

    /** 所有预检均可从批次历史发现，不要求管理员保留旧浏览器状态。 */
    @Transactional(readOnly = true)
    public JdbcOrganizationSyncPlanRepository.Page plans(Actor actor, UUID id, int page, int size) {
        actor.requireRole("ADMIN"); batches.find(actor.tenantId(), id).orElseThrow(OrganizationSyncService::notFound);
        return plans.page(actor.tenantId(), id, page, size);
    }

    /** 领取固定原租约，崩溃后的执行中批次到期只记录失败，不再次外发。 */
    @Transactional
    public Claim claim(String tenant, UUID id, Instant at) {
        organization.lock(tenant); source(tenant); var batch = locked(tenant, id); Instant now = time(at);
        if (batch.expired(now)) { fail(batch, Failure.SOURCE_TIMEOUT, now); return null; }
        if (batch.state().status() != Status.QUEUED) return null;
        long seconds = configuration.destination(tenant).map(value -> value.timeout().toSeconds()).orElse(0L) + LEASE_GRACE_SECONDS;
        batch.start(1, now, now.plusSeconds(seconds)); batches.update(batch, 1);
        if (!current(batch)) { fail(batch, Failure.SOURCE_CHANGED, now); return null; }
        return new Claim(batch.context(), batch.state().leaseUntil());
    }

    /** 外发前再次检查取消、原租约与目标，不能仅信任内存中的领取结果。 */
    @Transactional
    public boolean sendable(Claim claim, Instant at) {
        var batch = running(claim); if (batch == null) return false; Instant now = time(at);
        if (batch.expired(now)) { fail(batch, Failure.SOURCE_TIMEOUT, now); return false; }
        if (!current(batch)) { fail(batch, Failure.SOURCE_CHANGED, now); return false; }
        return true;
    }

    /** 迟到结果不能覆盖取消或失败，配置变化也不能把旧目标结果变成新来源事实。 */
    @Transactional
    public void finish(Claim claim, HttpOrganizationSyncSource.Result result, Instant at) {
        var batch = running(claim); if (batch == null) return; Instant now = time(at);
        if (batch.expired(now)) { fail(batch, Failure.SOURCE_TIMEOUT, now); return; }
        if (!current(batch)) { fail(batch, Failure.SOURCE_CHANGED, now); return; }
        if (result.failure() != null) { fail(batch, result.failure(), now); return; }
        try { batch.receive(2, result.delta(), now); }
        catch (DomainException invalid) { fail(batch, Failure.INVALID_SOURCE_DATA, now); return; }
        batches.update(batch, 2);
    }

    private Receipt create(Actor actor, long expectedVersion, String targetDigest, UUID retryOf) {
        var destination = configuration.require(actor.tenantId());
        if (!destination.digest(actor.tenantId()).equals(targetDigest)) throw changed();
        var existing = batches.source(actor.tenantId());
        if (existing.map(OrganizationSyncSource::version).orElse(0L) != expectedVersion) throw conflict();
        if (existing.isEmpty()) batches.register(new OrganizationSyncSource(actor.tenantId(), destination.sourceKey(), 0, 1, null, actor.userId(), time(Instant.now())));
        var source = source(actor.tenantId()); if (!source.sourceKey().equals(destination.sourceKey())) throw changed();
        var batch = new OrganizationSyncBatch(new Context(UUID.randomUUID(), actor.tenantId(), source.sourceKey(), source.appliedRevision(),
                targetDigest, actor.userId(), time(Instant.now()), retryOf)); batches.create(batch); return receipt(batch);
    }
    private OrganizationSyncBatch running(Claim claim) {
        String tenant = claim.context().tenantId(); organization.lock(tenant); source(tenant); var batch = locked(tenant, claim.context().id());
        return batch.state().status() == Status.FETCHING && batch.context().equals(claim.context()) && batch.state().leaseUntil().equals(claim.leaseUntil()) ? batch : null;
    }
    private boolean current(OrganizationSyncBatch batch) {
        return configuration.matches(batch.context()) && batches.source(batch.context().tenantId()).filter(value -> value.sourceKey().equals(batch.context().sourceKey())
                && value.appliedRevision() == batch.context().afterRevision()).isPresent();
    }
    private OrganizationSyncSource source(String tenant) {
        if (!batches.lockSource(tenant)) throw notFound(); return batches.source(tenant).orElseThrow(OrganizationSyncService::notFound);
    }
    private OrganizationSyncBatch locked(String tenant, UUID id) {
        if (!batches.lockBatch(tenant, id)) throw notFound(); return batches.find(tenant, id).orElseThrow(OrganizationSyncService::notFound);
    }
    private void fail(OrganizationSyncBatch batch, Failure failure, Instant now) { batch.fail(2, failure, now); batches.update(batch, 2); }
    private static Instant time(Instant at) { return at.truncatedTo(ChronoUnit.MILLIS); }
    private static Receipt receipt(OrganizationSyncBatch batch) { return new Receipt(batch.context().id(), batch.state().status(), batch.state().version()); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Organization synchronization version changed"); }
    private static DomainException changed() { return new DomainException("ORGANIZATION_SYNC_SOURCE_CHANGED", "Organization synchronization source or target changed"); }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Organization synchronization record not found"); }
    private static DomainException stateConflict() { return new DomainException("ORGANIZATION_SYNC_STATE_CONFLICT", "Only failed or cancelled organization synchronization batches can be retried"); }

    /**
     * 管理页的只读配置及持久来源状态，不包含地址、凭据或人员事实。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Overview(boolean initialized, boolean configured, boolean workerEnabled, String sourceKey, String registeredSourceKey,
                           long sourceVersion, long appliedRevision, String targetDigest, UUID activeBatchId) { }
    /**
     * 幂等回执仅缓存批次标识和状态，来源正文通过当前授权的详情读取。
     * @author owlzhangfq@gmail.com
     */
    public record Receipt(UUID id, Status status, long version) { }
    /**
     * 原请求审计不携带部署目标或访问令牌。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Request(UUID id, String sourceKey, long afterRevision, String requestedBy, Instant createdAt, UUID retryOf) { }
    /**
     * 当前租户管理员可读取完整来源和人工决定；失败批次仍保留原请求身份。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Detail(Request request, State state, UUID appliedPlanId, boolean reviewable, String unavailableReason) { }
    /**
     * 领取凭据保留原租约，不能由工作器自行续期。
     * @author owlzhangfq@gmail.com
     */
    public record Claim(Context context, Instant leaseUntil) { }
}
