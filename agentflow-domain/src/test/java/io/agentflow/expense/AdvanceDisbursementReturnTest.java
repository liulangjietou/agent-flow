package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.AdvanceDisbursementReturnPort;
import io.agentflow.finance.AdvanceRepaymentPort;
import io.agentflow.finance.EmployeeAccountSnapshot;
import io.agentflow.finance.FinanceResult;
import io.agentflow.finance.Money;
import io.agentflow.finance.PaymentCommand;
import io.agentflow.finance.PaymentObservation;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;

/**
 * 放款退回、主动还款和原报销占用分别守恒，外部撤销状态本身不构成资金调整。
 * @author owlzhangfq@gmail.com
 */
class AdvanceDisbursementReturnTest {
    private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");
    private static final LocalDate DATE = LocalDate.parse("2026-09-29");
    private final PaymentCommand command = new PaymentCommand(UUID.randomUUID(), "demo", PaymentCommand.Purpose.EMPLOYEE_ADVANCE,
            new PaymentCommand.Binding(UUID.randomUUID(), UUID.randomUUID(), 1, 3, 2), money("100"), "company-bank",
            new EmployeeAccountSnapshot(UUID.randomUUID(), "alice", "account", "****1234", "a".repeat(64), "v1"), "original-accrual",
            new PaymentCommand.Authorization("finance", "cashier", NOW.minusSeconds(100), NOW.plusSeconds(300)));
    private final PaymentObservation original = observation(PaymentObservation.Status.SUCCEEDED, 1, NOW.minusSeconds(20));
    private final AdvanceDisbursementReturnPort.Request request = new AdvanceDisbursementReturnPort.Request(command, original);

    @Test void cumulativeReturnsPreserveOriginalPrincipalRepaymentsReservationsAndFirstDecision() {
        var advance = advance(); var repayment = repayment(advance, "20"); advance.repay(advance.version(), repayment);
        var use = new ExpenseUse(UUID.randomUUID(), 1, 0); advance.reserve(advance.version(), use, money("20"));
        var first = item("first", "40"); var second = item("second", "20");
        apply(advance, List.of(first)); var originalEntry = advance.disbursementReturns().get(0);
        assertThat(advance.outstanding()).isEqualTo(money("40")); assertThat(advance.available()).isEqualTo(money("20"));
        apply(advance, List.of(first, second)); var entries = advance.disbursementReturns();
        assertThat(advance.disbursementReturns()).hasSize(2).contains(originalEntry);
        apply(advance, List.of(second, first)); assertThat(advance.disbursementReturns()).isEqualTo(entries);
        assertThat(advance.balance().limit()).isEqualTo(money("100")); assertThat(advance.balance().reservedFor(use)).isEqualTo(money("20"));
        assertThat(advance.repaid()).isEqualTo(money("20")); assertThat(advance.returnedDisbursements()).isEqualTo(money("60"));
        assertThat(advance.outstanding()).isEqualTo(money("20")); assertThat(advance.available()).isEqualTo(money("0"));
        assertThat(advance.repayments()).containsExactly(repayment.entry()); assertThat(advance.status()).isEqualTo(EmployeeAdvance.Status.PARTIALLY_SETTLED);
        assertThat(EmployeeAdvance.restore(advance.state()).state()).isEqualTo(advance.state());
        var state = advance.state(); fails("INSUFFICIENT_FINANCIAL_BALANCE", () -> advance.reserve(advance.version(), use, money("20.01")));
        assertThat(advance.state()).isEqualTo(state);
    }

    @Test void fullDisbursementReturnHasItsOwnClosedStateAndCannotBecomeSpendableOrAnEmployeeRepayment() {
        var advance = advance(); apply(advance, List.of(item("full", "100")));
        assertThat(advance.status()).isEqualTo(EmployeeAdvance.Status.RETURNED); assertThat(advance.outstanding()).isEqualTo(money("0"));
        assertThat(advance.available()).isEqualTo(money("0")); assertThat(advance.balance().limit()).isEqualTo(money("100"));
        assertThat(advance.receivedRepayments()).isEqualTo(money("0")); assertThat(advance.returnedRepayments()).isEqualTo(money("0"));
        assertThat(advance.overdue(DATE.plusDays(60))).isFalse();
        fails("INSUFFICIENT_FINANCIAL_BALANCE", () -> advance.repay(advance.version(), repayment(advance, "1")));
    }

