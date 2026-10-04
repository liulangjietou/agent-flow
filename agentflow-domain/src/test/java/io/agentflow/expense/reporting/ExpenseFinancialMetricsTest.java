package io.agentflow.expense.reporting;

import io.agentflow.approval.model.SubmissionRound.Status;
import io.agentflow.finance.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 原轮次指标只计算实际样本，空分母、未完成和异常时间不能被零值掩盖。
 * @author owlzhangfq@gmail.com
 */
class ExpenseFinancialMetricsTest {
    private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant NOW = START.plusSeconds(1000);

    @Test void emptyCohortHasNoInventedRatesOrQuantiles() {
        var result = summarize(List.of());
        assertThat(result.submitted()).isZero();
        assertThat(result.overLimitRate()).isNull();
        assertThat(result.reductionRate()).isNull();
        assertThat(result.returnRate()).isNull();
        assertThat(result.approval().p50Seconds()).isNull();
        assertThat(result.approval().p90Seconds()).isNull();
        assertThat(result.amounts()).isEmpty();
    }

    @Test void nearestRankUsesObservedValuesRatherThanInterpolation() {
        var rounds = IntStream.rangeClosed(1, 10).mapToObj(seconds -> approved(seconds, 100 + seconds)).toList();
        var result = summarize(rounds);
        assertThat(result.approval().samples()).isEqualTo(10);
        assertThat(result.approval().p50Seconds()).isEqualTo(5L);
        assertThat(result.approval().p90Seconds()).isEqualTo(9L);
        assertThat(result.payment().p50Seconds()).isEqualTo(100L);
        assertThat(result.endToEnd().p90Seconds()).isEqualTo(109L);
        assertThat(summarize(List.of(approved(1, 2), approved(100, 200))).approval().p50Seconds()).isEqualTo(1L);
    }

    @Test void zeroElapsedTimeIsARealSample() {
        var result = summarize(List.of(approved(0, 0)));
        assertThat(result.approval().samples()).isOne();
        assertThat(result.approval().p50Seconds()).isZero();
        assertThat(result.payment().samples()).isOne();
    }

    @Test void missingNegativeAndFutureTimesRemainUnknown() {
        var result = summarize(List.of(round(Status.APPROVED, null, ExpenseFinancialMetrics.Payment.CONFIRMED, START.plusSeconds(5)),
                round(Status.APPROVED, START.minusSeconds(1), ExpenseFinancialMetrics.Payment.CONFIRMED, START.minusSeconds(2)),
                round(Status.APPROVED, NOW.plusSeconds(1), ExpenseFinancialMetrics.Payment.CONFIRMED, NOW.plusSeconds(2))));
        assertThat(result.approval().samples()).isZero();
        assertThat(result.approval().unknown()).isEqualTo(3);
        assertThat(result.payment().unknown()).isEqualTo(3);
        assertThat(result.endToEnd().samples()).isOne();
        assertThat(result.endToEnd().unknown()).isEqualTo(2);
    }

    @Test void pendingAndNonApplicableRoundsAreDifferentFromUnknown() {
        var result = summarize(List.of(round(Status.IN_APPROVAL, null, ExpenseFinancialMetrics.Payment.AWAITING, null),
                round(Status.RETURNED, START.plusSeconds(10), ExpenseFinancialMetrics.Payment.AWAITING, null),
                round(Status.WITHDRAWN, START.plusSeconds(10), ExpenseFinancialMetrics.Payment.AWAITING, null),
                round(Status.CANCELLED, START.plusSeconds(10), ExpenseFinancialMetrics.Payment.AWAITING, null)));
        assertThat(result.approval().pending()).isOne();
        assertThat(result.approval().notApplicable()).isEqualTo(3);
        assertThat(result.approval().unknown()).isZero();
        assertThat(result.payment().pending()).isOne();
        assertThat(result.payment().notApplicable()).isEqualTo(3);
    }

    @Test void zeroPayableAndUncertainPaymentsDoNotInventArrivalSamples() {
        var result = summarize(List.of(round(Status.APPROVED, START.plusSeconds(2), ExpenseFinancialMetrics.Payment.NOT_REQUIRED, null),
                round(Status.APPROVED, START.plusSeconds(2), ExpenseFinancialMetrics.Payment.AWAITING, null),
                round(Status.APPROVED, START.plusSeconds(2), ExpenseFinancialMetrics.Payment.UNKNOWN, null)));
        assertThat(result.approval().samples()).isEqualTo(3);
        assertThat(result.payment().samples()).isZero();
        assertThat(result.payment().notApplicable()).isOne();
        assertThat(result.payment().pending()).isOne();
        assertThat(result.payment().unknown()).isOne();
        assertThat(result.endToEnd()).isEqualTo(result.payment());
    }

