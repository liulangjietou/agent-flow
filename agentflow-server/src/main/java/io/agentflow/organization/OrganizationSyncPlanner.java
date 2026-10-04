package io.agentflow.organization;

import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import static io.agentflow.organization.OrganizationSyncPlan.*;

/**
 * 把可信增量投影到当前租户目录，预检只生成前后值和冲突，不执行组织写入。
 * @author owlzhangfq@gmail.com
 */
@Component
public final class OrganizationSyncPlanner {
    private final OrganizationRepository organization;
    private final JdbcOrganizationSyncRepository sync;

    /** 原目录与稳定映射是预检仅有的事实来源，不根据显示名称配对。 */
    public OrganizationSyncPlanner(OrganizationRepository organization, JdbcOrganizationSyncRepository sync) {
        this.organization = organization; this.sync = sync;
    }

    /** 调用方持目录、来源和批次锁，保证核对版本与全部读取属于同一目录状态。 */
    public OrganizationSyncPlan prepare(Actor actor, OrganizationSyncBatch batch, long sourceVersion, long directoryRevision,
                                        List<Selection> selections, Instant at) {
        return new Projection(actor.tenantId(), batch.state().delta(), selections).plan(actor, batch, sourceVersion, directoryRevision, at);
    }

    /**
     * 单次预检的有界投影缓存；对象引用可以先指向本批稍后出现的事实。
     * @author owlzhangfq@gmail.com
     */
    private final class Projection {
        private final String tenant;
        private final OrganizationSyncDelta delta;
        private final List<Selection> selections;
        private final Map<OrganizationSyncKey, Target> targets = new LinkedHashMap<>();
        private final Map<OrganizationSyncKey, Conflict> conflicts = new LinkedHashMap<>();
        private final Map<UUID, OrganizationUnit> units = new HashMap<>();
        private final Map<UUID, OrganizationPerson> people = new HashMap<>();
        private final Map<UUID, OrganizationAppointment> appointments = new HashMap<>();
        private final List<Change<OrganizationUnit>> unitChanges = new ArrayList<>();
        private final List<Change<OrganizationPerson>> personChanges = new ArrayList<>();
        private final List<Change<OrganizationAppointment>> appointmentChanges = new ArrayList<>();

        private Projection(String tenant, OrganizationSyncDelta delta, List<Selection> selections) {
            this.tenant = tenant; this.delta = delta; this.selections = List.copyOf(selections);
            if (selections.size() > OrganizationSyncDelta.MAX_RECORDS) throw invalidSelection();
            var keys = new ArrayList<OrganizationSyncKey>();
            delta.units().forEach(value -> keys.add(value.key())); delta.people().forEach(value -> keys.add(value.key()));
            delta.appointments().forEach(value -> keys.add(value.key()));
            var choices = new HashMap<OrganizationSyncKey, Selection>();
            for (var choice : selections) if (!keys.contains(choice.key()) || choices.putIfAbsent(choice.key(), choice) != null) throw invalidSelection();
            var locals = new HashMap<String, OrganizationSyncKey>();
            for (var key : keys) inspect(key, () -> {
                var binding = sync.binding(tenant, key).orElse(null); var choice = choices.get(key);
                UUID localId = binding != null ? binding.localId() : choice != null ? choice.localId() : UUID.randomUUID();
                Object before = binding != null || choice != null ? existing(key, localId) : null;
                if (choice != null && (!localId.equals(choice.localId()) || revision(before) != choice.expectedRevision())) {
                    conflict(key, "ORGANIZATION_SYNC_SELECTION_STALE", localId);
                }
                if (binding != null && binding.localRevision() != revision(before) && choice == null) {
                    conflict(key, "ORGANIZATION_SYNC_LOCAL_CHANGED", localId);
                }
                var owner = sync.bindingByLocal(tenant, key.kind(), localId).orElse(null);
                if (owner != null && !owner.key().equals(key)) conflict(key, "ORGANIZATION_SYNC_ALREADY_BOUND", localId);
                if (locals.putIfAbsent(key.kind() + ":" + localId, key) != null) conflict(key, "ORGANIZATION_SYNC_DUPLICATE_LOCAL", localId);
                targets.put(key, new Target(localId, before, binding == null ? 0 : binding.version(), choice != null));
                return null;
            });
        }

