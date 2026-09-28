package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;

/**
 * 应用服务从可信制度、汇率及发票查验取得的提交事实；制度核算额和可抵扣税额均为本位币。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseAssessment(ExpenseExchangeRate exchangeRate, ExpensePolicySnapshot policy, Money deductibleTax) {
    /** 可抵扣税额必须具有显式本位币；与原币明细折算结果的一致性在冻结时校验。 */
    public ExpenseAssessment {
        if (exchangeRate == null || policy == null || deductibleTax == null) {
            throw new DomainException("EXPENSE_PRECHECK_REQUIRED", "Exchange rate, policy and tax assessment are required");
        }
    }
}
