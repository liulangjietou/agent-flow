package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.AccountingPeriodPort;
import io.agentflow.finance.FinanceResult;
import io.agentflow.finance.PaymentObservation;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.agentflow.procurement.ProcurementPayableTest.money;
import static io.agentflow.procurement.SupplierPaymentTestData.AUTHORIZED_AT;
import static org.assertj.core.api.Assertions.*;

/**
 * 核销前后回款采用不同账务增量，未知恢复及累计追加不能重记原付款或既有入款。
 * @author owlzhangfq@gmail.com
 */
class SupplierPayableAdjustmentTest {
    private static final Instant NOW = AUTHORIZED_AT.plusSeconds(86460);
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final UUID ID = UUID.fromString("7ab470bc-48f9-4e14-afb8-2651f241690f");

    @Test void beforeAndAfterOriginalSettlementHaveDifferentAtomicEffects() {
        var held = command(false, "20"); var settled = command(true, "20");
        assertThat(held.source().recognizesOriginalPayment()).isTrue(); assertThat(settled.source().recognizesOriginalPayment()).isFalse();
        assertThat(held.matches(adjusted(held, 1, NOW, "30", "80"), false, NOW)).isTrue();
        assertThat(settled.matches(adjusted(settled, 1, NOW, "100", "80"), false, NOW)).isTrue();
        assertThat(held.matches(adjusted(held, 1, NOW, "100", "80"), false, NOW)).isFalse();
        assertThat(settled.matches(adjusted(settled, 1, NOW, "30", "80"), false, NOW)).isFalse();
        assertThat(held.source().returns().request().command().amount()).isEqualTo(money("70"));
        assertThat(settled.source().settlement().observation().posting().settledAmount()).isEqualTo(money("70"));
    }

    @Test void fullReturnPreservesOriginalPaymentAndRestoresOnlyTheReturnedAmount() {
        for (boolean hasSettlement : List.of(false, true)) {
            var command = command(hasSettlement, "70"); var evidence = evidence(command, NOW);
            assertThat(evidence.bank().current().status()).isEqualTo(PaymentObservation.Status.REVERSED);
            assertThat(command.source().netPaid()).isEqualTo(money("0"));
            assertThat(command.matches(adjusted(command, 1, NOW, hasSettlement ? "100" : "30", "30"), false, NOW)).isTrue();
            assertThat(command.source().returns().request().original().status()).isEqualTo(PaymentObservation.Status.SUCCEEDED);
        }
    }

    @Test void subsequentAdjustmentAccountsOnlyNewReceiptsAndKeepsTheirFirstRegistrationOwners() {
        for (boolean hasSettlement : List.of(false, true)) {
            var original = command(hasSettlement, "20"); var next = subsequent(original);
            assertThat(next.source().newReturned()).isEqualTo(money("10")); assertThat(next.source().netPaid()).isEqualTo(money("40"));
            assertThat(next.source().newReturns()).hasSize(1); assertThat(next.source().recognizesOriginalPayment()).isFalse();
            assertThat(next.source().returns().entries().get(0)).isEqualTo(original.source().returns().entries().get(0));
            assertThat(next.matches(adjusted(next, 1, next.registeredAt(), "80", "70"), false, next.registeredAt())).isTrue();
            assertThat(evidence(next, next.registeredAt()).matches(next, next.registeredAt())).isTrue();
            var existing = next.source().previous();
            assertThatThrownBy(() -> new SupplierPayableAdjustmentSource(original.source().returns(), original.source().settlement(), existing)).isInstanceOf(DomainException.class);
            var wrong = new SupplierPayableAdjustmentSource.Previous(UUID.randomUUID(), existing.paymentDigest(), existing.originalSettlementId(), existing.version(), existing.entries(), existing.observation());
            assertThatThrownBy(() -> new SupplierPayableAdjustmentSource(next.source().returns(), next.source().settlement(), wrong)).isInstanceOf(DomainException.class);
            var changedOwner = new SupplierPaymentReturns.Entry(UUID.randomUUID(), existing.entries().get(0).proof());
            var changed = new SupplierPaymentReturns(next.source().returns().request(), 4, List.of(changedOwner, next.source().newReturns().get(0)), true, NOW.minusSeconds(5), next.registeredAt());
            assertThatThrownBy(() -> new SupplierPayableAdjustmentSource(changed, next.source().settlement(), existing)).isInstanceOf(DomainException.class);
        }
    }

