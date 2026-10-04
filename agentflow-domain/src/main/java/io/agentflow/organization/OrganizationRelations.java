package io.agentflow.organization;

import io.agentflow.common.DomainException;
import java.util.HashSet;
import java.util.UUID;
import java.util.function.Function;

/**
 * 单笔维护与同步预检共用关系规则，读取函数决定检查当前目录还是计划中的最终目录。
 * @author owlzhangfq@gmail.com
 */
public final class OrganizationRelations {
    private final Function<UUID, OrganizationUnit> units;
    private final Function<UUID, OrganizationPerson> people;
    private final Function<UUID, OrganizationAppointment> appointments;

    /** 读取函数必须限定同一租户，缺失引用由调用方报告。 */
    public OrganizationRelations(Function<UUID, OrganizationUnit> units, Function<UUID, OrganizationPerson> people,
                                 Function<UUID, OrganizationAppointment> appointments) {
        this.units = units; this.people = people; this.appointments = appointments;
    }

    /** 单元归属和整条部门层级必须属于同一法人，活动单元要求活动上级。 */
    public void unit(OrganizationUnit value) {
        if (value.kind() == OrganizationUnit.Kind.LEGAL_ENTITY) return;
        var legal = kind(value.legalEntityId(), OrganizationUnit.Kind.LEGAL_ENTITY);
        if (value.active() && !legal.active()) throw inactive();
        UUID parent = value.parentDepartmentId();
        var seen = new HashSet<UUID>(); seen.add(value.id());
        while (parent != null) {
            if (!seen.add(parent)) throw new DomainException("ORGANIZATION_DEPARTMENT_CYCLE", "Department hierarchy cannot contain a cycle");
            var department = kind(parent, OrganizationUnit.Kind.DEPARTMENT);
            if (!value.legalEntityId().equals(department.legalEntityId())) throw relation();
            if (value.active() && !department.active()) throw inactive();
            parent = department.parentDepartmentId();
        }
    }

    /** 任职引用同法人部门和岗位；活动任职要求各个引用均在用。 */
    public void appointment(OrganizationAppointment value) {
        var person = people.apply(value.personId());
        var department = kind(value.departmentId(), OrganizationUnit.Kind.DEPARTMENT);
        var position = kind(value.positionId(), OrganizationUnit.Kind.POSITION);
        if (!department.legalEntityId().equals(position.legalEntityId())) throw relation();
        var legal = kind(department.legalEntityId(), OrganizationUnit.Kind.LEGAL_ENTITY);
        if (value.active() && (!person.active() || !department.active() || !position.active() || !legal.active())) throw inactive();
    }

    /** 主管链不能重复任职或人员，主管必须保持有效审批资格。 */
    public void supervisor(OrganizationAppointment value) {
        var legal = units.apply(value.departmentId()).legalEntityId();
        var seen = new HashSet<UUID>(); var subjects = new HashSet<UUID>();
        seen.add(value.id()); subjects.add(value.personId());
        UUID current = value.supervisorAppointmentId();
        while (current != null) {
            if (!seen.add(current)) throw supervisorCycle();
            var supervisor = appointments.apply(current);
            if (!subjects.add(supervisor.personId())) throw supervisorCycle();
            if (!legal.equals(units.apply(supervisor.departmentId()).legalEntityId())) throw relation();
            effective(supervisor); current = supervisor.supervisorAppointmentId();
        }
    }

    /** 部门负责人必须在本部门有效任职，清空负责人不删除历史任职。 */
    public void head(OrganizationUnit value) {
        if (value.kind() != OrganizationUnit.Kind.DEPARTMENT) throw relation();
        if (value.headAppointmentId() != null) {
            var head = appointments.apply(value.headAppointmentId());
            if (!value.id().equals(head.departmentId())) throw relation();
            effective(head);
        }
    }

    private void effective(OrganizationAppointment value) {
        if (!value.active() || !people.apply(value.personId()).canApprove()) throw inactive();
        appointment(value);
    }
    private OrganizationUnit kind(UUID id, OrganizationUnit.Kind kind) {
        var value = units.apply(id); if (value.kind() != kind) throw relation(); return value;
    }
    private static DomainException supervisorCycle() { return new DomainException("ORGANIZATION_SUPERVISOR_CYCLE", "Supervisor chain cannot repeat an appointment or person"); }
    private static DomainException relation() { return new DomainException("ORGANIZATION_RELATION_INVALID", "Organization relation type or legal entity does not match"); }
    private static DomainException inactive() { return new DomainException("ORGANIZATION_RELATION_INACTIVE", "An active relation requires active referenced records"); }
}