    @Test void returnCannotOverwriteActualOffsetsNetRepaymentsOrReservationsAndFailureIsAtomic() {
        var advance = advance(); var settled = new ExpenseUse(UUID.randomUUID(), 1, 0);
        advance.reserve(advance.version(), settled, money("10")); advance.settle(advance.version(), settled);
        var reserved = new ExpenseUse(UUID.randomUUID(), 1, 0); advance.reserve(advance.version(), reserved, money("30"));
        advance.repay(advance.version(), repayment(advance, "20")); advance.requirePaymentReview(advance.version()); var before = advance.state();
        fails("DISBURSEMENT_RETURN_CONFLICTS_WITH_USAGE", () -> advance.resolveDisbursementReview(advance.version(), decision(List.of(item("too-much", "40.01")))));
        assertThat(advance.state()).isEqualTo(before);
        advance.resolveDisbursementReview(advance.version(), decision(List.of(item("allowed", "40"))));
        assertThat(advance.balance().consumed()).isEqualTo(money("10")); assertThat(advance.balance().reserved()).isEqualTo(money("30"));
        assertThat(advance.repaid()).isEqualTo(money("20")); assertThat(advance.outstanding()).isEqualTo(money("30"));
    }

    @Test void repaymentHoldRemainsIndependentAndEarlierReturnsCannotBeRemovedOrRewritten() {
        var advance = advance(); var repaid = repayment(advance, "10"); advance.repay(advance.version(), repaid);
        advance.requireRepaymentReview(advance.version(), repaid.id()); var first = item("first", "20"); apply(advance, List.of(first));
        assertThat(advance.status()).isEqualTo(EmployeeAdvance.Status.REPAYMENT_REVIEW); assertThat(advance.paymentReviewRequired()).isFalse();
        assertThat(advance.repaymentReviews()).containsExactly(repaid.id()); assertThat(advance.outstanding()).isEqualTo(money("70"));
        advance.requirePaymentReview(advance.version()); var before = advance.state();
        fails("DISBURSEMENT_RETURN_SOURCE_CHANGED", () -> advance.resolveDisbursementReview(advance.version(), decision(List.of())));
        fails("DISBURSEMENT_RETURN_SOURCE_CHANGED", () -> advance.resolveDisbursementReview(advance.version(), decision(List.of(item("first", "21")))));
        fails("ADVANCE_PAYMENT_REVIEW_REQUIRED", () -> advance.resolvePaymentReview(advance.version(), advance()));
        assertThat(advance.state()).isEqualTo(before);
    }

    @Test void externalSnapshotRequiresMatchingFullOrPartialStatusAndIndependentUniqueBankAndAccountingFacts() {
        var first = item("first", "40"); var second = item("second", "20"); var receipt = receipt(List.of(first, second));
        assertThat(receipt.totalReturned()).isEqualTo(money("60")); assertThat(receipt.preservesReturns(receipt(List.of(first)))).isTrue();
        assertThat(receipt.sameReturns(receipt(List.of(second, first)))).isTrue();
        invalid(() -> receipt(List.of(first, first)));
        invalid(() -> receipt(List.of(first, new AdvanceDisbursementReturnPort.ReturnItem(item("same-posting", "40").funding(), first.posting()))));
        invalid(() -> receipt(List.of(item("too-much", "100.01"))));
        invalid(() -> receipt(List.of(item(original.paymentReference(), "10"))));
        invalid(() -> new AdvanceDisbursementReturnPort.Receipt(request, AdvanceDisbursementReturnPort.Status.RETURNED, 2, NOW, NOW.plusSeconds(300), observation(PaymentObservation.Status.REVERSED, 2, NOW), List.of(first)));
        invalid(() -> new AdvanceDisbursementReturnPort.Receipt(request, AdvanceDisbursementReturnPort.Status.PARTIALLY_RETURNED, 2, NOW, NOW.plusSeconds(300), observation(PaymentObservation.Status.SUCCEEDED, 2, NOW), List.of(item("full", "100"))));
        invalid(() -> new AdvanceDisbursementReturnPort.ReturnItem(new AdvanceRepaymentPort.Funding(AdvanceRepaymentPort.Channel.CASH, "cash", money("40"), NOW.minusSeconds(2)), first.posting()));
        invalid(() -> new AdvanceDisbursementReturnPort.ReturnItem(new AdvanceRepaymentPort.Funding(AdvanceRepaymentPort.Channel.BANK_TRANSFER, "future", money("40"), NOW.plusSeconds(2)), first.posting()));
    }

