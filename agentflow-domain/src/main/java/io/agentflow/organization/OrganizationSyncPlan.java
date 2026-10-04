package io.agentflow.organization;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import static io.agentflow.organization.OrganizationSyncKey.*;

/**
 * 可持久核对的同步计划，保留人工选择、实体前后值和阻断原因；不由客户端提交目标实体。
 * @author owlzhangfq@gmail.com
 */
public record OrganizationSyncPlan(UUID id, String tenantId, UUID batchId, long batchVersion, long sourceVersion,
                                   long directoryRevision, String preparedBy, Instant preparedAt, List<Selection> selections,
                                   List<Change<OrganizationUnit>> units, List<Change<OrganizationPerson>> people,
                                   List<Change<OrganizationAppointment>> appointments, List<Conflict> conflicts) {
    /** 计划固定收到的批次及当前目录，之后的任意目录写入使计划过期。 */
    public OrganizationSyncPlan {
        if (id == null || batchId == null || batchVersion != 3 || sourceVersion < 1 || directoryRevision < 1 || preparedAt == null) throw invalid();
        text(tenantId, 64); text(preparedBy, 128);
        selections = List.copyOf(selections); units = List.copyOf(units); people = List.copyOf(people);
        appointments = List.copyOf(appointments); conflicts = List.copyOf(conflicts);
        if (units.size() + people.size() + appointments.size() > OrganizationSyncDelta.MAX_RECORDS
                || selections.size() > OrganizationSyncDelta.MAX_RECORDS) throw invalid();
        var keys = new HashSet<OrganizationSyncKey>();
        for (var change : units) if (!keys.add(change.key()) || change.key().kind() != Kind.valueOf(change.after().kind().name())) throw invalid();
        for (var change : people) if (!keys.add(change.key()) || change.key().kind() != Kind.PERSON) throw invalid();
        for (var change : appointments) if (!keys.add(change.key()) || change.key().kind() != Kind.APPOINTMENT) throw invalid();
    }

    /** 只有没有冲突的计划才可以由管理员明确应用。 */
    public boolean ready() { return conflicts.isEmpty(); }

    /** 仅计算实际组织实体变更，映射确认不制造虚假的组织修订。 */
    public long changedRecords() {
        return units.stream().filter(Change::modified).count() + people.stream().filter(Change::modified).count()
                + appointments.stream().filter(Change::modified).count();
    }

    /**
     * 明确采用某个当前本地实体；同时作为覆盖该实体人工改动的具名选择依据。
     * @author owlzhangfq@gmail.com
     */
    public record Selection(OrganizationSyncKey key, UUID localId, long expectedRevision) {
        /** 无版本的覆盖和空目标绑定均不属于有效选择。 */
        public Selection { if (key == null || localId == null || expectedRevision < 1) throw invalid(); }
    }

    /**
     * 一条来源事实的本地前后值，绑定版本用于应用时的乐观并发检查。
     * @author owlzhangfq@gmail.com
     */
    public record Change<T>(OrganizationSyncKey key, long bindingVersion, T before, T after, boolean explicitlySelected) {
        /** 新实体没有前值；原实体只能保持身份并前进自身修订。 */
        public Change {
            if (key == null || bindingVersion < 0 || after == null || before == null && bindingVersion != 0) throw invalid();
            id(after); revision(after);
            if (before != null && (!before.getClass().equals(after.getClass()) || !id(before).equals(id(after))
                    || revision(after) < revision(before))) throw invalid();
        }
        /** 返回计划已经固定的本地身份。 */
        public UUID localId() { return id(after); }
        /** 新增使用零修订，已有实体使用核对时的真实修订。 */
        public long expectedRevision() { return before == null ? 0 : revision(before); }
        /** 相同来源值只确认映射，不写重复组织变更。 */
        public boolean modified() { return !Objects.equals(before, after); }
    }

    /**
     * 预检只返回受控冲突码及当前租户的本地目标，不暴露上游异常正文。
     * @author owlzhangfq@gmail.com
     */
    public record Conflict(OrganizationSyncKey key, String code, UUID localId) {
        /** 冲突始终定位到来源事实；没有本地目标时 localId 为空。 */
        public Conflict { if (key == null) throw invalid(); text(code, 80); }
    }

    /** 对三种真实组织实体提取稳定身份，其他对象不能进入计划。 */
    public static UUID id(Object value) {
        if (value instanceof OrganizationUnit unit) return unit.id();
        if (value instanceof OrganizationPerson person) return person.id();
        if (value instanceof OrganizationAppointment appointment) return appointment.id();
        throw invalid();
    }
    /** 计划与绑定共同使用实体自身修订，不能拿目录修订替代。 */
    public static long revision(Object value) {
        if (value instanceof OrganizationUnit unit) return unit.revision();
        if (value instanceof OrganizationPerson person) return person.revision();
        if (value instanceof OrganizationAppointment appointment) return appointment.revision();
        throw invalid();
    }
}
