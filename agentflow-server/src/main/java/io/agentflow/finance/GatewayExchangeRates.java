package io.agentflow.finance;

import io.agentflow.expense.ExpenseExchangeRate;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * 汇率从明确法人政策读取，响应必须对应请求的币种对及日期。
 * @author owlzhangfq@gmail.com
 */
@Component
public class GatewayExchangeRates implements ExchangeRatePort {
    private final FinanceGatewayClient client;

    /** 汇率差异属于财务事实，不由 HTTP 传输或页面推测。 */
    public GatewayExchangeRates(FinanceGatewayClient client) { this.client = client; }

    /** 包括同币种在内均记录真实来源，不静默补造汇率。 */
    @Override public FinanceResult<ExpenseExchangeRate> rate(String tenantId, UUID legalEntityId, String fromCurrency, String toCurrency, LocalDate rateDate) {
        return client.read(tenantId, FinanceGatewayClient.Operation.EXCHANGE_RATE,
                Map.of("legalEntityId", legalEntityId, "fromCurrency", fromCurrency, "toCurrency", toCurrency, "rateDate", rateDate),
                ExpenseExchangeRate.class, value -> fromCurrency.equals(value.fromCurrency()) && toCurrency.equals(value.toCurrency()) && rateDate.equals(value.rateDate()));
    }
}