    @Test void boundedEvidenceRejectsTooManyRowsExpiredOriginalAndMissingOriginalSuccess() {
        assertThat(receipt(IntStream.range(0, 100).mapToObj(i -> item("bank-" + i, "1")).toList()).totalReturned()).isEqualTo(money("100"));
        invalid(() -> receipt(IntStream.range(0, 101).mapToObj(i -> item("bank-" + i, "0.50")).toList()));
        invalid(() -> new AdvanceDisbursementReturnPort.Request(command, observation(PaymentObservation.Status.REVERSED, 2, NOW)));
        invalid(() -> new AdvanceDisbursementReturnPort.Receipt(request, AdvanceDisbursementReturnPort.Status.CONFIRMED, 2, NOW, NOW.plusSeconds(300), original, List.of()));
        var valid = receipt(List.of(item("first", "20"))); assertThat(valid.matches(request, NOW.plusSeconds(299))).isTrue();
        assertThat(valid.matches(request, NOW.plusSeconds(300))).isFalse(); assertThat(valid.matches(request, NOW.minusSeconds(1))).isFalse();
    }

    @Test void decisionRejectsApplicantOriginalCashierWrongTenantAndExpiredEvidence() {
        var receipt = receipt(List.of(item("first", "20")));
        for (var actor : List.of("alice", "cashier")) assertThatThrownBy(() -> new AdvanceDisbursementReturn(UUID.randomUUID(), "demo", UUID.randomUUID(), receipt, actor, NOW, "proof", "核对")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new AdvanceDisbursementReturn(UUID.randomUUID(), "other", UUID.randomUUID(), receipt, "finance", NOW, "proof", "核对")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new AdvanceDisbursementReturn(UUID.randomUUID(), "demo", UUID.randomUUID(), receipt, "finance", NOW.plusSeconds(300), "proof", "核对")).isInstanceOf(DomainException.class);
        var advance = advance(); advance.requirePaymentReview(advance.version()); var before = advance.state();
        fails("CONCURRENCY_CONFLICT", () -> advance.resolveDisbursementReview(1, decision(receipt.returns()))); assertThat(advance.state()).isEqualTo(before);
    }

    @Test void persistedReadLeaseAndExplicitDecisionHaveSeparateSingleUseTransitions() {
        var input = new AdvanceDisbursementReturnCheck.Input(UUID.randomUUID(), "demo", "b".repeat(64), 4, request, "finance", NOW);
        var queued = AdvanceDisbursementReturnCheck.queue(input); assertThat(queued.active()).isTrue(); assertThat(queued.usable(NOW)).isFalse();
        var claimed = queued.claim(NOW, Duration.ofSeconds(90)); var facts = receipt(List.of(item("bank", "20")));
        var checked = claimed.complete(new FinanceResult.Success<>(facts), NOW.plusSeconds(1)); assertThat(checked.usable(NOW.plusSeconds(299))).isTrue();
        var decision = new AdvanceDisbursementReturn(UUID.randomUUID(), "demo", input.id(), facts, "finance", NOW.plusSeconds(2), "proof", "独立核对");
        var resolved = checked.resolve(decision, NOW.plusSeconds(2)); assertThat(resolved.status()).isEqualTo(AdvanceDisbursementReturnCheck.Status.RESOLVED);
        assertThat(resolved.resolutionId()).isEqualTo(decision.id()); assertThat(resolved.usable(NOW.plusSeconds(3))).isFalse();
        fails("DISBURSEMENT_RETURN_CHECK_CONFLICT", () -> resolved.resolve(decision, NOW.plusSeconds(3)));
        var timedOut = claimed.complete(new FinanceResult.Success<>(facts), NOW.plusSeconds(90)); assertThat(timedOut.issue()).isEqualTo(AdvanceDisbursementReturnCheck.Issue.TIMEOUT);
        assertThat(queued.voidSource(NOW).status()).isEqualTo(AdvanceDisbursementReturnCheck.Status.VOIDED);
        assertThat(checked.usable(NOW.plusSeconds(300))).isFalse();
    }

