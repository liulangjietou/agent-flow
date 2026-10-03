package io.agentflow.expense;

import io.agentflow.common.DomainException;
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
 * 还款与原报销预留互斥，真实冲销及还款共同结清而不改写原借出金额。
 * @author owlzhangfq@gmail.com
 */
class AdvanceRepaymentTest {
    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    @Test
    void repaymentAndExpenseOffsetsCloseExactlyWithoutRewritingOriginalLedger() {
        var advance = advance(); var use = new ExpenseUse(UUID.randomUUID(), 1, 0);
        advance.reserve(1, use, money("40")); var original = advance.balance();
        advance.repay(2, repayment(advance, "receipt-a", "60"));
        assertThat(advance.balance()).isEqualTo(original); assertThat(advance.repaid()).isEqualTo(money("60"));
        assertThat(advance.outstanding()).isEqualTo(money("40")); assertThat(advance.available()).isEqualTo(money("0"));
        assertThat(advance.offsetCapacity(null)).isEqualTo(money("0")); assertThat(advance.offsetCapacity(use)).isEqualTo(money("40"));
        assertThat(advance.status()).isEqualTo(EmployeeAdvance.Status.PARTIALLY_SETTLED); assertThat(advance.overdue(LocalDate.parse("2026-10-02"))).isTrue();
        advance.settle(3, use);
        assertThat(advance.status()).isEqualTo(EmployeeAdvance.Status.SETTLED); assertThat(advance.outstanding()).isEqualTo(money("0"));
        assertThat(advance.balance().limit()).isEqualTo(money("100")); assertThat(advance.balance().consumed()).isEqualTo(money("40"));
        assertThat(advance.overdue(LocalDate.parse("2026-10-02"))).isFalse(); assertThat(EmployeeAdvance.restore(advance.state()).state()).isEqualTo(advance.state());
    }
    @Test
    void repaymentCannotConsumeReservedFundsAndLaterReservationCannotReuseRepaidMoney() {
        var advance = advance(); var use = new ExpenseUse(UUID.randomUUID(), 1, 0); advance.reserve(1, use, money("40"));
        var before = advance.state(); fails("INSUFFICIENT_FINANCIAL_BALANCE", () -> advance.repay(2, repayment(advance, "too-large", "60.01")));
        assertThat(advance.state()).isEqualTo(before); advance.repay(2, repayment(advance, "exact", "60")); before = advance.state();
        fails("INSUFFICIENT_FINANCIAL_BALANCE", () -> advance.reserve(3, use, money("40.01")));
        fails("INSUFFICIENT_FINANCIAL_BALANCE", () -> advance.move(3, use, new ExpenseUse(use.reportId(), 2, 0), money("41")));
        assertThat(advance.state()).isEqualTo(before); advance.reserve(3, use, money("30"));
        assertThat(advance.available()).isEqualTo(money("10")); advance.move(4, use, new ExpenseUse(use.reportId(), 2, 0), money("40"));
        assertThat(advance.available()).isEqualTo(money("0"));
    }
    @Test
    void partialAndFullCashRepaymentsAreDistinctFromOffsetsAndRejectReplay() {
        var advance = advance(); var first = repayment(advance, "first", "25"); advance.repay(1, first);
        fails("CONCURRENCY_CONFLICT", () -> advance.repay(1, repayment(advance, "second", "1")));
        fails("ADVANCE_REPAYMENT_ALREADY_RECORDED", () -> advance.repay(2, first));
        fails("ADVANCE_REPAYMENT_ALREADY_RECORDED", () -> advance.repay(2, repayment(advance, "first", "25")));
        advance.repay(2, repayment(advance, "second", "75"));
        assertThat(advance.status()).isEqualTo(EmployeeAdvance.Status.SETTLED); assertThat(advance.repaid()).isEqualTo(money("100"));
        assertThat(advance.balance().consumed()).isEqualTo(money("0")); assertThat(advance.available()).isEqualTo(money("0"));
    }
    @Test
    void wrongLoanCurrencyAndIncompleteOrExpiredExternalEvidenceCannotCreateRepayment() {
        var advance = advance(); fails("ADVANCE_REPAYMENT_SOURCE_CHANGED", () -> advance.repay(1, repayment(advance(), "other", "1")));
        var original = repayment(advance, "valid", "1"); var receipt = original.receipt();
        fails("INVALID_ADVANCE_REPAYMENT", () -> new AdvanceRepayment(UUID.randomUUID(), "demo", UUID.randomUUID(), receipt, "alice", NOW, "本人不能确认"));
        fails("INVALID_ADVANCE_REPAYMENT", () -> new AdvanceRepayment(UUID.randomUUID(), "demo", UUID.randomUUID(), receipt, "finance", receipt.validUntil(), "过期"));
        fails("INVALID_ADVANCE_REPAYMENT_RECEIPT", () -> new AdvanceRepaymentPort.Receipt(receipt.request(), AdvanceRepaymentPort.Status.CONFIRMED, 1, NOW, NOW.plusSeconds(60), receipt.funding(), null));
        fails("INVALID_ADVANCE_REPAYMENT_RECEIPT", () -> new AdvanceRepaymentPort.Receipt(receipt.request(), AdvanceRepaymentPort.Status.CONFIRMED, 1, NOW, NOW.plusSeconds(60), receipt.funding(),
                new AdvanceRepaymentPort.Posting("v", "entry", money("2"), LocalDate.parse("2026-09-29"), NOW.minusSeconds(1))));
        assertThat(advance.version()).isEqualTo(1);
    }
    @Test
    void independentRepaymentDisputeSurvivesOriginalPaymentResolutionAndAllowsOnlyRelease() {
        var advance = advance(); var use = new ExpenseUse(UUID.randomUUID(), 1, 0); advance.reserve(1, use, money("40"));
        var repayment = repayment(advance, "r", "20"); advance.repay(2, repayment); advance.requireRepaymentReview(3, repayment.id()); var held = advance.state();
        assertThat(advance.offsetCapacity(use)).isEqualTo(money("0"));
        advance.requireRepaymentReview(4, repayment.id()); assertThat(advance.state()).isEqualTo(held);
        fails("ADVANCE_REPAYMENT_REVIEW_REQUIRED", () -> advance.settle(4, use));
        fails("ADVANCE_REPAYMENT_REVIEW_REQUIRED", () -> advance.repay(4, repayment(advance, "next", "1")));
        advance.requirePaymentReview(4);
        var paid = new EmployeeAdvance(advance.id(), advance.tenantId(), advance.legalEntityId(), advance.employeeId(), money("100"), advance.paymentReference(), advance.paidOn(), advance.dueOn());
        advance.resolvePaymentReview(5, paid); assertThat(advance.status()).isEqualTo(EmployeeAdvance.Status.REPAYMENT_REVIEW);
        assertThat(advance.available()).isEqualTo(money("0")); advance.reserve(6, use, money("0"));
        assertThat(advance.repaid()).isEqualTo(money("20")); assertThat(advance.outstanding()).isEqualTo(money("80"));
    }
    @Test
    void voucherHoldsPreserveLedgerAllowReleaseAndRequireEachOriginalDecision() {
        var advance = advance(); var use = new ExpenseUse(UUID.randomUUID(), 1, 0); advance.reserve(1, use, money("40"));
        advance.repay(2, repayment(advance, "kept", "20")); var ledger = advance.balance(); var repayments = advance.repayments();
        var accrual = UUID.randomUUID(); var payment = UUID.randomUUID();
        advance.requireVoucherReview(3, accrual); advance.requireVoucherReview(4, payment); var held = advance.state();
        advance.requireVoucherReview(5, accrual); assertThat(advance.state()).isEqualTo(held);
        assertThat(EmployeeAdvance.restore(held).state()).isEqualTo(held); assertThat(advance.available()).isEqualTo(money("0"));
        assertThat(advance.balance()).isEqualTo(ledger); assertThat(advance.outstanding()).isEqualTo(money("80"));
        fails("ADVANCE_VOUCHER_REVIEW_REQUIRED", () -> advance.reserve(5, use, money("41")));
        fails("ADVANCE_VOUCHER_REVIEW_REQUIRED", () -> advance.settle(5, use));
        fails("ADVANCE_VOUCHER_REVIEW_REQUIRED", () -> advance.move(5, use, new ExpenseUse(use.reportId(), 2, 0), money("40")));
        fails("ADVANCE_VOUCHER_REVIEW_REQUIRED", () -> advance.repay(5, repayment(advance, "next", "1")));
        fails("ADVANCE_VOUCHER_REVIEW_REQUIRED", () -> advance.resolveVoucherReview(5, UUID.randomUUID()));
        advance.reserve(5, use, money("30")); advance.resolveVoucherReview(6, accrual);
        assertThat(advance.status()).isEqualTo(EmployeeAdvance.Status.VOUCHER_REVIEW); assertThat(advance.voucherReviews()).containsExactly(payment);
        advance.resolveVoucherReview(7, payment); assertThat(advance.available()).isEqualTo(money("50")); assertThat(advance.repayments()).isEqualTo(repayments);
    }

