package io.agentflow.organization;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static io.agentflow.organization.OrganizationSyncKey.*;

/**
 * 单一来源按连续游标提供的组织事实变更；遗漏表示未变，停用必须明确给出。
 * @author owlzhangfq@gmail.com
 */
public record OrganizationSyncDelta(String sourceKey, long afterRevision, long revision,
                                    List<Unit> units, List<Person> people, List<Appointment> appointments) {
    public static final int MAX_RECORDS = 5000;

    /** 类型和单批唯一性在事实进入领域时校验，跨批引用由组织预检解析。 */
    public OrganizationSyncDelta {
        source(sourceKey);
        if (afterRevision < 0 || revision < afterRevision || units == null || people == null || appointments == null
                || (long) units.size() + people.size() + appointments.size() > MAX_RECORDS
                || units.stream().anyMatch(java.util.Objects::isNull) || people.stream().anyMatch(java.util.Objects::isNull)
                || appointments.stream().anyMatch(java.util.Objects::isNull)) throw invalid();
        units = List.copyOf(units); people = List.copyOf(people); appointments = List.copyOf(appointments);
        Set<OrganizationSyncKey> keys = new HashSet<>();
        for (var value : units) if (!keys.add(value.key())) throw invalid();
        for (var value : people) if (!keys.add(value.key())) throw invalid();
        for (var value : appointments) if (!keys.add(value.key())) throw invalid();
        if (!keys.isEmpty() && revision == afterRevision) throw invalid();
    }

    /** 批次规模包括全部事实类型；空变更也能明确确认来源游标。 */
    public int size() { return units.size() + people.size() + appointments.size(); }

    /**
     * 单元的法人、部门层级和负责人均引用来源稳定标识。
     * @author owlzhangfq@gmail.com
     */
    public record Unit(OrganizationSyncKey key, String name, OrganizationSyncKey legalEntity,
                       OrganizationSyncKey parentDepartment, boolean active, OrganizationSyncKey headAppointment) {
        /** 法人不归属另一法人；只有部门能配置上级部门与负责人。 */
        public Unit {
            if (key == null || !Set.of(Kind.LEGAL_ENTITY, Kind.DEPARTMENT, Kind.POSITION).contains(key.kind())) throw invalid();
            name = text(name, 128).strip();
            reference(legalEntity, Kind.LEGAL_ENTITY, key.kind() == Kind.LEGAL_ENTITY);
            reference(parentDepartment, Kind.DEPARTMENT, true); reference(headAppointment, Kind.APPOINTMENT, true);
            if (key.kind() == Kind.LEGAL_ENTITY && legalEntity != null
                    || key.kind() != Kind.DEPARTMENT && (parentDepartment != null || headAppointment != null)
                    || key.equals(parentDepartment)) throw invalid();
        }
    }

    /**
     * 人员事实引用认证源已有的稳定主体，不包含登录身份创建或系统角色授权。
     * @author owlzhangfq@gmail.com
     */
    public record Person(OrganizationSyncKey key, String subject, String displayName, boolean active, boolean approvalEligible) {
        /** 主体按原值绑定，姓名仅为显示字段。 */
        public Person {
            reference(key, Kind.PERSON, false); text(subject, 128); displayName = text(displayName, 128).strip();
        }
    }

    /**
     * 任职来源标识描述固定人员与部门岗位关系，调岗应提供新的任职标识。
     * @author owlzhangfq@gmail.com
     */
    public record Appointment(OrganizationSyncKey key, OrganizationSyncKey person, OrganizationSyncKey department,
                              OrganizationSyncKey position, boolean active, OrganizationSyncKey supervisorAppointment) {
        /** 主管同样属于明确任职，不能将人员编号解释成任意主任职。 */
        public Appointment {
            reference(key, Kind.APPOINTMENT, false); reference(person, Kind.PERSON, false);
            reference(department, Kind.DEPARTMENT, false); reference(position, Kind.POSITION, false);
            reference(supervisorAppointment, Kind.APPOINTMENT, true);
            if (key.equals(supervisorAppointment)) throw invalid();
        }
    }
}