    private void apply(EmployeeAdvance advance, List<AdvanceDisbursementReturnPort.ReturnItem> items) { advance.requirePaymentReview(advance.version()); advance.resolveDisbursementReview(advance.version(), decision(items)); }
    private AdvanceDisbursementReturn decision(List<AdvanceDisbursementReturnPort.ReturnItem> items) { return new AdvanceDisbursementReturn(UUID.randomUUID(), "demo", UUID.randomUUID(), receipt(items), "finance", NOW, "proof", "核对原放款、入款和贷方分录"); }
    private AdvanceDisbursementReturnPort.Receipt receipt(List<AdvanceDisbursementReturnPort.ReturnItem> items) {
        var total = items.stream().map(item -> item.funding().amount()).reduce(money("0"), Money::plus);
        var status = items.isEmpty() ? AdvanceDisbursementReturnPort.Status.CONFIRMED : total.equals(command.amount()) ? AdvanceDisbursementReturnPort.Status.RETURNED : AdvanceDisbursementReturnPort.Status.PARTIALLY_RETURNED;
        return new AdvanceDisbursementReturnPort.Receipt(request, status, 2, NOW, NOW.plusSeconds(300), observation(status == AdvanceDisbursementReturnPort.Status.RETURNED ? PaymentObservation.Status.REVERSED : PaymentObservation.Status.SUCCEEDED, 2, NOW), items);
    }
    private PaymentObservation observation(PaymentObservation.Status status, long revision, Instant observed) { return new PaymentObservation(command.id(), command.digest(), status, revision, observed, "original-payment", command.amount(), command.payee().accountDigest(), NOW.minusSeconds(30), status == PaymentObservation.Status.REVERSED ? "bank-return-receipt" : "original-receipt", null); }
    private static AdvanceDisbursementReturnPort.ReturnItem item(String reference, String amount) { return new AdvanceDisbursementReturnPort.ReturnItem(new AdvanceRepaymentPort.Funding(AdvanceRepaymentPort.Channel.BANK_TRANSFER, reference, money(amount), NOW.minusSeconds(5)), new AdvanceRepaymentPort.Posting("credit-" + reference, "credit-1", money(amount), DATE, NOW.minusSeconds(4))); }
    private EmployeeAdvance advance() { return new EmployeeAdvance(command.binding().businessId(), "demo", command.payee().legalEntityId(), "alice", command.amount(), original.paymentReference(), DATE, DATE.plusDays(30)); }
    private AdvanceRepayment repayment(EmployeeAdvance advance, String amount) {
        var request = new AdvanceRepaymentPort.Request(advance.id(), advance.legalEntityId(), "alice", advance.paymentReference(), "CNY", "repayment-" + UUID.randomUUID());
        var receipt = new AdvanceRepaymentPort.Receipt(request, AdvanceRepaymentPort.Status.CONFIRMED, 1, NOW, NOW.plusSeconds(300), new AdvanceRepaymentPort.Funding(AdvanceRepaymentPort.Channel.CASH, "cash", money(amount), NOW.minusSeconds(10)), new AdvanceRepaymentPort.Posting("repayment-credit", "credit-1", money(amount), DATE, NOW.minusSeconds(9)));
        return new AdvanceRepayment(UUID.randomUUID(), "demo", UUID.randomUUID(), receipt, "finance", NOW, "核对实际还款");
    }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    private static void invalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) { fails("INVALID_DISBURSEMENT_RETURN_RECEIPT", action); }
    private static void fails(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) { assertThatThrownBy(action).isInstanceOfSatisfying(DomainException.class, exception -> assertThat(exception.code()).isEqualTo(code)); }
}
