package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import org.apache.commons.lang3.StringUtils;
import java.util.UUID;

/**
 * 已匹配费用制度的版本化判定，不内置任何企业报销标准或税率。
 * @author owlzhangfq@gmail.com
 */
public record ExpensePolicySnapshot(UUID policyId, long version, Money assessedGross, Money allowedGross,
                                    Decision decision, String taxRuleReference, String evidenceReference) {
    /** 判定与明确上限保持一致；无金额上限的制度仍须返回审核过的适用额度。 */
    public ExpensePolicySnapshot {
        if (policyId == null || version < 1 || assessedGross == null || allowedGross == null || decision == null
                || StringUtils.isBlank(taxRuleReference) || taxRuleReference.length() > 128
                || StringUtils.isBlank(evidenceReference) || evidenceReference.length() > 128) throw invalid();
        int exceeds = assessedGross.compareTo(allowedGross);
        if (decision == Decision.WITHIN_LIMIT && exceeds > 0 || decision == Decision.REQUIRES_EXCEPTION && exceeds <= 0) throw invalid();
    }

    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_POLICY", "A consistent versioned expense and tax policy assessment is required"); }

    /**
     * 超标只表示需要审批例外，不表示制度已经批准超支。
     * @author owlzhangfq@gmail.com
     */
    public enum Decision { WITHIN_LIMIT, REQUIRES_EXCEPTION, DENIED }
}
