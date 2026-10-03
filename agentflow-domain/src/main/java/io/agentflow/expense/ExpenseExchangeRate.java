package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import org.apache.commons.lang3.StringUtils;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * 提交时取得并冻结的汇率事实，不在读取或复核时重新取实时汇率。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseExchangeRate(String fromCurrency, String toCurrency, BigDecimal rate, String source, LocalDate rateDate) {
    private static final BigDecimal MAX_RATE = new BigDecimal("1000000");
    private static final int MAX_RATE_SCALE = 12;
    /** 同币种必须是恒等汇率，来源和日期仍须明确记录。 */
    public ExpenseExchangeRate {
        Money.zero(fromCurrency); Money.zero(toCurrency);
        if (rate == null || rate.signum() <= 0 || rate.compareTo(MAX_RATE) > 0
                || rate.stripTrailingZeros().scale() > MAX_RATE_SCALE || StringUtils.isBlank(source) || source.length() > 128 || rateDate == null
                || fromCurrency.equals(toCurrency) && rate.compareTo(BigDecimal.ONE) != 0) {
            throw new DomainException("INVALID_EXCHANGE_RATE", "An explicit positive exchange rate with source and date is required");
        }
        rate = rate.stripTrailingZeros();
    }

    /** 按行折算并四舍五入到本币的分，汇总时不再舍入。 */
    public Money convert(Money amount) {
        if (!fromCurrency.equals(amount.currency())) throw new DomainException("CURRENCY_MISMATCH", "Exchange rate does not match the source currency");
        return new Money(amount.value().multiply(rate).setScale(Money.SCALE, RoundingMode.HALF_UP), toCurrency);
    }
}
