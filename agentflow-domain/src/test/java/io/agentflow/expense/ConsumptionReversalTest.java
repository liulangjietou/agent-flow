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
import static org.assertj.core.api.Assertions.*;

/**
 * 独立冲回保留原核销、防重、其他用量和冻结；重新报销发票必须重新查验。
 * @author owlzhangfq@gmail.com
 */
class ConsumptionReversalTest {
    private static final UUID ENTITY = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");
    private static final LocalDate DATE = LocalDate.parse("2026-09-29");
    private static final String DIGEST = "a".repeat(64);

    @Test void amountReversalRetainsOriginalUsesAndOtherReservationsWhileAllowingDifferentReports() {
        var original = use(1); var other = use(1); var adjustment = UUID.randomUUID();
        var before = ReservedAmount.available(money("100")).reserve(original, money("70")).consume(original).reserve(other, money("30"));
        var reversed = before.reverseConsumption(original, adjustment, NOW);
        assertThat(reversed.consumptions()).isEqualTo(before.consumptions());
        assertThat(reversed.reservations()).isEqualTo(before.reservations());
        assertThat(reversed.consumedFor(original)).isTrue();
        assertThat(reversed.consumedAmountFor(original)).isEqualTo(money("70"));
        assertThat(reversed.grossConsumed()).isEqualTo(money("70"));
        assertThat(reversed.consumed()).isEqualTo(money("0"));
        assertThat(reversed.available()).isEqualTo(money("70"));
        fails("CONSUMPTION_REVERSAL_CONFLICT", () -> reversed.reverseConsumption(original, UUID.randomUUID(), NOW));
        fails("RESERVATION_ALREADY_CONSUMED", () -> reversed.reserve(original, money("1")));
        var next = use(1); var reused = reversed.reserve(next, money("70")).consume(next).consume(other);
        assertThat(reused.grossConsumed()).isEqualTo(money("170"));
        assertThat(reused.consumed()).isEqualTo(money("100"));
        assertThat(reused.available()).isEqualTo(money("0"));
        assertThat(reused.reversals()).containsExactly(new ReservedAmount.ConsumptionReversal(adjustment, original, money("70"), NOW));
        assertThat(before.available()).isEqualTo(money("0"));
    }

