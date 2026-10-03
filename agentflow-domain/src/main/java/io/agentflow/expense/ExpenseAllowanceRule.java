package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 已发布制度中的定额补贴规则；日额来自配置，天数按明确的自然日口径计算。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseAllowanceRule(Money dailyRate, DayCountBasis dayCountBasis) {
    private static final long MAX_DAYS = 1_000_000L;

    /** 零金额、未说明天数口径及空日额都不能形成可发布补贴。 */
    public ExpenseAllowanceRule {
        if (dailyRate == null || dailyRate.value().signum() <= 0 || dayCountBasis == null) {
            throw new DomainException("INVALID_ALLOWANCE_RULE", "Allowance rate and explicit day-count basis are required");
        }
    }

    /** 含首尾日计算；同日出行计一天，日期差不会受到时区或夏令时影响。 */
    public Calculation calculate(LocalDate startsOn, LocalDate endsOn) {
        long days = days(startsOn, endsOn);
        return new Calculation(startsOn, endsOn, days, this, total(days));
    }

    private Money total(long days) {
        return new Money(dailyRate.value().multiply(BigDecimal.valueOf(days)), dailyRate.currency());
    }

    private static long days(LocalDate startsOn, LocalDate endsOn) {
        if (startsOn == null || endsOn == null || endsOn.isBefore(startsOn)) {
            throw new DomainException("ALLOWANCE_ITINERARY_REQUIRED", "Allowance requires an ordered itinerary date range");
        }
        long value = ChronoUnit.DAYS.between(startsOn, endsOn) + 1;
        if (value > MAX_DAYS) throw new DomainException("ALLOWANCE_ITINERARY_TOO_LONG", "Allowance itinerary exceeds the expense quantity limit");
        return value;
    }

    /**
     * 天数口径随制度版本保存，历史计算不依赖后续新增口径。
     * @author owlzhangfq@gmail.com
     */
    public enum DayCountBasis { CALENDAR_DAYS_INCLUSIVE }

    /**
     * 自洽的计算依据；输入不能另外指定与行程不符的天数或金额。
     * @author owlzhangfq@gmail.com
     */
    public record Calculation(LocalDate startsOn, LocalDate endsOn, long days, ExpenseAllowanceRule rule, Money gross) {
        /** 反序列化同样验证日期、天数和金额，拒绝破坏历史计算等式。 */
        public Calculation {
            if (rule == null || days != ExpenseAllowanceRule.days(startsOn, endsOn) || !rule.total(days).equals(gross)) {
                throw new DomainException("ALLOWANCE_CALCULATION_MISMATCH", "Allowance amount and days must match the recorded rule and itinerary");
            }
        }
    }
}
