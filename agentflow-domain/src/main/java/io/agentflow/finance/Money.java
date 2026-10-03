package io.agentflow.finance;

import io.agentflow.common.DomainException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * 费用与结算共用的非负两位金额；禁止隐式汇兑、精度截断与浮点数计算。
 * @author owlzhangfq@gmail.com
 */
public record Money(BigDecimal value, String currency) implements Comparable<Money> {
    public static final int SCALE = 2;
    public static final BigDecimal MAX_VALUE = new BigDecimal("999999999999999.99");

    /** 只规范化等价尾零；真正多于两位的小数必须由明确换算规则产生。 */
    public Money {
        if (value == null || value.signum() < 0 || value.compareTo(MAX_VALUE) > 0 || currency == null
                || !currency.matches("[A-Z]{3}")) throw invalid();
        try {
            if (Currency.getInstance(currency).getDefaultFractionDigits() != SCALE) throw invalid();
            value = value.setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (IllegalArgumentException | ArithmeticException invalid) { throw invalid(); }
    }

    /** 构造有明确币种的零金额。 */
    public static Money zero(String currency) { return new Money(BigDecimal.ZERO, currency); }

    /** 金额相加必须使用同一币种。 */
    public Money plus(Money other) { sameCurrency(other); return new Money(value.add(other.value), currency); }

    /** 扣减后不能形成负余额。 */
    public Money minus(Money other) { sameCurrency(other); return new Money(value.subtract(other.value), currency); }

    /** 保留同币种较小额，供有界冲销选择使用。 */
    public Money min(Money other) { return compareTo(other) <= 0 ? this : other; }

    /** 只有币种相同的金额可以比较。 */
    @Override public int compareTo(Money other) { sameCurrency(other); return value.compareTo(other.value); }

    /** 判断币种一致，不通过自动换算隐藏输入错误。 */
    public void sameCurrency(Money other) {
        if (other == null || !currency.equals(other.currency)) throw new DomainException("CURRENCY_MISMATCH", "Money currencies must match");
    }

    private static DomainException invalid() { return new DomainException("INVALID_MONEY", "A non-negative two-decimal currency amount within the supported limit is required"); }
}