    @Test void restoredAmountReversalsCannotInventFundsOrChangeTheOriginalAmount() {
        var use = use(0); var original = new ReservedAmount.Reservation(use, money("20")); var id = UUID.randomUUID();
        var reversal = new ReservedAmount.ConsumptionReversal(id, use, money("20"), NOW);
        fails("INVALID_RESERVATION", () -> new ReservedAmount(money("100"), List.of(), List.of(), List.of(reversal)));
        fails("INVALID_RESERVATION", () -> new ReservedAmount(money("100"), List.of(original), List.of(),
                List.of(new ReservedAmount.ConsumptionReversal(id, use, money("19"), NOW))));
        fails("INVALID_RESERVATION", () -> new ReservedAmount(money("100"), List.of(original), List.of(), List.of(reversal, reversal)));
        var old = new ReservedAmount(money("100"), List.of(original), List.of());
        assertThat(old.reversals()).isEmpty(); assertThat(old.consumed()).isEqualTo(money("20"));
        fails("CONSUMPTION_REVERSAL_CONFLICT", () -> old.reverseConsumption(use(0), id, NOW));
        assertThatThrownBy(() -> old.reverseConsumption(use, id, NOW).reversals().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void closedPriorRequestStaysClosedAndIndependentLoanHoldsAreNotCleared() {
        var original = use(1); var request = new ExpenseRequest(UUID.randomUUID(), "demo", UUID.randomUUID(), ENTITY, "alice",
                List.of(new ExpenseRequest.ApprovedLine(1, money("100"), BigDecimal.ZERO, "policy-v1")));
        request.reserve(1, 1, original, money("70")); request.consume(2, 1, original); request.close(3);
        request.reverseConsumption(4, 1, original, UUID.randomUUID(), NOW);
        assertThat(request.closed()).isTrue(); assertThat(request.balance(1).available()).isEqualTo(money("100"));
        fails("EXPENSE_REQUEST_CLOSED", () -> request.reserve(5, 1, use(1), money("1")));
        var advance = new EmployeeAdvance(UUID.randomUUID(), "demo", ENTITY, "alice", money("100"), "original-payment", DATE, DATE.plusDays(30));
        var offset = use(0); var other = use(0); var voucher = UUID.randomUUID();
        advance.reserve(1, offset, money("70")); advance.settle(2, offset); advance.reserve(3, other, money("30"));
        advance.requirePaymentReview(4); advance.requireVoucherReview(5, voucher);
        var before = advance.state(); advance.reverseOffset(6, offset, UUID.randomUUID(), NOW);
        assertThat(advance.outstanding()).isEqualTo(money("100")); assertThat(advance.available()).isEqualTo(money("0"));
        assertThat(advance.paymentReviewRequired()).isTrue(); assertThat(advance.voucherReviews()).containsExactly(voucher);
        assertThat(advance.balance().reservations()).isEqualTo(before.balance().reservations());
        assertThat(advance.balance().consumptions()).isEqualTo(before.balance().consumptions());
        assertThat(advance.repayments()).isEqualTo(before.repayments()); assertThat(advance.paymentReference()).isEqualTo(before.paymentReference());
        assertThat(EmployeeAdvance.restore(advance.state()).state()).isEqualTo(advance.state());
    }

    @Test void invoiceReversalPreservesOriginalFactsAndRequiresNewVerificationBeforeReuse() {
        var invoice = invoice(); var use = use(1); var id = UUID.randomUUID();
        invoice.occupy(2, use, "alice", ENTITY, NOW); invoice.consume(3, use, NOW);
        var consumed = invoice.state(); invoice.reverseConsumption(4, use, id, NOW.plusSeconds(1));
        assertThat(invoice.occupation()).isEqualTo(Invoice.Occupation.AVAILABLE); assertThat(invoice.use()).isNull();
        assertThat(invoice.verification()).isEqualTo(Invoice.Verification.PENDING);
        assertThat(invoice.facts()).isEqualTo(consumed.facts()); assertThat(invoice.originalDigest()).isEqualTo(consumed.originalDigest());
        assertThat(invoice.reversals()).containsExactly(new Invoice.ConsumptionReversal(id, use, NOW.plusSeconds(1)));
        assertThat(Invoice.restore(invoice.state()).state()).isEqualTo(invoice.state());
        fails("INVOICE_VERIFICATION_REQUIRED", () -> invoice.occupy(5, use(1), "alice", ENTITY, NOW.plusSeconds(2)));
        fails("INVALID_INVOICE_TIME", () -> invoice.verified(5, facts(NOW)));
        invoice.verified(5, facts(NOW.plusSeconds(2)));
        fails("CONSUMPTION_REVERSAL_CONFLICT", () -> invoice.occupy(6, use, "alice", ENTITY, NOW.plusSeconds(2)));
        invoice.occupy(6, use(1), "alice", ENTITY, NOW.plusSeconds(2));
        assertThat(invoice.reversals()).hasSize(1); assertThat(invoice.version()).isEqualTo(7);
        assertThat(consumed.occupation()).isEqualTo(Invoice.Occupation.CONSUMED);
    }

    @Test void invoiceReversalCannotUseOrdinaryReleaseOrAnotherRoundAndRejectsForgedVerifiedSnapshot() {
        var invoice = invoice(); var use = use(1); invoice.occupy(2, use, "alice", ENTITY, NOW); invoice.consume(3, use, NOW);
        fails("INVOICE_OCCUPATION_CHANGED", () -> invoice.release(4, use));
        fails("CONSUMPTION_REVERSAL_CONFLICT", () -> invoice.reverseConsumption(4, new ExpenseUse(use.reportId(), 2, 1), UUID.randomUUID(), NOW));
        invoice.invalidated(4, "VOIDED", NOW.plusSeconds(1));
        invoice.reverseConsumption(5, use, UUID.randomUUID(), NOW.plusSeconds(2));
        var state = invoice.state();
        fails("INVALID_INVOICE", () -> Invoice.restore(new Invoice.State(state.id(), state.tenantId(), state.ownerId(), state.originalFileId(),
                state.originalDigest(), state.version(), Invoice.Verification.VERIFIED, state.occupation(), state.facts(), null, state.checkedAt(), state.use(), state.reversals())));
        assertThat(invoice.verification()).isEqualTo(Invoice.Verification.PENDING);
        var old = invoice(); var oldState = old.state();
        assertThat(Invoice.restore(new Invoice.State(oldState.id(), oldState.tenantId(), oldState.ownerId(), oldState.originalFileId(), oldState.originalDigest(),
                oldState.version(), oldState.verification(), oldState.occupation(), oldState.facts(), null, oldState.checkedAt(), null)).reversals()).isEmpty();
    }

    private Invoice invoice() {
        var value = Invoice.uploaded(UUID.randomUUID(), "demo", "alice", UUID.randomUUID(), DIGEST); value.verified(1, facts(NOW)); return value;
    }
    private Invoice.VerifiedFacts facts(Instant at) {
        return new Invoice.VerifiedFacts(new InvoiceKey(InvoiceKey.Type.DIGITAL, null, "12345678901234567890"), ENTITY, money("100"), money("0"), DATE, DIGEST,
                "invoice-original", at, at.plusSeconds(300));
    }
    private ExpenseUse use(int line) { return new ExpenseUse(UUID.randomUUID(), 1, line); }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    private void fails(String code, Runnable action) { assertThatExceptionOfType(DomainException.class).isThrownBy(action::run).satisfies(error -> assertThat(error.code()).isEqualTo(code)); }
}