        private OrganizationSyncPlan plan(Actor actor, OrganizationSyncBatch batch, long sourceVersion, long directoryRevision, Instant at) {
            for (var fact : delta.units()) inspect(fact.key(), () -> {
                var target = target(fact.key()); var before = (OrganizationUnit) target.before();
                UUID legal = reference(fact.legalEntity()), parent = reference(fact.parentDepartment()), head = reference(fact.headAppointment());
                if (before != null && !Objects.equals(before.legalEntityId(), legal)) throw immutable();
                var after = before == null ? new OrganizationUnit(target.id(), OrganizationUnit.Kind.valueOf(fact.key().kind().name()), fact.name(), legal, parent, fact.active(), 1) : before;
                if (before != null && (!before.name().equals(fact.name()) || !Objects.equals(before.parentDepartmentId(), parent) || before.active() != fact.active())) {
                    after = before.revise(fact.name(), parent, fact.active(), before.revision());
                }
                if (!Objects.equals(after.headAppointmentId(), head)) after = after.withHead(head, after.revision());
                units.put(after.id(), after); unitChanges.add(target.change(fact.key(), before, after)); return null;
            });
            var subjects = new HashMap<String, UUID>();
            for (var fact : delta.people()) inspect(fact.key(), () -> {
                var target = target(fact.key()); var before = (OrganizationPerson) target.before();
                if (before != null && !before.subject().equals(fact.subject())) throw immutable();
                var existing = organization.personBySubject(tenant, fact.subject()).orElse(null);
                if (existing != null && !existing.id().equals(target.id())) {
                    conflict(fact.key(), "ORGANIZATION_SYNC_ADOPTION_REQUIRED", existing.id()); return null;
                }
                UUID previous = subjects.putIfAbsent(fact.subject(), target.id());
                if (previous != null && !previous.equals(target.id())) throw duplicate();
                var after = before == null ? new OrganizationPerson(target.id(), fact.subject(), fact.displayName(), fact.active(), fact.approvalEligible(), 1) : before;
                if (before != null && (!before.displayName().equals(fact.displayName()) || before.active() != fact.active() || before.approvalEligible() != fact.approvalEligible())) {
                    after = before.revise(fact.displayName(), fact.active(), fact.approvalEligible(), before.revision());
                }
                people.put(after.id(), after); personChanges.add(target.change(fact.key(), before, after)); return null;
            });
            var identities = new HashMap<List<UUID>, UUID>();
            for (var fact : delta.appointments()) inspect(fact.key(), () -> {
                var target = target(fact.key()); var before = (OrganizationAppointment) target.before();
                UUID person = reference(fact.person()), department = reference(fact.department()), position = reference(fact.position()), supervisor = reference(fact.supervisorAppointment());
                if (before != null && (!before.personId().equals(person) || !before.departmentId().equals(department) || !before.positionId().equals(position))) throw immutable();
                var existing = organization.appointmentByIdentity(tenant, person, department, position).orElse(null);
                if (existing != null && !existing.id().equals(target.id())) {
                    conflict(fact.key(), "ORGANIZATION_SYNC_ADOPTION_REQUIRED", existing.id()); return null;
                }
                UUID previous = identities.putIfAbsent(List.of(person, department, position), target.id());
                if (previous != null && !previous.equals(target.id())) throw duplicate();
                var after = before == null ? new OrganizationAppointment(target.id(), person, department, position, fact.active(), 1) : before;
                if (before != null && before.active() != fact.active()) after = before.revise(fact.active(), before.revision());
                if (!Objects.equals(after.supervisorAppointmentId(), supervisor)) after = after.withSupervisor(supervisor, after.revision());
                appointments.put(after.id(), after); appointmentChanges.add(target.change(fact.key(), before, after)); return null;
            });
            var relations = new OrganizationRelations(this::unit, this::person, this::appointment);
            for (var value : unitChanges) inspect(value.key(), () -> {
                relations.unit(value.after());
                // 停用与改名保留历史负责人，只有重新配置关系才核对当次任命资格。
                if (value.after().kind() == OrganizationUnit.Kind.DEPARTMENT && (value.before() == null
                        || !Objects.equals(value.before().headAppointmentId(), value.after().headAppointmentId()))) relations.head(value.after());
                return null;
            });
            for (var value : appointmentChanges) inspect(value.key(), () -> {
                relations.appointment(value.after());
                if (value.before() == null || !Objects.equals(value.before().supervisorAppointmentId(), value.after().supervisorAppointmentId())) {
                    relations.supervisor(value.after());
                }
                return null;
            });
            return new OrganizationSyncPlan(UUID.randomUUID(), tenant, batch.context().id(), batch.state().version(), sourceVersion,
                    directoryRevision, actor.userId(), at, selections, unitChanges, personChanges, appointmentChanges, List.copyOf(conflicts.values()));
        }

