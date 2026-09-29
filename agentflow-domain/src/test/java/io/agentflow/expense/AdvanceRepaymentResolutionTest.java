package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.AdvanceRepaymentAdjustmentPort;
import io.agentflow.finance.AdvanceRepaymentPort;
import io.agentflow.finance.Money;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 逐笔争议与真实退回调整保留原还款、冲销和预留；不能越权或顺带解除其他冻结。
 * @author owlzhangfq@gmail.com
 */
class AdvanceRepaymentResolutionTest {
    private static final Instant NOW = Instant.parse("2026-09-29T08:00:00Z");
    private static final LocalDate DATE = LocalDate.parse("2026-09-29");

    @Test void partialLoanRepaymentReturnAddsBackDebtWithoutChangingOriginalRepaymentOrOffsets() {
        var advance = advance(); var use = new ExpenseUse(UUID.randomUUID(), 1, 0); advance.reserve(1, use, money("40")); advance.settle(2, use);
        var repayment = repayment(advance, "60"); advance.repay(3, repayment); var original = advance.balance();
        assertThat(advance.status()).isEqualTo(EmployeeAdvance.Status.SETTLED); advance.requireRepaymentReview(4, repayment.id());
        advance.resolveRepaymentReview(5, decision(repayment, true));
        assertThat(advance.balance()).isEqualTo(original); assertThat(advance.repayments()).containsExactly(repayment.entry());
        assertThat(advance.receivedRepayments()).isEqualTo(money("60")); assertThat(advance.returnedRepayments()).isEqualTo(money("60")); assertThat(advance.repaid()).isEqualTo(money("0"));
        assertThat(advance.outstanding()).isEqualTo(money("60")); assertThat(advance.available()).isEqualTo(money("60")); assertThat(advance.status()).isEqualTo(EmployeeAdvance.Status.PARTIALLY_SETTLED);
        assertThat(EmployeeAdvance.restore(advance.state()).state()).isEqualTo(advance.state());
    }

    @Test void resolvingOneReceiptNeverClearsOtherReceiptOrOriginalPaymentReview() {
        var advance = advance(); var first = repayment(advance, "30"); var second = repayment(advance, "20"); advance.repay(1, first); advance.repay(2, second);
        advance.requireRepaymentReview(3, first.id()); advance.requireRepaymentReview(4, second.id()); advance.requirePaymentReview(5);
        advance.resolveRepaymentReview(6, decision(first, true)); assertThat(advance.repaymentReviews()).containsExactly(second.id()); assertThat(advance.paymentReviewRequired()).isTrue();
        assertThat(advance.outstanding()).isEqualTo(money("80")); assertThat(advance.available()).isEqualTo(money("0"));
        advance.resolveRepaymentReview(7, decision(second, false)); assertThat(advance.repaymentReviews()).isEmpty(); assertThat(advance.status()).isEqualTo(EmployeeAdvance.Status.PAYMENT_REVIEW);
        assertThat(advance.repaid()).isEqualTo(money("20")); assertThat(advance.available()).isEqualTo(money("0"));
    }

    @Test void confirmationKeepsOriginalReservationsAndCannotReuseReceiptOrDoubleApplyReturn() {
        var advance = advance(); var repayment = repayment(advance, "60"); var use = new ExpenseUse(UUID.randomUUID(), 1, 0);
        advance.repay(1, repayment); advance.reserve(2, use, money("40")); advance.requireRepaymentReview(3, repayment.id());
        advance.resolveRepaymentReview(4, decision(repayment, false)); assertThat(advance.available()).isEqualTo(money("0")); assertThat(advance.balance().reserved()).isEqualTo(money("40"));
        advance.requireRepaymentReview(5, repayment.id()); var returned = decision(repayment, true); advance.resolveRepaymentReview(6, returned);
        assertThat(advance.available()).isEqualTo(money("60")); var entry = advance.repaymentReturns().get(0);
        advance.requireRepaymentReview(7, repayment.id()); advance.resolveRepaymentReview(8, decision(repayment, true));
        assertThat(advance.repaymentReturns()).containsExactly(entry); assertThat(advance.returnedRepayments()).isEqualTo(money("60"));
        assertThatThrownBy(() -> advance.repay(9, repayment)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> advance.resolveRepaymentReview(9, returned)).isInstanceOf(DomainException.class);
    }

    @Test void acceptedReturnCannotDisappearThroughLaterConfirmedReceiptAndWrongSourceCannotChangeBalance() {
        var advance = advance(); var repayment = repayment(advance, "100"); advance.repay(1, repayment); advance.requireRepaymentReview(2, repayment.id());
        var original = advance.state(); var foreign = decision(repayment(advance(), "100"), true);
        assertThatThrownBy(() -> advance.resolveRepaymentReview(3, foreign)).isInstanceOf(DomainException.class); assertThat(advance.state()).isEqualTo(original);
        advance.resolveRepaymentReview(3, decision(repayment, true)); advance.requireRepaymentReview(4, repayment.id()); var held = advance.state();
        assertThatThrownBy(() -> advance.resolveRepaymentReview(5, decision(repayment, false))).isInstanceOf(DomainException.class); assertThat(advance.state()).isEqualTo(held);
        assertThatThrownBy(() -> advance.resolveRepaymentReview(4, decision(repayment, true))).isInstanceOf(DomainException.class);
    }

