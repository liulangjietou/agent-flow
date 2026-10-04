package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;

/**
 * 外部明确拒绝原冻结命令后给出的例外凭据，原命令身份由所在结果绑定。
 * @author owlzhangfq@gmail.com
 */
public record BudgetExceptionOffer(String policyReference, String reference) {
    /** 政策与本次凭据必须同时存在，普通预算不足没有此含义。 */
    public BudgetExceptionOffer {
        if (StringUtils.isBlank(policyReference) || policyReference.length() > 128 || StringUtils.isBlank(reference) || reference.length() > 128) {
            throw new DomainException("INVALID_BUDGET_EXCEPTION_OFFER", "Budget exception offer and policy references are required");
        }
    }
}
