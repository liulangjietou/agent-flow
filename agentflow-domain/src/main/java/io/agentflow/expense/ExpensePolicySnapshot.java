package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import org.apache.commons.lang3.StringUtils;
import java.util.UUID;
import java.util.List;

/**
 * 已匹配费用制度的版本化判定，不内置任何企业报销标准或税率。
 * @author owlzhangfq@gmail.com
 */
public record ExpensePolicySnapshot(UUID policyId, long version, Money assessedGross, Money allowedGross,
                                    Decision decision, String taxRuleReference, String evidenceReference,
                                    List<ExceptionReason> exceptionReasons, ExpensePolicyReceipt managedPolicy) {
    /** 历史金额超标判定保留原语义，没有平台配置回执时不能冒充使用了新制度。 */
    public ExpensePolicySnapshot(UUID policyId, long version, Money assessedGross, Money allowedGross,
                                 Decision decision, String taxRuleReference, String evidenceReference) {
        this(policyId, version, assessedGross, allowedGross, decision, taxRuleReference, evidenceReference, null, null);
    }
    /** 判定与明确上限保持一致；无金额上限的制度仍须返回审核过的适用额度。 */
    public ExpensePolicySnapshot {
        if (policyId == null || version < 1 || assessedGross == null || allowedGross == null || decision == null
                || StringUtils.isBlank(taxRuleReference) || taxRuleReference.length() > 128
                || StringUtils.isBlank(evidenceReference) || evidenceReference.length() > 128) throw invalid();
        int exceeds = assessedGross.compareTo(allowedGross);
        if (exceptionReasons == null) exceptionReasons = decision == Decision.REQUIRES_EXCEPTION && exceeds > 0 ? List.of(ExceptionReason.AMOUNT) : List.of();
        if (exceptionReasons.stream().anyMatch(java.util.Objects::isNull) || exceptionReasons.stream().distinct().count() != exceptionReasons.size()
                || exceptionReasons.size() > ExceptionReason.values().length) throw invalid();
        exceptionReasons = List.copyOf(exceptionReasons);
        if (decision == Decision.WITHIN_LIMIT && (exceeds > 0 || !exceptionReasons.isEmpty())
                || decision == Decision.REQUIRES_EXCEPTION && (exceptionReasons.isEmpty() || exceptionReasons.contains(ExceptionReason.AMOUNT) != (exceeds > 0))
                || decision == Decision.DENIED && !exceptionReasons.isEmpty()
                || managedPolicy != null && (!policyId.equals(managedPolicy.selection().policyId()) || version != managedPolicy.selection().policyVersion())) throw invalid();
    }

    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_POLICY", "A consistent versioned expense and tax policy assessment is required"); }

    /**
     * 超标只表示需要审批例外，不表示制度已经批准超支。
     * @author owlzhangfq@gmail.com
     */
    public enum Decision { WITHIN_LIMIT, REQUIRES_EXCEPTION, DENIED }
    /**
     * 非金额例外必须有明确种类，不能靠伪造低额度来表示舱位或票据时限超标。
     * @author owlzhangfq@gmail.com
     */
    public enum ExceptionReason { AMOUNT, SERVICE_LEVEL, INVOICE_AGE }
}