    @Test void legacyUnspecifiedRepaymentHoldRequiresEveryOriginalReceiptAndInvalidRestoreFails() {
        var advance = advance(); var first = repayment(advance, "30"); var second = repayment(advance, "20"); advance.repay(1, first); advance.repay(2, second); var state = advance.state();
        var legacy = EmployeeAdvance.restore(new EmployeeAdvance.State(state.id(), state.tenantId(), state.legalEntityId(), state.employeeId(), state.paymentReference(), state.paidOn(), state.dueOn(),
                state.balance(), state.version(), false, state.repayments(), true, null, null));
        assertThat(legacy.repaymentReviews()).containsExactly(first.id(), second.id()); legacy.resolveRepaymentReview(3, decision(first, false)); assertThat(legacy.status()).isEqualTo(EmployeeAdvance.Status.REPAYMENT_REVIEW);
        assertThatThrownBy(() -> EmployeeAdvance.restore(new EmployeeAdvance.State(state.id(), state.tenantId(), state.legalEntityId(), state.employeeId(), state.paymentReference(), state.paidOn(), state.dueOn(),
                state.balance(), state.version(), false, state.repayments(), false, List.of(first.id()), null))).isInstanceOf(DomainException.class);
        var refund = decision(first, true).returnEntry();
        assertThatThrownBy(() -> EmployeeAdvance.restore(new EmployeeAdvance.State(state.id(), state.tenantId(), state.legalEntityId(), state.employeeId(), state.paymentReference(), state.paidOn(), state.dueOn(),
                state.balance(), state.version(), false, state.repayments(), false, List.of(), List.of(refund, refund)))).isInstanceOf(DomainException.class);
    }

    private static AdvanceRepaymentResolution decision(AdvanceRepayment repayment, boolean returned) {
        var original = repayment.receipt(); var request = new AdvanceRepaymentAdjustmentPort.Request(repayment.id(), original);
        var current = new AdvanceRepaymentPort.Receipt(original.request(), returned ? AdvanceRepaymentPort.Status.REVERSED : AdvanceRepaymentPort.Status.CONFIRMED, 2, NOW, NOW.plusSeconds(300), original.funding(), original.posting());
        var receipt = new AdvanceRepaymentAdjustmentPort.Receipt(request, returned ? AdvanceRepaymentAdjustmentPort.Status.RETURNED : AdvanceRepaymentAdjustmentPort.Status.CONFIRMED, 2, NOW, NOW.plusSeconds(300), current,
                returned ? new AdvanceRepaymentAdjustmentPort.FundsReturn(AdvanceRepaymentPort.Channel.CASH, "return-" + repayment.id(), repayment.amount(), NOW.minusSeconds(2)) : null,
                returned ? new AdvanceRepaymentAdjustmentPort.ReturnPosting("return-" + repayment.id(), "debit-1", repayment.amount(), DATE, NOW.minusSeconds(1)) : null);
        return new AdvanceRepaymentResolution(UUID.randomUUID(), "demo", UUID.randomUUID(), receipt, "finance", NOW, "ERP-CHECK", "核对原还款及实际退回依据");
    }
    private static AdvanceRepayment repayment(EmployeeAdvance advance, String amount) {
        String key = UUID.randomUUID().toString(); var request = new AdvanceRepaymentPort.Request(advance.id(), advance.legalEntityId(), advance.employeeId(), advance.paymentReference(), "CNY", key);
        var receipt = new AdvanceRepaymentPort.Receipt(request, AdvanceRepaymentPort.Status.CONFIRMED, 1, NOW.minusSeconds(100), NOW.plusSeconds(200),
                new AdvanceRepaymentPort.Funding(AdvanceRepaymentPort.Channel.CASH, "funds-" + key, money(amount), NOW.minusSeconds(300)),
                new AdvanceRepaymentPort.Posting("voucher-" + key, "credit-1", money(amount), DATE, NOW.minusSeconds(200)));
        return new AdvanceRepayment(UUID.randomUUID(), "demo", UUID.randomUUID(), receipt, "finance", NOW.minusSeconds(90), "核对原收款");
    }
    private static EmployeeAdvance advance() { return new EmployeeAdvance(UUID.randomUUID(), "demo", UUID.randomUUID(), "alice", money("100"), "original-payment", DATE.minusDays(1), DATE.plusDays(30)); }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
}
