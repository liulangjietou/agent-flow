package io.agentflow.organization;

import io.agentflow.common.DomainException;
import java.util.UUID;

/**
 * 人员在具体部门和岗位的任职事实；一人可拥有多个不同任职。
 * @author owlzhangfq@gmail.com
 */
public record OrganizationAppointment(UUID id, UUID personId, UUID departmentId, UUID positionId,
                                      boolean active, long revision) {
    /** 任职的身份和归属必须明确，不能把空字段解释为所有部门或所有岗位。 */
    public OrganizationAppointment {
        if (id == null || personId == null || departmentId == null || positionId == null || revision < 1) {
            throw new DomainException("INVALID_ORGANIZATION_APPOINTMENT", "Organization appointment fields are invalid");
        }
    }

    /** 任职结束通过停用保留事实；调岗创建新任职，不改写历史关系。 */
    public OrganizationAppointment revise(boolean active, long expectedRevision) {
        OrganizationRevision.require(revision, expectedRevision);
        return new OrganizationAppointment(id, personId, departmentId, positionId, active, revision + 1);
    }
}