    @Test void withdrawnAndPendingRoundsDoNotDiluteReturnRate() {
        var result = summarize(List.of(round(Status.APPROVED, START.plusSeconds(1), ExpenseFinancialMetrics.Payment.AWAITING, null),
                round(Status.RETURNED, START.plusSeconds(2), ExpenseFinancialMetrics.Payment.AWAITING, null),
                round(Status.REJECTED, START.plusSeconds(3), ExpenseFinancialMetrics.Payment.AWAITING, null),
                round(Status.WITHDRAWN, START.plusSeconds(4), ExpenseFinancialMetrics.Payment.AWAITING, null),
                round(Status.IN_APPROVAL, null, ExpenseFinancialMetrics.Payment.AWAITING, null)));
        assertThat(result.submitted()).isEqualTo(5);
        assertThat(result.returnRate()).isEqualByComparingTo("0.333333");
        assertThat(result.withdrawn()).isOne();
        assertThat(result.inApproval()).isOne();
    }

    @Test void categoryTotalsUseOnlyTheirLinesAndCountTheRoundOnce() {
        var fact = new ExpenseFinancialMetrics.Round(Status.APPROVED, START, START.plusSeconds(5), ExpenseFinancialMetrics.Payment.AWAITING, null,
                List.of(line("A", "CNY", "10", "8", true), line("A", "CNY", "20", "20", false),
                        line("B", "USD", "7", "7", false)), null);
        var total = summarize(List.of(fact));
        assertThat(total.submitted()).isOne();
        assertThat(total.overLimit()).isOne();
        assertThat(total.reduced()).isOne();
        assertThat(total.amounts()).containsExactly(new ExpenseFinancialMetrics.Amounts("CNY", "30.00", "28.00", "2.00"),
                new ExpenseFinancialMetrics.Amounts("USD", "7.00", "7.00", "0.00"));
        var category = ExpenseFinancialMetrics.summarize(List.of(fact), NOW, "B");
        assertThat(category.submitted()).isOne();
        assertThat(category.overLimit()).isZero();
        assertThat(category.reduced()).isZero();
        assertThat(category.overLimitRate()).isEqualByComparingTo("0");
        assertThat(category.amounts()).containsExactly(new ExpenseFinancialMetrics.Amounts("USD", "7.00", "7.00", "0.00"));
        assertThat(ExpenseFinancialMetrics.summarize(List.of(fact), NOW, "C").submitted()).isZero();
    }

    @Test void reductionsAndExceptionsUseAllSelectedSubmittedRoundsAsDenominator() {
        var changed = new ExpenseFinancialMetrics.Round(Status.RETURNED, START, START.plusSeconds(3), ExpenseFinancialMetrics.Payment.AWAITING, null,
                List.of(line("A", "CNY", "100", "0", true)), "缺少依据");
        var result = summarize(List.of(changed, approved(2, 10)));
        assertThat(result.overLimitRate()).isEqualByComparingTo("0.5");
        assertThat(result.reductionRate()).isEqualByComparingTo("0.5");
        assertThat(result.amounts().get(0).reduced()).isEqualTo("100.00");
    }

    @Test void exactAggregateAmountsCanExceedOneTransactionLimit() {
        var value = Money.MAX_VALUE.toPlainString();
        var large = new ExpenseFinancialMetrics.Round(Status.IN_APPROVAL, START, null, ExpenseFinancialMetrics.Payment.AWAITING, null,
                List.of(line("A", "CNY", value, value, false), line("B", "CNY", value, value, false)), null);
        assertThat(summarize(List.of(large)).amounts().get(0).claimed()).isEqualTo("1999999999999999.98");
    }

    @Test void missingReturnReasonsRemainSeparateAndRepeatedReasonsAreCounted() {
        var base = round(Status.RETURNED, START.plusSeconds(1), ExpenseFinancialMetrics.Payment.AWAITING, null);
        var reason = new ExpenseFinancialMetrics.Round(base.status(), base.submittedAt(), base.completedAt(), base.payment(), base.paidAt(), base.lines(), "  缺少依据  ");
        var result = summarize(List.of(base, reason, reason));
        assertThat(result.returnReasons()).containsExactly(new ExpenseFinancialMetrics.Reason("缺少依据", 2), new ExpenseFinancialMetrics.Reason(null, 1));
    }

    private static ExpenseFinancialMetrics.Metrics summarize(List<ExpenseFinancialMetrics.Round> rounds) {
        return ExpenseFinancialMetrics.summarize(rounds, NOW, null);
    }
    private static ExpenseFinancialMetrics.Round approved(long approved, long paid) {
        return round(Status.APPROVED, START.plusSeconds(approved), ExpenseFinancialMetrics.Payment.CONFIRMED, START.plusSeconds(paid));
    }
    private static ExpenseFinancialMetrics.Round round(Status status, Instant completed, ExpenseFinancialMetrics.Payment payment, Instant paid) {
        return new ExpenseFinancialMetrics.Round(status, START, completed, payment, paid, List.of(line("A", "CNY", "100", "100", false)), null);
    }
    private static ExpenseFinancialMetrics.Line line(String category, String currency, String claimed, String approved, boolean exception) {
        return new ExpenseFinancialMetrics.Line(category, new Money(new BigDecimal(claimed), currency), new Money(new BigDecimal(approved), currency), exception);
    }
}
