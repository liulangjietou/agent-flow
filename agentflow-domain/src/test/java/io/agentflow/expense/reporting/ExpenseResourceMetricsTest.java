package io.agentflow.expense.reporting;

import io.agentflow.finance.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 当前余额与已消费额度分别计量；预留、争议和缺失日期不能改写实际执行率。
 * @author owlzhangfq@gmail.com
 */
class ExpenseResourceMetricsTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 4);

    @Test void dueDateItselfIsNotOverdueAndEveryBoundaryHasOneBand() {
        var days = List.of(0, 1, 30, 31, 60, 61, 90, 91);
        var values = days.stream().map(day -> new ExpenseResourceMetrics.Advance(money("10", "CNY"), TODAY.minusDays(day), false)).toList();
        var result = ExpenseResourceMetrics.advances(values, TODAY).get(0);
        assertThat(result.accounts()).isEqualTo(8);
        assertThat(result.outstanding()).isEqualTo("80.00");
        assertThat(result.ages()).extracting(ExpenseResourceMetrics.Age::accounts).containsExactly(1L, 2L, 2L, 2L, 1L, 0L);
        assertThat(result.ages()).extracting(ExpenseResourceMetrics.Age::outstanding).containsExactly("10.00", "20.00", "20.00", "20.00", "10.00", "0.00");
    }

    @Test void futureAndUnknownDatesAreNotInventedOverdueBalances() {
        var result = ExpenseResourceMetrics.advances(List.of(new ExpenseResourceMetrics.Advance(money("10", "CNY"), TODAY.plusDays(1), false),
                new ExpenseResourceMetrics.Advance(money("20", "CNY"), null, true)), TODAY).get(0);
        assertThat(result.underReview()).isEqualTo("20.00");
        assertThat(result.ages().get(0).outstanding()).isEqualTo("10.00");
        assertThat(result.ages().get(5).band()).isEqualTo(ExpenseResourceMetrics.AgeBand.UNKNOWN);
        assertThat(result.ages().get(5).outstanding()).isEqualTo("20.00");
    }

    @Test void settledAccountsDoNotIncreaseOutstandingAgeCounts() {
        var result = ExpenseResourceMetrics.advances(List.of(new ExpenseResourceMetrics.Advance(money("0", "CNY"), TODAY.minusDays(100), false)), TODAY).get(0);
        assertThat(result.accounts()).isOne();
        assertThat(result.ages()).allSatisfy(age -> assertThat(age.accounts()).isZero());
    }

    @Test void currenciesStaySeparateAndLargeTotalsStayExact() {
        var max = new Money(Money.MAX_VALUE, "CNY");
        var result = ExpenseResourceMetrics.advances(List.of(new ExpenseResourceMetrics.Advance(max, TODAY, false),
                new ExpenseResourceMetrics.Advance(max, TODAY, true), new ExpenseResourceMetrics.Advance(money("5", "USD"), TODAY, false)), TODAY);
        assertThat(result).extracting(ExpenseResourceMetrics.AdvanceTotals::currency).containsExactly("CNY", "USD");
        assertThat(result.get(0).outstanding()).isEqualTo("1999999999999999.98");
        assertThat(result.get(1).outstanding()).isEqualTo("5.00");
    }

    @Test void consumedRatherThanReservedAmountsDetermineExecutionRate() {
        var result = ExpenseResourceMetrics.priorRequests(List.of(prior("100", "25", "70", "CNY"), prior("100", "75", "0", "CNY"))).get(0);
        assertThat(result.lines()).isEqualTo(2);
        assertThat(result.approved()).isEqualTo("200.00");
        assertThat(result.consumed()).isEqualTo("100.00");
        assertThat(result.reserved()).isEqualTo("70.00");
        assertThat(result.executionRate()).isEqualByComparingTo("0.5");
    }

    @Test void zeroApprovedAmountHasNoRateAndRealOverageMayExceedOneHundredPercent() {
        var result = ExpenseResourceMetrics.priorRequests(List.of(prior("0", "0", "0", "CNY"), prior("100", "120", "0", "USD")));
        assertThat(result.get(0).executionRate()).isNull();
        assertThat(result.get(1).executionRate()).isEqualByComparingTo("1.2");
    }

    @Test void emptyResourcesDoNotInventCurrencyRows() {
        assertThat(ExpenseResourceMetrics.advances(List.of(), TODAY)).isEmpty();
        assertThat(ExpenseResourceMetrics.priorRequests(List.of())).isEmpty();
    }

    private static ExpenseResourceMetrics.Prior prior(String approved, String consumed, String reserved, String currency) {
        return new ExpenseResourceMetrics.Prior(money(approved, currency), money(consumed, currency), money(reserved, currency));
    }
    private static Money money(String amount, String currency) { return new Money(new BigDecimal(amount), currency); }
}
