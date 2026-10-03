package io.agentflow.expense;

import io.agentflow.common.DomainException;
import java.util.UUID;

/**
 * 一次费用核算固定的制度、类别与生效版本；摘要绑定传给事实源的完整规则正文。
 * @author owlzhangfq@gmail.com
 */
public record ExpensePolicySelection(UUID policyId, long policyVersion, long categoryRevision, long activeRevision, String definitionDigest) {
    /** 未启用平台制度使用外层空选择，不能伪造零版已发布制度。 */
    public ExpensePolicySelection {
        if (policyId == null || policyVersion < 1 || categoryRevision < 1 || activeRevision < 1 || definitionDigest == null
                || !definitionDigest.matches("[a-f0-9]{64}")) throw new DomainException("INVALID_EXPENSE_POLICY_SELECTION", "Managed expense policy selection must bind immutable versions and definition digest");
    }
}
