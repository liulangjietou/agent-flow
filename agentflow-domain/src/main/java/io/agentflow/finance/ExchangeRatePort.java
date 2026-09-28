package io.agentflow.finance;

import io.agentflow.expense.ExpenseExchangeRate;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 法人汇率政策的只读端口，不用系统内的任意示例汇率完成财务核算。
 * @author owlzhangfq@gmail.com
 */
public interface ExchangeRatePort {
    /** 返回所请求日期和币种对的真实版本化来源。 */
    FinanceResult<ExpenseExchangeRate> rate(String tenantId, UUID legalEntityId, String fromCurrency, String toCurrency, LocalDate rateDate);
}
