package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.agentflow.procurement.ProcurementPayableTest.money;
import static org.assertj.core.api.Assertions.*;

/**
 * 已收资金与独立记账分别完成，新原件不能清掉未记账资金，也不能重复登记已确认分录。
 * @author owlzhangfq@gmail.com
 */
class SupplierPaymentReturnAccountingTest {
    @Test void adjustmentEndsOnlyTheOriginalHeldReservationWithoutRewritingAnEarlierSettlement() {
        var done = done(); var original = done.command().source().returns().request().command().holdCommand().authorization().source().reservation();
        var adjusted = original.adjust(done, done.updatedAt());
        assertThat(adjusted.held()).isFalse(); assertThat(adjusted.version()).isEqualTo(2);
        assertThat(adjusted.release()).isNull(); assertThat(adjusted.settlement()).isNull();
        assertThat(adjusted.source()).isEqualTo(original.source()); assertThat(adjusted.heldAt()).isEqualTo(original.heldAt());
        assertThat(adjusted.adjustment().operationId()).isEqualTo(done.command().id());
        assertThatThrownBy(() -> adjusted.adjust(done, done.updatedAt())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> original.adjust(done.requestQuery(done.updatedAt()), done.updatedAt())).isInstanceOf(DomainException.class);
        var settled = new ProcurementPayableReservation(original.id(), original.source(), 2, original.heldAt(), null,
                new ProcurementPayableReservation.Settlement(UUID.randomUUID(), 4, adjusted.adjustment().paymentId(), done.updatedAt()));
        assertThatThrownBy(() -> settled.adjust(done, done.updatedAt())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new ProcurementPayableReservation(original.id(), original.source(), 2, original.heldAt(), null, settled.settlement(), adjusted.adjustment())).isInstanceOf(DomainException.class);
    }

    @Test void confirmedAccountingKeepsOriginalFundsAndRequiresFreshPostExecutionBankEvidence() {
        var done = done(); var command = done.command(); var before = command.source().returns(); var at = done.updatedAt().plusSeconds(1);
        var proof = proof(command, before.entries(), at); var accounted = before.account(done, proof, at);
        assertThat(accounted.reviewRequired()).isFalse(); assertThat(accounted.entries()).isEqualTo(before.entries());
        assertThat(accounted.totalReturned()).isEqualTo(before.totalReturned()); assertThat(accounted.request()).isEqualTo(before.request());
        assertThat(accounted.accounting().operationId()).isEqualTo(command.id()); assertThat(accounted.accountedEntryCount()).isEqualTo(1);
        assertThatThrownBy(() -> accounted.account(done, proof, at)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> before.account(done, proof, proof.validUntil())).isInstanceOf(DomainException.class);
        var missing = new SupplierPaymentReturnPort.Receipt(before.request(), SupplierPaymentReturnPort.Status.UNRESOLVED, 4, at, at.plusSeconds(300), null, List.of());
        assertThatThrownBy(() -> before.account(done, missing, at)).isInstanceOf(DomainException.class);
        var unknown = done.requestQuery(done.updatedAt());
        assertThatThrownBy(() -> before.account(unknown, proof, at)).isInstanceOf(DomainException.class);
    }

    @Test void laterRegisteredFundsRemainPendingWhileOriginalConfirmedPortionIsAccounted() {
        var done = done(); var command = done.command(); var before = command.source().returns(); var at = done.updatedAt().plusSeconds(2);
        var entries = added(before, "10", at.minusSeconds(1)); var current = before.register(decision(command, proof(command, entries, at), at));
        var accounted = current.account(done, proof(command, current.entries(), at.plusSeconds(1)), at.plusSeconds(1));
        assertThat(accounted.reviewRequired()).isTrue(); assertThat(accounted.accountedEntryCount()).isEqualTo(1);
        assertThat(accounted.totalReturned()).isEqualTo(money("30")); assertThat(accounted.entries().get(0)).isEqualTo(before.entries().get(0));
        var payment = before.request().command();
        var previous = new SupplierPayableAdjustmentSource.Previous(payment.id(), payment.digest(), null, done.version(), before.entries(), done.observation());
        var source = new SupplierPayableAdjustmentSource(accounted, null, previous);
        assertThat(source.newReturned()).isEqualTo(money("10"));
        assertThatThrownBy(() -> new SupplierPayableAdjustmentSource(accounted, null, null)).isInstanceOf(DomainException.class);
    }

    @Test void explicitFreshBankConfirmationPreservesAccountingButNewFundsReopenAdjustment() {
        var done = done(); var command = done.command(); var at = done.updatedAt().plusSeconds(1);
        var accounted = command.source().returns().account(done, proof(command, command.source().returns().entries(), at), at);
        var disputed = accounted.requireReview(at.plusSeconds(1)); assertThat(disputed.reviewRequired()).isTrue();
        assertThat(disputed.accounting()).isEqualTo(accounted.accounting());
        var confirmed = disputed.register(decision(command, proof(command, disputed.entries(), at.plusSeconds(2)), at.plusSeconds(2)));
        assertThat(confirmed.reviewRequired()).isFalse(); assertThat(confirmed.accounting()).isEqualTo(accounted.accounting());
        var fresh = confirmed.register(decision(command, proof(command, added(confirmed, "10", at.plusSeconds(3)), at.plusSeconds(4)), at.plusSeconds(4)));
        assertThat(fresh.reviewRequired()).isTrue(); assertThat(fresh.accountedEntryCount()).isEqualTo(1);
        assertThat(fresh.accounting()).isEqualTo(accounted.accounting()); assertThat(fresh.entries()).hasSize(2);
    }

