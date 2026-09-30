package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import io.agentflow.finance.ReservedAmount;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 部分冲回保留原核销，累计不得超额，关闭状态与各类独立资金冻结不被解除。
 * @author owlzhangfq@gmail.com
 */
class ConsumptionReductionTest {
    private static final Instant NOW = Instant.parse("2026-09-30T18:40:00Z");
    private static final UUID ENTITY = UUID.randomUUID();

    @Test void independentPartialReductionsKeepOriginalConsumptionAndOtherReservations() {
        var use = use(0); var other = use(0); var original = ReservedAmount.available(money("100")).reserve(use, money("70")).consume(use).reserve(other, money("30"));
        var first = original.reduceConsumption(use, money("20"), UUID.randomUUID(), NOW);
        assertThat(first.consumptions()).isEqualTo(original.consumptions()); assertThat(first.reservations()).isEqualTo(original.reservations());
        assertThat(first.grossConsumed()).isEqualTo(money("70")); assertThat(first.consumed()).isEqualTo(money("50")); assertThat(first.available()).isEqualTo(money("20"));
        var next = first.reduceConsumption(use, money("50"), UUID.randomUUID(), NOW.plusSeconds(1));
        assertThat(next.consumedAmountFor(use)).isEqualTo(money("70")); assertThat(next.netConsumedAmountFor(use)).isEqualTo(money("0"));
        assertThat(next.reduced()).isEqualTo(money("70")); assertThat(next.reversals()).isEmpty(); assertThat(next.reductions()).hasSize(2);
        assertThat(original.reductions()).isEmpty();
        invalid(() -> next.reserve(use, money("1"))); invalid(() -> next.reduceConsumption(use, money("0.01"), UUID.randomUUID(), NOW.plusSeconds(2)));
        invalid(() -> next.reverseConsumption(use, UUID.randomUUID(), NOW.plusSeconds(2)));
    }

    @Test void retriesTimeRegressionAndOverReductionDoNotCreateExtraBalance() {
        var use = use(1); var id = UUID.randomUUID(); var before = ReservedAmount.available(money("100")).reserve(use, money("70")).consume(use);
        var reduced = before.reduceConsumption(use, money("20"), id, NOW);
        invalid(() -> reduced.reduceConsumption(use, money("20"), id, NOW.plusSeconds(1)));
        invalid(() -> reduced.reduceConsumption(use, money("1"), UUID.randomUUID(), NOW.minusSeconds(1)));
        invalid(() -> reduced.reduceConsumption(use, money("50.01"), UUID.randomUUID(), NOW.plusSeconds(1)));
        invalid(() -> reduced.reduceConsumption(use(1), money("1"), UUID.randomUUID(), NOW));
        invalid(() -> before.reduceConsumption(use, money("0"), id, NOW));
        invalid(() -> before.reduceConsumption(use, new Money(BigDecimal.ONE, "USD"), id, NOW));
        assertThat(reduced.reductions()).hasSize(1); assertThat(reduced.available()).isEqualTo(money("50"));
    }

    @Test void restoredReductionsRequireOriginalAmountUniqueDecisionAndChronologicalEvidence() {
        var use = use(1); var item = new ReservedAmount.Reservation(use, money("50"));
        var first = new ReservedAmount.ConsumptionReduction(UUID.randomUUID(), use, money("20"), NOW);
        var next = new ReservedAmount.ConsumptionReduction(UUID.randomUUID(), use, money("31"), NOW.plusSeconds(1));
        invalid(() -> new ReservedAmount(money("100"), List.of(), List.of(), List.of(), List.of(first)));
        invalid(() -> new ReservedAmount(money("100"), List.of(item), List.of(), List.of(), List.of(first, first)));
        invalid(() -> new ReservedAmount(money("100"), List.of(item), List.of(), List.of(), List.of(first, next)));
        invalid(() -> new ReservedAmount(money("100"), List.of(item), List.of(), List.of(), List.of(next, first)));
        var reversed = new ReservedAmount.ConsumptionReversal(UUID.randomUUID(), use, money("50"), NOW);
        invalid(() -> new ReservedAmount(money("100"), List.of(item), List.of(), List.of(reversed), List.of(first)));
        var old = new ReservedAmount(money("100"), List.of(item), List.of(), List.of()); assertThat(old.reductions()).isEmpty();
        var full = old.reverseConsumption(use, UUID.randomUUID(), NOW);
        invalid(() -> full.reduceConsumption(use, money("1"), UUID.randomUUID(), NOW)); assertThat(full.netConsumedAmountFor(use)).isEqualTo(money("0"));
    }