    @Test
    void voucherResolutionNeverClearsIndependentPaymentOrRepaymentHolds() {
        var advance = advance(); var original = EmployeeAdvance.restore(advance.state()); var repayment = repayment(advance, "r", "20");
        advance.repay(1, repayment); advance.requireRepaymentReview(2, repayment.id()); advance.requirePaymentReview(3);
        var voucher = UUID.randomUUID(); advance.requireVoucherReview(4, voucher); advance.resolveVoucherReview(5, voucher);
        assertThat(advance.status()).isEqualTo(EmployeeAdvance.Status.PAYMENT_REVIEW);
        advance.requireVoucherReview(6, voucher); advance.resolvePaymentReview(7, original);
        assertThat(advance.status()).isEqualTo(EmployeeAdvance.Status.VOUCHER_REVIEW);
        advance.resolveVoucherReview(8, voucher); assertThat(advance.status()).isEqualTo(EmployeeAdvance.Status.REPAYMENT_REVIEW);
        assertThat(advance.available()).isEqualTo(money("0")); assertThat(advance.repaymentReviews()).containsExactly(repayment.id());
    }

    @Test
    void legacySnapshotsDefaultToNoRepaymentButDuplicateAndOverdrawnSnapshotsFail() {
        var advance = advance(); var s = advance.state(); var entry = repayment(advance, "r", "60").entry();
        assertThat(EmployeeAdvance.restore(new EmployeeAdvance.State(s.id(), s.tenantId(), s.legalEntityId(), s.employeeId(), s.paymentReference(), s.paidOn(), s.dueOn(), s.balance(), 1, false, null, false, null, null)).state()).isEqualTo(s);
        fails("INVALID_ADVANCE", () -> EmployeeAdvance.restore(new EmployeeAdvance.State(s.id(), s.tenantId(), s.legalEntityId(), s.employeeId(), s.paymentReference(), s.paidOn(), s.dueOn(), s.balance(), 2, false, List.of(entry, entry), false, null, null)));
        fails("INSUFFICIENT_FINANCIAL_BALANCE", () -> EmployeeAdvance.restore(new EmployeeAdvance.State(s.id(), s.tenantId(), s.legalEntityId(), s.employeeId(), s.paymentReference(), s.paidOn(), s.dueOn(), s.balance(), 2, false, List.of(entry, repayment(advance, "r2", "41").entry()), false, null, null)));
    }
    private static EmployeeAdvance advance() { return new EmployeeAdvance(UUID.randomUUID(), "demo", UUID.randomUUID(), "alice", money("100"), "payment-original", LocalDate.parse("2026-09-01"), LocalDate.parse("2026-10-01")); }
    private static AdvanceRepayment repayment(EmployeeAdvance advance, String reference, String amount) {
        var request = new AdvanceRepaymentPort.Request(advance.id(), advance.legalEntityId(), advance.employeeId(), advance.paymentReference(), "CNY", reference);
        var receipt = new AdvanceRepaymentPort.Receipt(request, AdvanceRepaymentPort.Status.CONFIRMED, 1, NOW.minusSeconds(1), NOW.plusSeconds(60),
                new AdvanceRepaymentPort.Funding(AdvanceRepaymentPort.Channel.CASH, "funding-" + reference, money(amount), NOW.minusSeconds(10)),
                new AdvanceRepaymentPort.Posting("voucher-" + reference, "entry-" + reference, money(amount), LocalDate.parse("2026-09-29"), NOW.minusSeconds(2)));
        return new AdvanceRepayment(UUID.randomUUID(), "demo", UUID.randomUUID(), receipt, "finance", NOW, "核对外部实际收款和借款冲减分录");
    }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    private static void fails(String code, Runnable action) { assertThatThrownBy(action::run).isInstanceOf(DomainException.class).extracting(value -> ((DomainException) value).code()).isEqualTo(code); }
}
