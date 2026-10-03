package io.agentflow.organization;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.util.UUID;

/**
 * 本地人员引用 OIDC 的稳定主体，不保存密码或授予系统角色。
 * @author owlzhangfq@gmail.com
 */
public record OrganizationPerson(UUID id, String subject, String displayName, boolean active,
                                 boolean approvalEligible, long revision) {
    /** 主体保持身份源原值，不能 trim、改大小写或借用显示名称匹配身份。 */
    public OrganizationPerson {
        if (id == null || StringUtils.isBlank(subject) || subject.length() > 128
                || subject.chars().anyMatch(Character::isISOControl)
                || StringUtils.isBlank(displayName) || displayName.length() > 128
                || displayName.chars().anyMatch(Character::isISOControl) || revision < 1) {
            throw new DomainException("INVALID_ORGANIZATION_PERSON", "Organization person fields are invalid");
        }
        displayName = displayName.strip();
    }

    /** 审批资格同时要求人员仍在用；身份源系统权限在办理入口另外复核。 */
    public boolean canApprove() { return active && approvalEligible; }

    /** 修改本地状态，不允许替换既有人员的稳定身份绑定。 */
    public OrganizationPerson revise(String displayName, boolean active, boolean approvalEligible, long expectedRevision) {
        OrganizationRevision.require(revision, expectedRevision);
        return new OrganizationPerson(id, subject, displayName, active, approvalEligible, revision + 1);
    }
}
