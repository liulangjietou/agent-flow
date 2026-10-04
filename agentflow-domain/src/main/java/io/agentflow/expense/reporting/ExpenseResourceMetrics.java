package io.agentflow.expense.reporting;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.finance.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 当前可读资金资源的余额、账龄和执行率，不将预留解释为实际报销。
 * @author owlzhangfq@gmail.com
 */
public final class ExpenseResourceMetrics {
    private static final long FIRST_AGE_BOUNDARY = 30;
    private static final long SECOND_AGE_BOUNDARY = 60;
    private static final long THIRD_AGE_BOUNDARY = 90;
    private ExpenseResourceMetrics() { }

    /** 每笔余额按原法人的本地日期计算账龄；到期日当天不逾期，已结清账户不进入账龄账户数。 */
    public static List<AdvanceTotals> advances(List<Advance> values) {
        Map<String, AdvanceAccumulator> totals = new TreeMap<>();
        for (var value : values) totals.computeIfAbsent(value.outstanding().currency(), ignored -> new AdvanceAccumulator()).add(value);
        return totals.entrySet().stream().map(entry -> entry.getValue().result(entry.getKey())).toList();
    }

    /** 原批准额度与实际已消费额分别相加，预留不进入执行率分子。 */
    public static List<PriorTotals> priorRequests(List<Prior> values) {
        Map<String, PriorAccumulator> totals = new TreeMap<>();
        for (var value : values) totals.computeIfAbsent(value.approved().currency(), ignored -> new PriorAccumulator()).add(value);
        return totals.entrySet().stream().map(entry -> entry.getValue().result(entry.getKey())).toList();
    }

    private static AgeBand age(LocalDate due, LocalDate asOf) {
        if (due == null) return AgeBand.UNKNOWN;
        long days = ChronoUnit.DAYS.between(due, asOf);
        if (days <= 0) return AgeBand.NOT_DUE;
        if (days <= FIRST_AGE_BOUNDARY) return AgeBand.DAYS_1_30;
        if (days <= SECOND_AGE_BOUNDARY) return AgeBand.DAYS_31_60;
        if (days <= THIRD_AGE_BOUNDARY) return AgeBand.DAYS_61_90;
        return AgeBand.OVER_90;
    }
    /**
     * 余额来自原聚合 outstanding，本地日期由原法人时区确定，争议资金仍保留余额。
     * @author owlzhangfq@gmail.com
     */
    public record Advance(Money outstanding, LocalDate dueOn, boolean reviewRequired, LocalDate localDate) { }
    /**
     * 单条原批准额度与当前实际消费、预留；查询层负责原资源权限和去重。
     * @author owlzhangfq@gmail.com
     */
    public record Prior(Money approved, Money consumed, Money reserved) { }
    /**
     * 固定账龄区间的顺序也是页面顺序，未知日期保留独立区间。
     * @author owlzhangfq@gmail.com
     */
    public enum AgeBand { NOT_DUE, DAYS_1_30, DAYS_31_60, DAYS_61_90, OVER_90, UNKNOWN }
    /**
     * 每个区间只计算未结清账户，不包含其他币种。
     * @author owlzhangfq@gmail.com
     */
    public record Age(AgeBand band, long accounts, String outstanding) { }
    /**
     * 同币种当前借款汇总，账户数包含已结清账户，账龄只计有余额的账户。
     * @author owlzhangfq@gmail.com
     */
    public record AdvanceTotals(String currency, long accounts, String outstanding, String underReview, List<Age> ages) { }
    /**
     * 执行率允许超过一；零批准额度没有可定义的执行率。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PriorTotals(String currency, long lines, String approved, String consumed, String reserved, BigDecimal executionRate) { }

    /**
     * 当前币种的账龄累加器，不复用单笔金额的容量上限。
     * @author owlzhangfq@gmail.com
     */
    private static final class AdvanceAccumulator {
        private long accounts;
        private BigDecimal outstanding = BigDecimal.ZERO, underReview = BigDecimal.ZERO;
        private final Map<AgeBand, Long> counts = new EnumMap<>(AgeBand.class);
        private final Map<AgeBand, BigDecimal> amounts = new EnumMap<>(AgeBand.class);
        private void add(Advance value) {
            accounts++; var amount = value.outstanding().value(); outstanding = outstanding.add(amount);
            if (value.reviewRequired()) underReview = underReview.add(amount);
            if (amount.signum() > 0) {
                var band = age(value.dueOn(), value.localDate());
                counts.merge(band, 1L, Long::sum); amounts.merge(band, amount, BigDecimal::add);
            }
        }
        private AdvanceTotals result(String currency) {
            var ages = Arrays.stream(AgeBand.values()).map(band -> new Age(band, counts.getOrDefault(band, 0L),
                    ExpenseFinancialMetrics.amount(amounts.getOrDefault(band, BigDecimal.ZERO)))).toList();
            return new AdvanceTotals(currency, accounts, ExpenseFinancialMetrics.amount(outstanding), ExpenseFinancialMetrics.amount(underReview), ages);
        }
    }
    /**
     * 计划消费与预留独立累计，避免用剩余额度反推实际执行。
     * @author owlzhangfq@gmail.com
     */
    private static final class PriorAccumulator {
        private long lines;
        private BigDecimal approved = BigDecimal.ZERO, consumed = BigDecimal.ZERO, reserved = BigDecimal.ZERO;
        private void add(Prior value) {
            lines++; approved = approved.add(value.approved().value()); consumed = consumed.add(value.consumed().value()); reserved = reserved.add(value.reserved().value());
        }
        private PriorTotals result(String currency) {
            return new PriorTotals(currency, lines, ExpenseFinancialMetrics.amount(approved), ExpenseFinancialMetrics.amount(consumed),
                    ExpenseFinancialMetrics.amount(reserved), ExpenseFinancialMetrics.ratio(consumed, approved));
        }
    }
}
