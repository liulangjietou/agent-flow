package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import io.agentflow.finance.ReservedAmount;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 预留不等于核销；以跨轮次释放、额度争抢、查验失效和关闭后的余额为主要风险。
 * @author owlzhangfq@gmail.com
 */
class ExpenseResourcesTest {
    private static final UUID ENTITY = UUID.randomUUID();
    private static final String DIGEST = "a".repeat(64);
    private static final Instant CHECKED = Instant.parse("2026-09-28T10:00:00Z");
    private static final Instant EXPIRES = CHECKED.plusSeconds(60);
    private static final LocalDate PAID = LocalDate.parse("2026-09-01"), DUE = LocalDate.parse("2026-09-28");

    @Test
    void offsetCapacityOnlyRestoresTheSpecifiedReservationAndNeverUnfreezesReviewedFunds() {
        var advance = advance(); var retained = use(0); var other = use(0);
        advance.reserve(1, retained, money("40")); advance.reserve(2, other, money("20"));
        assertThat(advance.offsetCapacity(null)).isEqualTo(money("40"));
        assertThat(advance.offsetCapacity(retained)).isEqualTo(money("80"));
        assertThat(advance.offsetCapacity(new ExpenseUse(retained.reportId(), 2, 0))).isEqualTo(money("40"));
        var state = advance.state();
        advance.requirePaymentReview(advance.version()); assertThat(advance.offsetCapacity(retained)).isEqualTo(money("0"));
        advance = EmployeeAdvance.restore(state); advance.requireVoucherReview(advance.version(), UUID.randomUUID());
        assertThat(advance.offsetCapacity(retained)).isEqualTo(money("0"));
        assertThat(advance.balance().reservedFor(retained)).isEqualTo(money("40"));
    }

    @Test
    void reservationReplacementConservesBalanceAndDoesNotOverwriteOtherReports() {
        var first = use(1); var second = use(1);
        var balance = ReservedAmount.available(money("100")).reserve(first, money("60")).reserve(second, money("40"));
        assertThat(balance.available()).isEqualTo(money("0"));
        fails("INSUFFICIENT_FINANCIAL_BALANCE", () -> balance.reserve(first, money("60.01")));
        var reduced = balance.reserve(first, money("20"));
        assertThat(reduced.available()).isEqualTo(money("40"));
        assertThat(reduced.reservedFor(second)).isEqualTo(money("40"));
        var consumed = reduced.consume(second);
        assertThat(consumed.available()).isEqualTo(money("40"));
        assertThat(consumed.consumed()).isEqualTo(money("40"));
        fails("RESERVATION_NOT_FOUND", () -> consumed.consume(second));
        assertThat(balance.consumed()).isEqualTo(money("0"));
    }

    @Test
    void retainedReservationsMoveAtomicallyAndOldRoundReleaseCannotRemoveTheNewOne() {
        var old = use(1); var next = new ExpenseUse(old.reportId(), 2, old.lineNo());
        var balance = ReservedAmount.available(money("100")).reserve(old, money("80"));
        fails("INSUFFICIENT_FINANCIAL_BALANCE", () -> balance.move(old, next, money("101")));
        assertThat(balance.reservedFor(old)).isEqualTo(money("80"));
        var moved = balance.move(old, next, money("90"));
        assertThat(moved.reservedFor(old)).isEqualTo(money("0"));
        var staleRelease = moved.reserve(old, money("0"));
        assertThat(staleRelease.reservedFor(next)).isEqualTo(money("90"));
        fails("RESERVATION_NOT_FOUND", () -> moved.consume(old));
        fails("INVALID_RESERVATION", () -> balance.move(old, new ExpenseUse(UUID.randomUUID(), 2, old.lineNo()), money("80")));
    }

