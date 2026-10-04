package io.agentflow.organization;

import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static io.agentflow.organization.OrganizationSyncPlan.*;

/**
 * 管理员预检与应用用例：只处理已读取的可信事实，在一个事务内提交组织及全部同步轨迹。
 * @author owlzhangfq@gmail.com
 */
@Service
public class OrganizationSyncApplicationService {
    private final OrganizationRepository organization;
    private final JdbcOrganizationSyncRepository sync;
    private final JdbcOrganizationSyncPlanRepository plans;
    private final OrganizationSyncPlanner planner;
    private final OrganizationSyncConfiguration configuration;

    /** 不注入网络客户端，预检与应用均不在持锁事务中读取外部服务。 */
    public OrganizationSyncApplicationService(OrganizationRepository organization, JdbcOrganizationSyncRepository sync,
                                              JdbcOrganizationSyncPlanRepository plans, OrganizationSyncPlanner planner, OrganizationSyncConfiguration configuration) {
        this.organization = organization; this.sync = sync; this.plans = plans; this.planner = planner; this.configuration = configuration;
    }

    /** 保存完整核对结果，即使存在冲突也保留该次具名预检，不改变组织事实。 */
    @Transactional
    public JdbcOrganizationSyncPlanRepository.Saved preflight(Actor actor, UUID batchId, long expectedVersion, List<Selection> selections) {
        actor.requireRole("ADMIN"); long directoryRevision = organization.lock(actor.tenantId());
        var source = source(actor); var batch = batch(actor, batchId, expectedVersion);
        configuration.requireCurrent(batch.context());
        return plans.create(planner.prepare(actor, batch, source.version(), directoryRevision, selections, now()));
    }

    /** 读取既有计划用于人工核对，不为旧计划拼接当前名称或来源事实。 */
    @Transactional(readOnly = true)
    public JdbcOrganizationSyncPlanRepository.Saved plan(Actor actor, UUID id) {
        actor.requireRole("ADMIN"); return plans.find(actor.tenantId(), id).orElseThrow(OrganizationSyncApplicationService::notFound);
    }

    /** 只应用服务端已保存且仍新鲜的无冲突计划，不接受客户端提供的实体目标值。 */
    @Transactional
    public OrganizationSyncBatch.State apply(Actor actor, UUID batchId, long expectedVersion, UUID planId, String comment) {
        actor.requireRole("ADMIN"); long directoryRevision = organization.lock(actor.tenantId());
        var source = source(actor); var batch = batch(actor, batchId, expectedVersion);
        configuration.requireCurrent(batch.context());
        var saved = plans.find(actor.tenantId(), planId).orElseThrow(OrganizationSyncApplicationService::notFound); var plan = saved.plan();
        if (!plan.batchId().equals(batchId) || plan.batchVersion() != expectedVersion || plan.directoryRevision() != directoryRevision
                || plan.sourceVersion() != source.version() || batch.context().afterRevision() != source.appliedRevision()) throw stale();
        if (!plan.ready()) throw new DomainException("ORGANIZATION_SYNC_PLAN_CONFLICT", "Resolve organization synchronization conflicts before application");
        Instant at = now();
        // 先构造所有新实体的无环基础引用，再保存部门负责人和主管，避免双向外键创建顺序问题。
        createBaseRecords(actor.tenantId(), plan);
        for (var change : plan.units()) if (change.modified()) {
            if (change.before() != null) organization.save(actor.tenantId(), change.after(), change.expectedRevision());
            else if (change.after().headAppointmentId() != null) organization.save(actor.tenantId(), change.after(), 1);
            organization.recordChange(actor.tenantId(), directoryRevision++, actor.userId(), change.key().kind().name(), change.localId(), change.after(), at);
        }
        for (var change : plan.people()) if (change.modified()) {
            if (change.before() != null) organization.save(actor.tenantId(), change.after(), change.expectedRevision());
            organization.recordChange(actor.tenantId(), directoryRevision++, actor.userId(), change.key().kind().name(), change.localId(), change.after(), at);
        }
        for (var change : plan.appointments()) if (change.modified()) {
            if (change.before() != null) organization.save(actor.tenantId(), change.after(), change.expectedRevision());
            else if (change.after().supervisorAppointmentId() != null) organization.save(actor.tenantId(), change.after(), 1);
            organization.recordChange(actor.tenantId(), directoryRevision++, actor.userId(), change.key().kind().name(), change.localId(), change.after(), at);
        }
        batch.apply(expectedVersion, source.appliedRevision(), new OrganizationSyncBatch.Applied(saved.digest(), plan.directoryRevision(), directoryRevision), actor.userId(), comment, at);
        sync.update(batch, expectedVersion);
        for (var change : plan.units()) bind(actor.tenantId(), batch, change, at);
        for (var change : plan.people()) bind(actor.tenantId(), batch, change, at);
        for (var change : plan.appointments()) bind(actor.tenantId(), batch, change, at);
        plans.applied(saved, batch); sync.advance(source.applied(batch, source.version()), source.version());
        return batch.state();
    }

    private void createBaseRecords(String tenant, OrganizationSyncPlan plan) {
        var pending = new ArrayList<>(plan.units().stream().filter(value -> value.before() == null).toList());
        var remainingIds = new HashSet<UUID>(); pending.forEach(value -> remainingIds.add(value.localId()));
        while (!pending.isEmpty()) {
            boolean progressed = false;
            for (var iterator = pending.iterator(); iterator.hasNext();) {
                var value = iterator.next().after();
                if (remainingIds.contains(value.legalEntityId()) || remainingIds.contains(value.parentDepartmentId())) continue;
                organization.save(tenant, new OrganizationUnit(value.id(), value.kind(), value.name(), value.legalEntityId(), value.parentDepartmentId(), value.active(), 1), 0);
                remainingIds.remove(value.id()); iterator.remove(); progressed = true;
            }
            if (!progressed) throw new IllegalStateException("Verified organization synchronization plan contains a dependency cycle");
        }
        for (var value : plan.people()) if (value.before() == null) organization.save(tenant, value.after(), 0);
        for (var change : plan.appointments()) if (change.before() == null) {
            var value = change.after(); organization.save(tenant, new OrganizationAppointment(value.id(), value.personId(), value.departmentId(), value.positionId(), value.active(), 1), 0);
        }
    }

    private void bind(String tenant, OrganizationSyncBatch batch, Change<?> change, Instant at) {
        sync.save(new OrganizationSyncBinding(tenant, batch.context().sourceKey(), change.key(), change.localId(), revision(change.after()),
                batch.state().delta().revision(), change.bindingVersion() + 1, batch.context().id(), at), change.bindingVersion());
    }
    private OrganizationSyncSource source(Actor actor) {
        if (!sync.lockSource(actor.tenantId())) throw notFound(); return sync.source(actor.tenantId()).orElseThrow(OrganizationSyncApplicationService::notFound);
    }
    private OrganizationSyncBatch batch(Actor actor, UUID id, long version) {
        if (!sync.lockBatch(actor.tenantId(), id)) throw notFound();
        var value = sync.find(actor.tenantId(), id).orElseThrow(OrganizationSyncApplicationService::notFound);
        if (value.state().status() != OrganizationSyncBatch.Status.RECEIVED || value.state().version() != version) throw stale(); return value;
    }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MILLIS); }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Organization synchronization record not found"); }
    private static DomainException stale() { return new DomainException("ORGANIZATION_SYNC_PLAN_STALE", "Organization synchronization plan no longer matches the current batch or directory"); }
}
