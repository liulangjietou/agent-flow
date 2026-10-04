package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;

/**
 * 外部预算明确允许申请例外的政策来源；它不代表审批通过或预算已冻结。
 * @author owlzhangfq@gmail.com
 */
public record BudgetExceptionPolicy(String reference) {
    /** 来源固定在完整分摊的预检中，不接受无来源的柔性开关。 */
    public BudgetExceptionPolicy {
        if (StringUtils.isBlank(reference) || reference.length() > 128) {
            throw new DomainException("INVALID_BUDGET_EXCEPTION_POLICY", "Budget exception policy reference is required");
        }
    }
}