    @Test
    void ledgerRejectsDuplicateOwnersCurrencyMismatchAndNegativeAvailableFunds() {
        var use = use(1);
        fails("INVALID_RESERVATION", () -> new ReservedAmount(money("100"), List.of(), List.of(
                new ReservedAmount.Reservation(use, money("1")), new ReservedAmount.Reservation(use, money("1")))));
        fails("CURRENCY_MISMATCH", () -> ReservedAmount.available(money("100")).reserve(use, new Money(BigDecimal.ONE, "USD")));
        fails("INSUFFICIENT_FINANCIAL_BALANCE", () -> new ReservedAmount(money("100"), List.of(new ReservedAmount.Reservation(use, money("101"))), List.of()));
        var mutable = new java.util.ArrayList<>(List.of(new ReservedAmount.Reservation(use, money("1"))));
        var ledger = new ReservedAmount(money("10"), List.of(), mutable); mutable.clear();
        assertThat(ledger.reservations()).hasSize(1);
        assertThatThrownBy(() -> ledger.reservations().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void alreadyConsumedRoundCannotBeReservedAgainByAStaleSubmission() {
        var use = use(0); var advance = advance();
        advance.reserve(1, use, money("40")); advance.settle(2, use);
        fails("RESERVATION_ALREADY_CONSUMED", () -> advance.reserve(3, use, money("40")));
        assertThat(advance.balance().consumed()).isEqualTo(money("40"));
        assertThat(advance.balance().available()).isEqualTo(money("60"));
        assertThat(advance.version()).isEqualTo(3);
    }

    @Test
    void approvedToleranceIsAppliedOnceAndRoundedDownWithoutCreatingAnExtraCent() {
        var line = new ExpenseRequest.ApprovedLine(1, money("0.03"), new BigDecimal("0.5"), "explicit-policy-v1");
        assertThat(line.limit()).isEqualTo(money("0.04"));
        var request = request(List.of(line)); var use = use(1);
        request.reserve(1, 1, use, money("0.04"));
        assertThat(request.balance(1).available()).isEqualTo(money("0"));
        fails("INSUFFICIENT_FINANCIAL_BALANCE", () -> request.reserve(2, 1, use, money("0.05")));
        assertThat(request.version()).isEqualTo(2);
        assertThat(request.approvedLines().get(0).approvedAmount()).isEqualTo(money("0.03"));
    }

    @Test
    void closedPriorRequestAllowsReductionAndConsumptionButNoNewOrIncreasedReservation() {
        var request = request(List.of(new ExpenseRequest.ApprovedLine(1, money("100"), BigDecimal.ZERO, "policy-v1")));
        var old = use(1); request.reserve(1, 1, old, money("70")); request.close(2);
        fails("EXPENSE_REQUEST_CLOSED", () -> request.reserve(3, 1, use(1), money("1")));
        fails("EXPENSE_REQUEST_CLOSED", () -> request.reserve(3, 1, old, money("71")));
        request.reserve(3, 1, old, money("60"));
        var next = new ExpenseUse(old.reportId(), 2, 1); request.move(4, 1, old, next, money("50"));
        request.consume(5, 1, next);
        assertThat(request.balance(1).consumed()).isEqualTo(money("50"));
        assertThat(request.balance(1).available()).isEqualTo(money("50"));
        assertThat(request.closed()).isTrue();
        fails("EXPENSE_REQUEST_CLOSED", () -> request.reserve(6, 1, use(1), money("1")));
    }

    @Test
    void priorRequestLinesCannotBorrowEachOthersLimitsOrBypassVersion() {
        var request = request(List.of(new ExpenseRequest.ApprovedLine(1, money("10"), BigDecimal.ZERO, "policy-v1"),
                new ExpenseRequest.ApprovedLine(2, money("90"), BigDecimal.ZERO, "policy-v1")));
        fails("INSUFFICIENT_FINANCIAL_BALANCE", () -> request.reserve(1, 1, use(1), money("11")));
        fails("PRIOR_REQUEST_LINE_NOT_FOUND", () -> request.reserve(1, 3, use(1), money("1")));
        request.reserve(1, 1, use(1), money("10"));
        fails("CONCURRENCY_CONFLICT", () -> request.close(1));
        assertThat(request.balance(2).available()).isEqualTo(money("90"));
    }

    @Test
    void advanceReservationDoesNotCountAsSettlementAndOverdueStatusStopsOnlyWhenSettled() {
        var advance = advance(); var first = use(0); var second = use(0);
        advance.reserve(1, first, money("40")); advance.reserve(2, second, money("60"));
        assertThat(advance.balance().available()).isEqualTo(money("0"));
        assertThat(advance.status()).isEqualTo(EmployeeAdvance.Status.PAID_OUT);
        assertThat(advance.overdue(DUE)).isFalse(); assertThat(advance.overdue(DUE.plusDays(1))).isTrue();
        advance.settle(3, first);
        assertThat(advance.status()).isEqualTo(EmployeeAdvance.Status.PARTIALLY_SETTLED);
        advance.settle(4, second);
        assertThat(advance.status()).isEqualTo(EmployeeAdvance.Status.SETTLED);
        assertThat(advance.overdue(DUE.plusDays(1))).isFalse();
        fails("RESERVATION_NOT_FOUND", () -> advance.settle(5, second));
        assertThat(advance.version()).isEqualTo(5);
    }

    @Test
    void delayedDisbursementRetainsOriginalDueDateAndIsOverdueOnlyAfterItActuallyArrives() {
        var paidOn = DUE.plusDays(2);
        var advance = new EmployeeAdvance(UUID.randomUUID(), "demo", ENTITY, "alice", money("100"), "delayed-payment", paidOn, DUE);
        assertThat(advance.paidOn()).isEqualTo(paidOn);
        assertThat(advance.dueOn()).isEqualTo(DUE);
        assertThat(advance.overdue(DUE.plusDays(1))).isFalse();
        assertThat(advance.overdue(paidOn)).isTrue();
        assertThat(EmployeeAdvance.restore(advance.state()).state()).isEqualTo(advance.state());
    }

    @Test
    void disputedPaymentPreservesLedgerAndOnlyAllowsReservationReductionOrRelease() {
        var advance = advance(); var retained = use(0); var consumed = use(0);
        advance.reserve(1, retained, money("40")); advance.reserve(2, consumed, money("30")); advance.settle(3, consumed);
        advance.requirePaymentReview(4);
        assertThat(advance.status()).isEqualTo(EmployeeAdvance.Status.PAYMENT_REVIEW);
        assertThat(advance.available()).isEqualTo(money("0"));
        assertThat(advance.balance().available()).isEqualTo(money("30"));
        assertThat(advance.balance().consumed()).isEqualTo(money("30"));
        fails("ADVANCE_PAYMENT_REVIEW_REQUIRED", () -> advance.reserve(5, use(0), money("1")));
        fails("ADVANCE_PAYMENT_REVIEW_REQUIRED", () -> advance.move(5, retained, new ExpenseUse(retained.reportId(), 2, 0), money("40")));
        fails("ADVANCE_PAYMENT_REVIEW_REQUIRED", () -> advance.settle(5, retained));
        advance.reserve(5, retained, money("20")); advance.reserve(6, retained, money("0"));
        assertThat(EmployeeAdvance.restore(advance.state()).paymentReviewRequired()).isTrue();
        advance.requirePaymentReview(7); assertThat(advance.version()).isEqualTo(7);
    }

    @Test void explicitOriginalPaymentRecoveryKeepsUsedAndReservedAmountsAndRejectsAnotherDisbursement() {
        var advance = advance(); var retained = use(0); var consumed = use(0); var original = EmployeeAdvance.restore(advance.state());
        advance.reserve(1, retained, money("40")); advance.reserve(2, consumed, money("30")); advance.settle(3, consumed);
        advance.requirePaymentReview(4); var held = advance.state();
        fails("ADVANCE_PAYMENT_REVIEW_REQUIRED", () -> advance.resolvePaymentReview(5, advance()));
        assertThat(advance.state()).isEqualTo(held); advance.resolvePaymentReview(5, original);
        assertThat(advance.balance()).isEqualTo(held.balance()); assertThat(advance.available()).isEqualTo(money("30"));
        assertThat(advance.status()).isEqualTo(EmployeeAdvance.Status.PARTIALLY_SETTLED); assertThat(advance.version()).isEqualTo(6);
        fails("ADVANCE_PAYMENT_REVIEW_REQUIRED", () -> advance.resolvePaymentReview(6, original));
    }

    @Test
    void advanceRequiresWholeReportReferencesAndRetainsItsBalanceAfterRejectedChanges() {
        var advance = advance(); var use = use(0); advance.reserve(1, use, money("80"));
        fails("INVALID_ADVANCE_OFFSET", () -> advance.reserve(2, use(1), money("1")));
        fails("INSUFFICIENT_FINANCIAL_BALANCE", () -> advance.reserve(2, use, money("101")));
        fails("CONCURRENCY_CONFLICT", () -> advance.settle(1, use));
        var next = new ExpenseUse(use.reportId(), 2, 0); advance.move(2, use, next, money("70"));
        assertThat(advance.balance().reservedFor(use)).isEqualTo(money("0"));
        assertThat(advance.balance().reservedFor(next)).isEqualTo(money("70"));
    }

    @Test
    void originalFileVerificationAndBuyerMustMatchBeforeAnInvoiceCanBeOccupied() {
        var invoice = invoice(); var use = use(1);
        fails("INVOICE_VERIFICATION_REQUIRED", () -> invoice.occupy(1, use, "alice", ENTITY, CHECKED));
        var wrongFile = new Invoice.VerifiedFacts(key(), ENTITY, money("100"), money("6"), DUE, "b".repeat(64), "ref", CHECKED, EXPIRES);
        fails("INVALID_INVOICE", () -> invoice.verified(1, wrongFile));
        invoice.verified(1, facts(CHECKED, EXPIRES));
        fails("INVOICE_OWNER_MISMATCH", () -> invoice.occupy(2, use, "bob", ENTITY, CHECKED));
        fails("INVOICE_TITLE_MISMATCH", () -> invoice.occupy(2, use, "alice", UUID.randomUUID(), CHECKED));
        fails("INVOICE_VERIFICATION_REQUIRED", () -> invoice.occupy(2, use, "alice", ENTITY, EXPIRES));
        invoice.occupy(2, use, "alice", ENTITY, CHECKED.plusSeconds(1));
        assertThat(invoice.occupation()).isEqualTo(Invoice.Occupation.OCCUPIED);
        fails("INVOICE_OCCUPIED", () -> invoice.occupy(3, use(1), "alice", ENTITY, CHECKED.plusSeconds(2)));
    }

    @Test
    void newVerificationCannotChangeAnAlreadyVerifiedInvoiceIdentityOrMoney() {
        var invoice = invoice(); invoice.verified(1, facts(CHECKED, EXPIRES));
        var wrong = new Invoice.VerifiedFacts(key(), ENTITY, money("101"), money("6"), DUE, DIGEST, "ref-2", CHECKED.plusSeconds(1), EXPIRES);
        fails("INVOICE_FACTS_CHANGED", () -> invoice.verified(2, wrong));
        assertThat(invoice.facts().gross()).isEqualTo(money("100")); assertThat(invoice.version()).isEqualTo(2);
        fails("INVALID_INVOICE_TIME", () -> invoice.invalidated(2, "CANCELLED", CHECKED.minusSeconds(1)));
    }

    @Test
    void invalidatedInvoiceKeepsItsOccupationAndCannotBeConsumedUntilVerifiedAgain() {
        var invoice = invoice(); var use = use(1);
        invoice.verified(1, facts(CHECKED, EXPIRES)); invoice.occupy(2, use, "alice", ENTITY, CHECKED);
        invoice.invalidated(3, "CANCELLED", CHECKED.plusSeconds(1));
        assertThat(invoice.occupation()).isEqualTo(Invoice.Occupation.OCCUPIED);
        assertThat(invoice.use()).isEqualTo(use); assertThat(invoice.facts()).isNotNull();
        fails("INVOICE_VERIFICATION_REQUIRED", () -> invoice.consume(4, use, CHECKED.plusSeconds(2)));
        invoice.release(4, use);
        assertThat(invoice.occupation()).isEqualTo(Invoice.Occupation.AVAILABLE);
        assertThat(invoice.verification()).isEqualTo(Invoice.Verification.FAILED);
        fails("INVOICE_VERIFICATION_REQUIRED", () -> invoice.occupy(5, use(1), "alice", ENTITY, CHECKED.plusSeconds(2)));
    }

    @Test
    void movingAnInvoiceBlocksOldRoundReleaseAndConsumptionIsIrreversible() {
        var invoice = invoice(); var old = use(1); var next = new ExpenseUse(old.reportId(), 2, 2);
        invoice.verified(1, facts(CHECKED, EXPIRES)); invoice.occupy(2, old, "alice", ENTITY, CHECKED);
        invoice.move(3, old, next, ENTITY, CHECKED.plusSeconds(1));
        fails("INVOICE_OCCUPATION_CHANGED", () -> invoice.release(4, old));
        fails("INVOICE_OCCUPATION_CHANGED", () -> invoice.consume(4, old, CHECKED.plusSeconds(2)));
        invoice.consume(4, next, CHECKED.plusSeconds(2));
        assertThat(invoice.occupation()).isEqualTo(Invoice.Occupation.CONSUMED);
        fails("INVOICE_OCCUPATION_CHANGED", () -> invoice.release(5, next));
        fails("INVOICE_OCCUPIED", () -> invoice.occupy(5, use(1), "alice", ENTITY, CHECKED.plusSeconds(2)));
    }

    @Test
    void canonicalInvoiceNumbersPreserveLeadingZerosAndNeverUseOriginalFileHash() {
        assertThat(key().canonical()).isEqualTo("D:00000000000000000001");
        assertThat(new InvoiceKey(InvoiceKey.Type.TRADITIONAL, "0012", "0034").canonical()).isEqualTo("T:0012:0034");
        fails("INVALID_INVOICE_KEY", () -> new InvoiceKey(InvoiceKey.Type.DIGITAL, null, "1"));
        fails("INVALID_INVOICE_KEY", () -> new InvoiceKey(InvoiceKey.Type.DIGITAL, "0012", "00000000000000000001"));
        fails("INVALID_INVOICE_KEY", () -> new InvoiceKey(InvoiceKey.Type.TRADITIONAL, "00:12", "0034"));
    }

    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    private static ExpenseUse use(int line) { return new ExpenseUse(UUID.randomUUID(), 1, line); }
    private static ExpenseRequest request(List<ExpenseRequest.ApprovedLine> lines) { return new ExpenseRequest(UUID.randomUUID(), "demo", UUID.randomUUID(), ENTITY, "alice", lines); }
    private static EmployeeAdvance advance() { return new EmployeeAdvance(UUID.randomUUID(), "demo", ENTITY, "alice", money("100"), "actual-payment-1", PAID, DUE); }
    private static Invoice invoice() { return Invoice.uploaded(UUID.randomUUID(), "demo", "alice", UUID.randomUUID(), DIGEST); }
    private static InvoiceKey key() { return new InvoiceKey(InvoiceKey.Type.DIGITAL, null, "00000000000000000001"); }
    private static Invoice.VerifiedFacts facts(Instant checked, Instant expires) { return new Invoice.VerifiedFacts(key(), ENTITY, money("100"), money("6"), DUE, DIGEST, "verification-ref", checked, expires); }
    private static void fails(String code, Runnable operation) {
        assertThatExceptionOfType(DomainException.class).isThrownBy(operation::run).satisfies(error -> assertThat(error.code()).isEqualTo(code));
    }
}
