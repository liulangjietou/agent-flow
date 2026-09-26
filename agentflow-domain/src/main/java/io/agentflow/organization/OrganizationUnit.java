package io.agentflow.organization;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.util.UUID;

/**
 * 法人、部门和岗位共用命名、启停和修订语义；组织归属的跨实体检查由用例服务编排。
 * @author owlzhangfq@gmail.com
 */
public record OrganizationUnit(UUID id, Kind kind, String name, UUID legalEntityId,
                               UUID parentDepartmentId, boolean active, long revision) {
    /** 仅保留各类组织单元适用的结构，拒绝无意义字段。 */
    public OrganizationUnit {
        if (id == null || kind == null || StringUtils.isBlank(name) || name.length() > 128 || revision < 1
                || name.chars().anyMatch(Character::isISOControl)) throw invalid();
        name = name.strip();
        if (kind == Kind.LEGAL_ENTITY && (legalEntityId != null || parentDepartmentId != null)
                || kind != Kind.LEGAL_ENTITY && legalEntityId == null
                || kind != Kind.DEPARTMENT && parentDepartmentId != null
                || id.equals(legalEntityId) || id.equals(parentDepartmentId)) throw invalid();
    }

    /** 修改名称、启停及同法人内部门层级；不能通过编辑把既有实体变成另一类组织。 */
    public OrganizationUnit revise(String name, UUID parentDepartmentId, boolean active, long expectedRevision) {
        OrganizationRevision.require(revision, expectedRevision);
        return new OrganizationUnit(id, kind, name, legalEntityId, parentDepartmentId, active, revision + 1);
    }

    private static DomainException invalid() { return new DomainException("INVALID_ORGANIZATION_UNIT", "Organization unit fields are invalid"); }

    /**
     * 法人拥有部门与岗位，岗位可通过任职分配到同法人的不同部门。
     * @author owlzhangfq@gmail.com
     */
    public enum Kind { LEGAL_ENTITY, DEPARTMENT, POSITION }
}