    @Test void unregisteredOrReassignedBankReceiptsCannotCompleteLocalAccounting() {
        var done = done(); var command = done.command(); var before = command.source().returns(); var at = done.updatedAt().plusSeconds(1);
        var extra = proof(command, added(before, "10", at), at);
        assertThatThrownBy(() -> before.account(done, extra, at)).isInstanceOf(DomainException.class);
        var changed = new SupplierPaymentReturns(before.request(), before.version() + 1, List.of(new SupplierPaymentReturns.Entry(UUID.randomUUID(), before.entries().get(0).proof())), true, before.createdAt(), at);
        assertThatThrownBy(() -> changed.account(done, proof(command, changed.entries(), at), at)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new SupplierPaymentReturns(before.request(), before.version(), before.entries(), false, before.createdAt(), before.updatedAt())).isInstanceOf(DomainException.class);
    }

    @Test void previousAccountingMustMatchTheExactImmutablePrefixInsteadOfAnyEqualAmountEntry() {
        var done = done(); var command = done.command(); var before = command.source().returns(); var at = done.updatedAt().plusSeconds(1);
        var accounted = before.account(done, proof(command, before.entries(), at), at);
        var later = accounted.register(decision(command, proof(command, added(accounted, "20", at.plusSeconds(1)), at.plusSeconds(2)), at.plusSeconds(2)));
        var second = later.entries().get(1); var p = done.observation().posting();
        var changed = new SupplierPayableAdjustmentObservation.Posting(p.adjustmentReference(), p.holdReference(), p.ledgerVersion(), p.recognitionVoucherReference(), p.returnedAmount(), p.totalReturned(), p.netPaid(), p.payableSettledBefore(), p.payableSettledAfter(),
                List.of(new SupplierPayableAdjustmentObservation.ReturnEntry(second.proof().transactionReference(), second.proof().amount(), "return-voucher", "entry-1")), p.periodReference(), p.accountingDate(), at.plusSeconds(1));
        var observation = new SupplierPayableAdjustmentObservation(command.id(), command.digest(), SupplierPayableAdjustmentObservation.Status.ADJUSTED, 1, at.plusSeconds(1), changed, null);
        var payment = before.request().command(); var previous = new SupplierPayableAdjustmentSource.Previous(payment.id(), payment.digest(), null, done.version(), List.of(second), observation);
        var forged = new SupplierPaymentReturns(later.request(), later.version(), later.entries(), true, later.createdAt(), at.plusSeconds(2), new SupplierPaymentReturns.Accounting(command.id(), done.version(), 1, at.plusSeconds(1)));
        assertThatThrownBy(() -> new SupplierPayableAdjustmentSource(forged, null, previous)).isInstanceOf(DomainException.class);
    }

    private static SupplierPayableAdjustmentOperation done() {
        var command = SupplierPayableAdjustmentTest.command(false, "20"); var at = command.registeredAt();
        return SupplierPayableAdjustmentOperation.queue(command, at).claim(at, Duration.ofSeconds(30))
                .readyToSend(SupplierPayableAdjustmentTest.evidence(command, at), at)
                .complete(new FinanceResult.Success<>(SupplierPayableAdjustmentTest.adjusted(command, 1, at, "30", "80")), at);
    }
    private static List<SupplierPaymentReturns.Entry> added(SupplierPaymentReturns before, String amount, Instant at) {
        var entries = new ArrayList<>(before.entries()); entries.add(new SupplierPaymentReturns.Entry(UUID.randomUUID(), new SupplierPaymentReturnPort.BankReceipt("return-2", before.request().command().debitAccount().reference(), money(amount), at))); return entries;
    }
    private static SupplierPaymentReturnPort.Receipt proof(SupplierPayableAdjustmentCommand command, List<SupplierPaymentReturns.Entry> entries, Instant at) {
        var original = SupplierPayableAdjustmentTest.evidence(command, at).bank();
        return new SupplierPaymentReturnPort.Receipt(original.request(), original.status(), 4, at, at.plusSeconds(300), original.current(), entries.stream().map(SupplierPaymentReturns.Entry::proof).toList());
    }
    private static SupplierPaymentReturn decision(SupplierPayableAdjustmentCommand command, SupplierPaymentReturnPort.Receipt proof, Instant at) {
        return new SupplierPaymentReturn(UUID.randomUUID(), command.tenantId(), UUID.randomUUID(), proof, "finance-2", at, "statement", "核对真实累计入款");
    }
}