        private Target target(OrganizationSyncKey key) {
            var target = targets.get(key); if (target == null) throw missing(); return target;
        }
        private UUID reference(OrganizationSyncKey key) {
            if (key == null) return null;
            if (targets.containsKey(key)) return targets.get(key).id();
            var binding = sync.binding(tenant, key).orElseThrow(OrganizationSyncPlanner::missing);
            existing(key, binding.localId()); return binding.localId();
        }
        private Object existing(OrganizationSyncKey key, UUID id) {
            return switch (key.kind()) {
                case PERSON -> person(id);
                case APPOINTMENT -> appointment(id);
                default -> {
                    var value = unit(id); if (!value.kind().name().equals(key.kind().name())) throw immutable(); yield value;
                }
            };
        }
        private OrganizationUnit unit(UUID id) { return units.computeIfAbsent(id, value -> organization.unit(tenant, value).orElseThrow(OrganizationSyncPlanner::missing)); }
        private OrganizationPerson person(UUID id) { return people.computeIfAbsent(id, value -> organization.person(tenant, value).orElseThrow(OrganizationSyncPlanner::missing)); }
        private OrganizationAppointment appointment(UUID id) { return appointments.computeIfAbsent(id, value -> organization.appointment(tenant, value).orElseThrow(OrganizationSyncPlanner::missing)); }
        private void conflict(OrganizationSyncKey key, String code, UUID id) { conflicts.putIfAbsent(key, new Conflict(key, code, id)); }
        private void inspect(OrganizationSyncKey key, Supplier<Void> action) {
            try { action.get(); }
            catch (DomainException rejected) { conflict(key, rejected.code(), targets.containsKey(key) ? targets.get(key).id() : null); }
        }
    }

    /**
     * 解析后的稳定本地目标与上次绑定版本，仅在单次预检中使用。
     * @author owlzhangfq@gmail.com
     */
    private record Target(UUID id, Object before, long bindingVersion, boolean selected) {
        private <T> Change<T> change(OrganizationSyncKey key, T before, T after) { return new Change<>(key, bindingVersion, before, after, selected); }
    }
    private static DomainException missing() { return new DomainException("ORGANIZATION_SYNC_REFERENCE_MISSING", "A referenced organization source or local record is missing"); }
    private static DomainException immutable() { return new DomainException("ORGANIZATION_SYNC_IDENTITY_IMMUTABLE", "Organization identity or ownership cannot be replaced by synchronization"); }
    private static DomainException duplicate() { return new DomainException("ORGANIZATION_SYNC_DUPLICATE_IDENTITY", "Multiple source records claim the same organization identity"); }
    private static DomainException invalidSelection() { return new DomainException("INVALID_ORGANIZATION_SYNC_SELECTION", "Selections must uniquely identify facts in this batch"); }
}