    @Test void oneAdjustmentCanReduceDistinctOriginalUsesWithoutMergingTheirBounds() {
        var first = use(1); var second = new ExpenseUse(first.reportId(), 1, 2); var id = UUID.randomUUID();
        var before = ReservedAmount.available(money("100")).reserve(first, money("40")).consume(first).reserve(second, money("30")).consume(second);
        var changed = before.reduceConsumption(first, money("10"), id, NOW).reduceConsumption(second, money("20"), id, NOW);
        assertThat(changed.netConsumedAmountFor(first)).isEqualTo(money("30")); assertThat(changed.netConsumedAmountFor(second)).isEqualTo(money("10"));
        assertThat(changed.available()).isEqualTo(money("60"));
        assertThatThrownBy(() -> changed.reductions().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void closedPriorRequestAndFrozenAdvanceKeepTheirOriginalRestrictions() {
        var request = new ExpenseRequest(UUID.randomUUID(), "demo", UUID.randomUUID(), ENTITY, "alice", List.of(new ExpenseRequest.ApprovedLine(1, money("100"), BigDecimal.ZERO, "original")));
        var use = use(1); request.reserve(1, 1, use, money("70")); request.consume(2, 1, use); request.close(3);
        request.reduceConsumption(4, 1, use, money("20"), UUID.randomUUID(), NOW);
        assertThat(request.closed()).isTrue(); assertThat(request.balance(1).consumed()).isEqualTo(money("50"));
        invalid(() -> request.reserve(5, 1, use(1), money("1")));
        var date = LocalDate.of(2026, 9, 30); var advance = new EmployeeAdvance(UUID.randomUUID(), "demo", ENTITY, "alice", money("100"), "original-bank", date, date.plusDays(30));
        var offset = use(0); var other = use(0); var voucher = UUID.randomUUID();
        advance.reserve(1, offset, money("70")); advance.settle(2, offset); advance.reserve(3, other, money("30"));
        advance.requirePaymentReview(4); advance.requireVoucherReview(5, voucher); var before = advance.state();
        advance.reduceOffset(6, offset, money("20"), UUID.randomUUID(), NOW);
        assertThat(advance.outstanding()).isEqualTo(money("50")); assertThat(advance.available()).isEqualTo(money("0"));
        assertThat(advance.balance().reservations()).isEqualTo(before.balance().reservations()); assertThat(advance.balance().consumptions()).isEqualTo(before.balance().consumptions());
        assertThat(advance.paymentReviewRequired()).isTrue(); assertThat(advance.voucherReviews()).containsExactly(voucher);
        assertThat(advance.repayments()).isEqualTo(before.repayments()); assertThat(EmployeeAdvance.restore(advance.state()).state()).isEqualTo(advance.state());
        var current = advance.state(); invalid(() -> advance.reduceOffset(6, offset, money("1"), UUID.randomUUID(), NOW)); assertThat(advance.state()).isEqualTo(current);
    }

    private static ExpenseUse use(int line) { return new ExpenseUse(UUID.randomUUID(), 1, line); }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    private static void invalid(Runnable action) { assertThatThrownBy(action::run).isInstanceOf(DomainException.class); }
}