    @Test void restoredPreviousAdjustmentMustAccountForItsOwnRegisteredReceipts() {
        var command = command(false, "20"); var observed = adjusted(command, 1, NOW, "30", "80"); var p = observed.posting();
        var wrong = new SupplierPayableAdjustmentObservation.Posting(p.adjustmentReference(), p.holdReference(), p.ledgerVersion(), p.recognitionVoucherReference(), p.returnedAmount(), p.totalReturned(), p.netPaid(), p.payableSettledBefore(), p.payableSettledAfter(),
                List.of(new SupplierPayableAdjustmentObservation.ReturnEntry("other-bank", money("20"), "return-voucher", "entry-1")), p.periodReference(), p.accountingDate(), p.adjustedAt());
        var unrelated = new SupplierPayableAdjustmentObservation(ID, command.digest(), observed.status(), 1, NOW, wrong, null);
        var payment = command.source().returns().request().command();
        assertThatThrownBy(() -> new SupplierPayableAdjustmentSource.Previous(payment.id(), payment.digest(), null, 4, command.source().returns().entries(), unrelated)).isInstanceOf(DomainException.class);
    }

    @Test void authorizationUsesIndependentFinanceAndActualReturnDateAfterOriginalAuthorizationExpires() {
        var valid = command(true, "20"); assertThat(valid.registeredAt()).isAfter(valid.source().returns().request().command().holdCommand().authorization().expiresAt());
        for (String actor : List.of("alice", "cashier", " ", "finance\n")) assertThatThrownBy(() -> new SupplierPayableAdjustmentCommand(ID, valid.source(), valid.period(), actor, NOW)).isInstanceOf(DomainException.class);
        var old = valid.period(); var day = old.request().accountingDate().minusDays(1);
        var period = new AccountingPeriodPort.OpenPeriod(new AccountingPeriodPort.Request(old.request().legalEntityId(), "CNY", day), "period-1", "v1", day, day, NOW, NOW.plusSeconds(300));
        assertThatThrownBy(() -> new SupplierPayableAdjustmentCommand(ID, valid.source(), period, "finance", NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new SupplierPayableAdjustmentCommand(valid.source().returns().request().command().id(), valid.source(), valid.period(), "finance", NOW)).isInstanceOf(DomainException.class);
        assertThat(valid.digest()).isEqualTo(new SupplierPayableAdjustmentCommand(ID, valid.source(), valid.period(), "finance", NOW).digest());
        assertThat(new SupplierPayableAdjustmentCommand(ID, valid.source(), valid.period(), "finance-2", NOW).digest()).isNotEqualTo(valid.digest());
        assertThat(valid.toString()).doesNotContain("return-1", "voucher-1", "alice", "finance");
    }

    @Test void adjustmentDateCannotPrecedeTheOriginalSettlementAccountingDate() {
        var command = command(true, "20"); var source = command.source(); var original = source.settlement(); var c = original.command();
        var day = command.period().request().accountingDate().plusDays(1); var period = periodOn(c.period(), day);
        var changed = new SupplierPayableSettlementCommand(c.id(), c.payment(), c.paymentVersion(), c.paid(), period, c.financeActor(), c.registeredAt());
        var old = original.observation(); var p = old.posting();
        var posting = new SupplierPayableSettlementObservation.Posting(p.settlementReference(), p.holdReference(), p.ledgerVersion(), p.settledAmount(), p.settledBefore(), p.settledAfter(), p.bankPaymentReference(), p.bankReceiptReference(), p.voucherReference(), p.periodReference(), day, p.settledAt());
        var dated = new SupplierPayableAdjustmentSource.OriginalSettlement(original.version(), changed, new SupplierPayableSettlementObservation(changed.id(), changed.digest(), old.status(), old.revision(), old.observedAt(), posting, null));
        var input = new SupplierPayableAdjustmentSource(source.returns(), dated, null);
        assertThatThrownBy(() -> new SupplierPayableAdjustmentCommand(ID, input, command.period(), "finance", NOW)).isInstanceOf(DomainException.class);
    }

    @Test void cumulativeAdjustmentsCannotMoveAccountingBeforeTheirPreviousPosting() {
        var command = command(false, "20");
        var future = new SupplierPayableAdjustmentCommand(ID, command.source(), periodOn(command.period(), command.period().request().accountingDate().plusDays(1)), "finance", NOW);
        assertThatThrownBy(() -> subsequent(future)).isInstanceOf(DomainException.class);
    }

    @Test void originalPurchaseAccrualVoucherCannotBeReusedForPaymentOrReturns() {
        var command = command(false, "20"); var p = adjusted(command, 1, NOW, "30", "80").posting();
        var accrual = command.source().returns().request().command().holdCommand().authorization().payable().accrualVoucherReference();
        for (boolean recognition : List.of(true, false)) {
            var entries = recognition ? p.entries() : List.of(new SupplierPayableAdjustmentObservation.ReturnEntry("return-1", money("20"), accrual, "entry-return"));
            var reused = new SupplierPayableAdjustmentObservation.Posting(p.adjustmentReference(), p.holdReference(), p.ledgerVersion(), recognition ? accrual : p.recognitionVoucherReference(), p.returnedAmount(), p.totalReturned(), p.netPaid(), p.payableSettledBefore(), p.payableSettledAfter(), entries, p.periodReference(), p.accountingDate(), p.adjustedAt());
            assertThat(command.matches(new SupplierPayableAdjustmentObservation(ID, command.digest(), SupplierPayableAdjustmentObservation.Status.ADJUSTED, 1, NOW, reused, null), false, NOW)).isFalse();
        }
    }

    private static AccountingPeriodPort.OpenPeriod periodOn(AccountingPeriodPort.OpenPeriod original, java.time.LocalDate day) {
        return new AccountingPeriodPort.OpenPeriod(new AccountingPeriodPort.Request(original.request().legalEntityId(), original.request().currency(), day), original.periodReference(), original.sourceVersion(), original.startsOn(), original.endsOn(), original.observedAt(), original.validUntil());
    }

    @Test void preflightRejectsUnregisteredFundsDifferentOriginalPostingAndExpiredOldestEvidence() {
        var command = command(true, "20"); var correct = evidence(command, NOW);
        var bank = correct.bank(); var extra = new ArrayList<>(bank.returns()); extra.add(bankReceipt(bank.request(), "extra", "10", NOW));
        var unregistered = new SupplierPaymentReturnPort.Receipt(bank.request(), bank.status(), 3, NOW, NOW.plusSeconds(300), bank.current(), extra);
        assertThatThrownBy(() -> SupplierPayableAdjustmentEvidence.checked(command, unregistered, null, correct.settlement(), null, correct.period(), NOW)).isInstanceOf(DomainException.class);
        var original = correct.settlement(); var p = original.posting();
        var otherPosting = new SupplierPayableSettlementObservation.Posting(p.settlementReference(), p.holdReference(), p.ledgerVersion(), p.settledAmount(), p.settledBefore(), p.settledAfter(), p.bankPaymentReference(), p.bankReceiptReference(), "other-voucher", p.periodReference(), p.accountingDate(), p.settledAt());
        var other = new SupplierPayableSettlementObservation(original.operationId(), original.commandDigest(), original.status(), 2L, NOW, otherPosting, null);
        assertThatThrownBy(() -> SupplierPayableAdjustmentEvidence.checked(command, bank, null, other, null, correct.period(), NOW)).isInstanceOf(DomainException.class);
        var later = NOW.plusSeconds(290); var fresh = evidence(command, later);
        var oldest = SupplierPayableAdjustmentEvidence.checked(command, fresh.bank(), null, original, null, fresh.period(), later);
        assertThat(oldest.validUntil()).isEqualTo(NOW.plusSeconds(300));
        assertThat(oldest.matches(command, oldest.validUntil().minusNanos(1))).isTrue(); assertThat(oldest.matches(command, oldest.validUntil())).isFalse();
        assertThatThrownBy(() -> SupplierPayableAdjustmentEvidence.checked(command, bank, null, null, null, correct.period(), NOW)).isInstanceOf(DomainException.class);
    }

    @Test void adjustedPostingMustBindActualReceiptAmountsOriginalVoucherAndOpenPeriod() {
        var command = command(true, "20"); var observed = adjusted(command, 1, NOW, "100", "80"); var p = observed.posting();
        var different = new SupplierPayableAdjustmentObservation.Posting(p.adjustmentReference(), p.holdReference(), p.ledgerVersion(), "other-original", p.returnedAmount(), p.totalReturned(), p.netPaid(), p.payableSettledBefore(), p.payableSettledAfter(), p.entries(), p.periodReference(), p.accountingDate(), p.adjustedAt());
        assertThat(command.matches(new SupplierPayableAdjustmentObservation(ID, command.digest(), observed.status(), 1, NOW, different, null), false, NOW)).isFalse();
        var differentBank = new SupplierPayableAdjustmentObservation.Posting(p.adjustmentReference(), p.holdReference(), p.ledgerVersion(), p.recognitionVoucherReference(), p.returnedAmount(), p.totalReturned(), p.netPaid(), p.payableSettledBefore(), p.payableSettledAfter(), List.of(new SupplierPayableAdjustmentObservation.ReturnEntry("other-bank", money("20"), "return-voucher", "entry-1")), p.periodReference(), p.accountingDate(), p.adjustedAt());
        assertThat(command.matches(new SupplierPayableAdjustmentObservation(ID, command.digest(), observed.status(), 1, NOW, differentBank, null), false, NOW)).isFalse();
        assertThatThrownBy(() -> new SupplierPayableAdjustmentObservation.Posting(p.adjustmentReference(), p.holdReference(), p.ledgerVersion(), "voucher-1", p.returnedAmount(), p.totalReturned(), p.netPaid(), p.payableSettledBefore(), p.payableSettledAfter(), List.of(new SupplierPayableAdjustmentObservation.ReturnEntry("return-1", money("20"), "voucher-1", "entry-1")), p.periodReference(), p.accountingDate(), p.adjustedAt())).isInstanceOf(DomainException.class);
    }

    @Test void lostResponseRestoresOnlyOriginalAdjustmentEvenAfterPeriodAndAuthorizationExpire() {
        var command = command(true, "20"); var sending = sending(command);
        var unknown = sending.unavailable(SupplierPayableAdjustmentOperation.Failure.TIMEOUT, NOW.plusSeconds(1));
        var after = NOW.plusSeconds(172800); var query = unknown.claim(after, LEASE);
        assertThat(query.status()).isEqualTo(SupplierPayableAdjustmentOperation.Status.QUERYING); assertThat(query.dispatches()).isEqualTo(1);
        assertThatThrownBy(() -> query.requireSendAt(after)).isInstanceOf(DomainException.class);
        var posted = adjusted(command, 1, NOW, "100", "80"); var recovered = new SupplierPayableAdjustmentObservation(ID, command.digest(), posted.status(), 1, after, posted.posting(), null);
        assertThat(query.complete(new FinanceResult.Success<>(recovered), after).adjusted()).isTrue();
        var late = sending.complete(new FinanceResult.Success<>(recovered), after);
        assertThat(late.status()).isEqualTo(SupplierPayableAdjustmentOperation.Status.UNKNOWN); assertThat(late.observation()).isNull();
    }

    @Test void readFailuresAndLeaseExpiryNeverDispatchAndNotFoundRequiresExplicitRetry() {
        var command = command(false, "20"); var checking = SupplierPayableAdjustmentOperation.queue(command, NOW).claim(NOW, LEASE);
        assertThat(checking.expireLease(checking.leaseUntil()).status()).isEqualTo(SupplierPayableAdjustmentOperation.Status.QUEUED);
        assertThat(checking.unavailableBeforeSend(SupplierPayableAdjustmentOperation.Failure.CONNECTION, NOW).dispatches()).isZero();
        var absent = new SupplierPayableAdjustmentObservation(ID, command.digest(), SupplierPayableAdjustmentObservation.Status.NOT_FOUND, 0, NOW, null, null);
        var unknown = sending(command).complete(new FinanceResult.Success<>(absent), NOW);
        assertThat(unknown.failure()).isEqualTo(SupplierPayableAdjustmentOperation.Failure.INVALID_RESPONSE);
        var at = unknown.nextAttemptAt(); var query = unknown.claim(at, LEASE);
        var missing = query.complete(new FinanceResult.Success<>(new SupplierPayableAdjustmentObservation(ID, command.digest(), absent.status(), 0, at, null, null)), at);
        assertThat(missing.retirementBasis()).isNull(); assertThatThrownBy(() -> missing.claim(at, LEASE)).isInstanceOf(DomainException.class);
        var retry = missing.retryNotFound(at).claim(at, LEASE).readyToSend(evidence(command, at), at);
        assertThat(retry.command()).isSameAs(command); assertThat(retry.dispatches()).isEqualTo(2);
    }

    @Test void changingConfirmedPostingCannotEraseOriginalAccountingOrBeSafelyRetired() {
        var command = command(true, "20"); var observed = adjusted(command, 1, NOW, "100", "80");
        var done = sending(command).complete(new FinanceResult.Success<>(observed), NOW);
        var p = observed.posting(); var other = new SupplierPayableAdjustmentObservation.Posting("other-adjustment", p.holdReference(), p.ledgerVersion(), p.recognitionVoucherReference(), p.returnedAmount(), p.totalReturned(), p.netPaid(), p.payableSettledBefore(), p.payableSettledAfter(), p.entries(), p.periodReference(), p.accountingDate(), p.adjustedAt());
        var incoming = new SupplierPayableAdjustmentObservation(ID, command.digest(), observed.status(), 2, NOW, other, null);
        var conflict = done.requestQuery(NOW).claim(NOW, LEASE).complete(new FinanceResult.Success<>(incoming), NOW);
        assertThat(conflict.status()).isEqualTo(SupplierPayableAdjustmentOperation.Status.RECONCILING); assertThat(conflict.observation()).isEqualTo(observed);
        assertThat(conflict.retirementBasis()).isNull(); assertThatThrownBy(() -> conflict.retryNotFound(NOW)).isInstanceOf(DomainException.class);
        var rejected = new SupplierPayableAdjustmentObservation(ID, command.digest(), SupplierPayableAdjustmentObservation.Status.REJECTED, 3, NOW, null, SupplierPayableAdjustmentObservation.Rejection.ALREADY_ADJUSTED);
        assertThat(sending(command).complete(new FinanceResult.Success<>(rejected), NOW).retirementBasis()).isNull();
    }

    static SupplierPayableAdjustmentCommand command(boolean settled, String amount) {
        var bank = SupplierPayableSettlementTest.bank(); var request = new SupplierPaymentReturnPort.Request(bank.command(), bank.observation());
        var entries = List.of(new SupplierPaymentReturns.Entry(UUID.fromString("09f2d87e-ee54-4ca5-a824-be5f28d9b4e5"), bankReceipt(request, "return-1", amount, NOW.minusSeconds(1))));
        var ledger = new SupplierPaymentReturns(request, 3, entries, true, NOW.minusSeconds(5), NOW);
        var original = SupplierPayableSettlementTest.command(bank, AUTHORIZED_AT.plusSeconds(10));
        var fact = SupplierPayableSettlementTest.settled(original, 1, original.registeredAt(), original.registeredAt()); var p = fact.posting();
        var posting = new SupplierPayableSettlementObservation.Posting(p.settlementReference(), p.holdReference(), p.ledgerVersion(), p.settledAmount(), p.settledBefore(), p.settledAfter(), p.bankPaymentReference(), p.bankReceiptReference(), "payment-voucher-1", p.periodReference(), p.accountingDate(), p.settledAt());
        var reference = settled ? new SupplierPayableAdjustmentSource.OriginalSettlement(4, original, new SupplierPayableSettlementObservation(fact.operationId(), fact.commandDigest(), fact.status(), fact.revision(), fact.observedAt(), posting, null)) : null;
        return new SupplierPayableAdjustmentCommand(ID, new SupplierPayableAdjustmentSource(ledger, reference, null), period(bank.command(), NOW), "finance", NOW);
    }
    private static SupplierPayableAdjustmentCommand subsequent(SupplierPayableAdjustmentCommand original) {
        var source = original.source(); var observed = adjusted(original, 1, NOW, source.recognizesOriginalPayment() ? "30" : "100", "80");
        var previous = new SupplierPayableAdjustmentSource.Previous(source.returns().request().command().id(), source.returns().request().command().digest(), source.settlement() == null ? null : source.settlement().command().id(), 4, source.returns().entries(), observed);
        var entries = new ArrayList<>(source.returns().entries()); entries.add(new SupplierPaymentReturns.Entry(UUID.randomUUID(), bankReceipt(source.returns().request(), "return-2", "10", NOW.plusSeconds(20))));
        var at = NOW.plusSeconds(30); var ledger = new SupplierPaymentReturns(source.returns().request(), 4, entries, true, source.returns().createdAt(), at, new SupplierPaymentReturns.Accounting(original.id(), previous.version(), previous.entries().size(), observed.observedAt()));
        return new SupplierPayableAdjustmentCommand(UUID.randomUUID(), new SupplierPayableAdjustmentSource(ledger, source.settlement(), previous), period(ledger.request().command(), at), "finance-2", at);
    }
    static SupplierPayableAdjustmentEvidence evidence(SupplierPayableAdjustmentCommand command, Instant at) {
        var source = command.source(); var request = source.returns().request(); var original = request.original(); var full = source.netPaid().value().signum() == 0;
        var bank = new PaymentObservation(request.command().id(), request.command().digest(), full ? PaymentObservation.Status.REVERSED : PaymentObservation.Status.SUCCEEDED, full ? 2L : 1L, at,
                original.paymentReference(), original.paidAmount(), original.accountDigest(), full ? at : original.completedAt(), original.receiptReference(), null);
        var returned = new SupplierPaymentReturnPort.Receipt(request, full ? SupplierPaymentReturnPort.Status.RETURNED : SupplierPaymentReturnPort.Status.PARTIALLY_RETURNED, 3, at, at.plusSeconds(300), bank, source.returns().entries().stream().map(SupplierPaymentReturns.Entry::proof).toList());
        var h = request.command().held();
        var hold = source.recognizesOriginalPayment() ? new SupplierPayableHoldObservation(h.authorizationId(), h.commandDigest(), h.status(), h.revision(), at, h.holdReference(), h.ledgerVersion(), h.heldAmount(), h.accountDigest(), h.heldAt(), null) : null;
        var s = source.settlement() == null ? null : source.settlement().observation();
        var settled = s == null ? null : new SupplierPayableSettlementObservation(s.operationId(), s.commandDigest(), s.status(), s.revision(), at, s.posting(), null);
        var p = source.previous() == null ? null : source.previous().observation();
        var previous = p == null ? null : new SupplierPayableAdjustmentObservation(p.operationId(), p.commandDigest(), p.status(), p.revision(), at, p.posting(), null);
        return SupplierPayableAdjustmentEvidence.checked(command, returned, hold, settled, previous, period(request.command(), at), at);
    }
    static SupplierPayableAdjustmentObservation adjusted(SupplierPayableAdjustmentCommand command, long revision, Instant at, String before, String after) {
        var source = command.source(); var recognition = source.previous() != null ? source.previous().observation().posting().recognitionVoucherReference()
                : source.settlement() == null ? "recognition-1" : source.settlement().observation().posting().voucherReference();
        var entries = source.newReturns().stream().map(entry -> new SupplierPayableAdjustmentObservation.ReturnEntry(entry.proof().transactionReference(), entry.proof().amount(), "return-voucher-" + command.id(), "entry-" + entry.proof().transactionReference())).toList();
        var posting = new SupplierPayableAdjustmentObservation.Posting("adjustment-" + command.id(), source.returns().request().command().held().holdReference(), "ledger-3", recognition,
                source.newReturned(), source.returns().totalReturned(), source.netPaid(), money(before), money(after), entries, command.period().periodReference(), command.period().request().accountingDate(), at);
        return new SupplierPayableAdjustmentObservation(command.id(), command.digest(), SupplierPayableAdjustmentObservation.Status.ADJUSTED, revision, at, posting, null);
    }
    private static SupplierPayableAdjustmentOperation sending(SupplierPayableAdjustmentCommand command) { return SupplierPayableAdjustmentOperation.queue(command, NOW).claim(NOW, LEASE).readyToSend(evidence(command, NOW), NOW); }
    private static SupplierPaymentReturnPort.BankReceipt bankReceipt(SupplierPaymentReturnPort.Request request, String reference, String amount, Instant at) { return new SupplierPaymentReturnPort.BankReceipt(reference, request.command().debitAccount().reference(), money(amount), at); }
    private static AccountingPeriodPort.OpenPeriod period(SupplierPaymentCommand payment, Instant at) {
        var date = at.atZone(java.time.ZoneId.of(payment.holdCommand().authorization().source().reservation().source().round().legalEntity().timeZone())).toLocalDate();
        return new AccountingPeriodPort.OpenPeriod(new AccountingPeriodPort.Request(payment.payee().legalEntityId(), "CNY", date), "period-1", "v1", date.minusDays(30), date.plusDays(30), at, at.plusSeconds(600));
    }
}
