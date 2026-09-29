package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.AccountingPeriodPort;
import io.agentflow.finance.FinanceResult;
import io.agentflow.finance.Money;
import io.agentflow.finance.PaymentAccountsPort;
import io.agentflow.finance.PaymentObservation;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.agentflow.procurement.ProcurementPayableTest.money;
import static io.agentflow.procurement.SupplierPaymentTestData.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 原应付结算必须绑定已到账银行事实，未知恢复不重复核销，也不再次付款。
 * @author owlzhangfq@gmail.com
 */
class SupplierPayableSettlementTest {
    private static final UUID SETTLEMENT_ID = UUID.fromString("c9ff0280-e9ee-4d90-8fdf-1a77bb87b1c7");
    private static final Instant REGISTERED = AUTHORIZED_AT.plusSeconds(10);
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final PaymentAccountsPort.DebitAccount DEBIT = new PaymentAccountsPort.DebitAccount("debit-1", "基本户", "****4567", "CNY", "v1");

    @Test void registrationPinsActualBankRevisionReceiptPeriodAndIndependentFinance() {
        var bank = bank(); var command = command(bank, REGISTERED);
        assertThat(command.id()).isEqualTo(SETTLEMENT_ID); assertThat(command.payment().id()).isEqualTo(bank.command().id()); assertThat(command.registeredFrom(bank)).isTrue();
        assertThat(command.paymentVersion()).isEqualTo(bank.version());
        assertThat(command.digest()).isEqualTo(command(bank, REGISTERED).digest()).matches("[a-f0-9]{64}");
        assertThat(new SupplierPayableSettlementCommand(command.id(), command.payment(), command.paymentVersion(), command.paid(), command.period(), "finance-2", REGISTERED).digest()).isNotEqualTo(command.digest());
        var separate = new SupplierPayableSettlementCommand(UUID.randomUUID(), command.payment(), command.paymentVersion(), command.paid(), command.period(), command.financeActor(), REGISTERED);
        assertThat(separate.payment().id()).isEqualTo(command.payment().id()); assertThat(separate.digest()).isNotEqualTo(command.digest());
        assertThat(command.toString()).doesNotContain("receipt-1", "hold-1", "alice", "finance");
        for (String actor : List.of("alice", "cashier", " ", "finance\n")) {
            assertThatThrownBy(() -> SupplierPayableSettlementCommand.register(SETTLEMENT_ID, bank, verified(bank, REGISTERED), period(bank.command(), REGISTERED), actor, REGISTERED)).isInstanceOf(DomainException.class);
        }
    }

    @Test void newSettlementRequiresActualUndisputedPaidStateAndMatchingFreshBankObservation() {
        var bank = bank(); var original = command(bank, REGISTERED); var query = bank.requestQuery(REGISTERED).claim(REGISTERED, LEASE);
        assertThatThrownBy(() -> command(query, REGISTERED)).isInstanceOf(DomainException.class);
        var returned = new PaymentObservation(bank.command().id(), bank.command().digest(), PaymentObservation.Status.REVERSED, 2L, REGISTERED,
                "bank-1", bank.command().amount(), bank.command().payee().accountDigest(), REGISTERED, "return-1", null);
        var reversed = query.complete(new FinanceResult.Success<>(returned), REGISTERED);
        assertThatThrownBy(() -> command(reversed, REGISTERED)).isInstanceOf(DomainException.class);
        assertThat(original.matchesCurrentPayment(reversed, verified(bank, REGISTERED), REGISTERED)).isFalse();
        var changed = new PaymentObservation(original.payment().id(), bank.command().digest(), PaymentObservation.Status.SUCCEEDED, 2L, REGISTERED,
                "bank-1", bank.command().amount(), bank.command().payee().accountDigest(), bank.observation().completedAt(), "changed-receipt", null);
        assertThatThrownBy(() -> SupplierPayableSettlementCommand.register(SETTLEMENT_ID, bank, changed, period(bank.command(), REGISTERED), "finance", REGISTERED)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> SupplierPayableSettlementCommand.register(SETTLEMENT_ID, bank, bank.observation(), period(bank.command(), REGISTERED.plusSeconds(300)), "finance", REGISTERED.plusSeconds(300))).isInstanceOf(DomainException.class);
    }

    @Test void accountingDateUsesOriginalLegalEntityTimeZoneAndCannotPrecedeBankCompletion() {
        var bank = bank(); var at = REGISTERED; var period = period(bank.command(), at); var request = period.request();
        var wrongDate = new AccountingPeriodPort.Request(request.legalEntityId(), "CNY", request.accountingDate().minusDays(1));
        var wrongPeriod = new AccountingPeriodPort.OpenPeriod(wrongDate, "period-1", "v1", wrongDate.accountingDate(), wrongDate.accountingDate(), at, at.plusSeconds(300));
        assertThatThrownBy(() -> SupplierPayableSettlementCommand.register(SETTLEMENT_ID, bank, verified(bank, at), wrongPeriod, "finance", at)).isInstanceOf(DomainException.class);
        var wrongEntity = new AccountingPeriodPort.OpenPeriod(new AccountingPeriodPort.Request(UUID.randomUUID(), "CNY", request.accountingDate()), "period-1", "v1", period.startsOn(), period.endsOn(), at, at.plusSeconds(300));
        assertThatThrownBy(() -> SupplierPayableSettlementCommand.register(SETTLEMENT_ID, bank, verified(bank, at), wrongEntity, "finance", at)).isInstanceOf(DomainException.class);
    }

    @Test void alreadyPaidSettlementCanBeRegisteredAfterOriginalPaymentAuthorizationExpires() {
        var bank = bank(); var after = bank.command().holdCommand().authorization().expiresAt().plusSeconds(1); var command = command(bank, after);
        var sending = SupplierPayableSettlementOperation.queue(command, after).claim(after, LEASE).readyToSend(evidence(command, after), after);
        sending.requireSendAt(after); assertThat(sending.dispatches()).isEqualTo(1);
    }

    @Test void freshPreflightCannotChangeOriginalHoldBankReceiptOrAccountingDate() {
        var command = command(bank(), REGISTERED); var at = REGISTERED.plusSeconds(1); var correct = evidence(command, at);
        assertThat(correct.matches(command, at)).isTrue();
        var hold = correct.hold(); var other = new SupplierPayableHoldObservation(hold.authorizationId(), hold.commandDigest(), hold.status(), hold.revision(), at,
                "other-hold", hold.ledgerVersion(), hold.heldAmount(), hold.accountDigest(), hold.heldAt(), null);
        assertThatThrownBy(() -> SupplierPayableSettlementEvidence.checked(command, other, correct.paid(), correct.period(), at)).isInstanceOf(DomainException.class);
        var old = correct.period(); var changed = new AccountingPeriodPort.OpenPeriod(old.request(), "other-period", "v2", old.startsOn(), old.endsOn(), at, old.validUntil());
        assertThatThrownBy(() -> SupplierPayableSettlementEvidence.checked(command, hold, correct.paid(), changed, at)).isInstanceOf(DomainException.class);
        var revised = new AccountingPeriodPort.OpenPeriod(old.request(), old.periodReference(), "v2", old.startsOn(), old.endsOn(), at, old.validUntil());
        assertThat(SupplierPayableSettlementEvidence.checked(command, hold, correct.paid(), revised, at).matches(command, at)).isTrue();
    }

    @Test void oldestEvidenceLimitsSendingAndReadTimeoutDoesNotDispatch() {
        var command = command(bank(), REGISTERED); var at = REGISTERED.plusSeconds(290); var old = evidence(command, REGISTERED);
        var proof = SupplierPayableSettlementEvidence.checked(command, old.hold(), paid(command.payment(), at), period(command.payment(), at), at);
        assertThat(proof.validUntil()).isEqualTo(REGISTERED.plusSeconds(300));
        var checking = SupplierPayableSettlementOperation.queue(command, REGISTERED).claim(at, LEASE);
        var sending = checking.readyToSend(proof, at); sending.requireSendAt(proof.validUntil().minusNanos(1));
        assertThatThrownBy(() -> sending.requireSendAt(proof.validUntil())).isInstanceOf(DomainException.class);
        var retry = checking.unavailableBeforeSend(SupplierPayableSettlementOperation.Failure.CONNECTION, at.plusSeconds(1));
        assertThat(retry.status()).isEqualTo(SupplierPayableSettlementOperation.Status.QUEUED); assertThat(retry.dispatches()).isZero();
        assertThat(checking.expireLease(checking.leaseUntil()).status()).isEqualTo(SupplierPayableSettlementOperation.Status.QUEUED);
        assertThatThrownBy(() -> retry.requestQuery(retry.updatedAt())).isInstanceOf(DomainException.class);
    }

    @Test void lostWriteResponseAndLateCompletionCanOnlyQueryOriginalSettlement() {
        var sending = sending(); var unknown = sending.unavailable(SupplierPayableSettlementOperation.Failure.TIMEOUT, REGISTERED.plusSeconds(1));
        var next = REGISTERED.plusSeconds(172800); var query = unknown.claim(next, LEASE);
        assertThat(query.status()).isEqualTo(SupplierPayableSettlementOperation.Status.QUERYING); assertThat(query.dispatches()).isEqualTo(1);
        assertThatThrownBy(() -> query.requireSendAt(next)).isInstanceOf(DomainException.class);
        var restored = query.complete(new FinanceResult.Success<>(settled(query.command(), 1, next, REGISTERED)), next);
        assertThat(restored.settled()).isTrue(); assertThat(restored.command()).isSameAs(sending.command());
        var late = sending.complete(new FinanceResult.Success<>(settled(sending.command(), 1, sending.leaseUntil(), REGISTERED)), sending.leaseUntil());
        assertThat(late.status()).isEqualTo(SupplierPayableSettlementOperation.Status.UNKNOWN); assertThat(late.observation()).isNull();
    }

    @Test void onlyAuthoritativeQueryNotFoundPermitsExplicitOriginalCommandRetry() {
        var sending = sending(); var refused = sending.complete(new FinanceResult.Success<>(absent(sending.command(), REGISTERED)), REGISTERED);
        assertThat(refused.failure()).isEqualTo(SupplierPayableSettlementOperation.Failure.INVALID_RESPONSE);
        var query = refused.claim(refused.nextAttemptAt(), LEASE); var missing = query.complete(new FinanceResult.Success<>(absent(query.command(), query.updatedAt())), query.updatedAt());
        assertThatThrownBy(() -> missing.claim(missing.updatedAt(), LEASE)).isInstanceOf(DomainException.class);
        var retry = missing.retryNotFound(missing.updatedAt()).claim(missing.updatedAt(), LEASE);
        assertThat(retry.command()).isSameAs(sending.command()); assertThat(retry.evidence()).isNull();
        assertThat(retry.readyToSend(evidence(retry.command(), retry.updatedAt()), retry.updatedAt()).dispatches()).isEqualTo(2);
    }

    @Test void pendingThenNotFoundFreezesOriginalFactAndLaterSuccessDoesNotEraseConflict() {
        var sending = sending(); var pending = new SupplierPayableSettlementObservation(sending.command().id(), sending.command().digest(), SupplierPayableSettlementObservation.Status.PENDING, 1L, REGISTERED, null, null);
        var waiting = sending.complete(new FinanceResult.Success<>(pending), REGISTERED); var query = waiting.claim(waiting.nextAttemptAt(), LEASE);
        var disputed = query.complete(new FinanceResult.Success<>(absent(query.command(), query.updatedAt())), query.updatedAt());
        assertThat(disputed.status()).isEqualTo(SupplierPayableSettlementOperation.Status.RECONCILING); assertThat(disputed.observation()).isEqualTo(pending);
        assertThatThrownBy(() -> disputed.retryNotFound(disputed.updatedAt())).isInstanceOf(DomainException.class);
        var again = disputed.requestQuery(disputed.updatedAt()).claim(disputed.updatedAt(), LEASE);
        assertThat(again.complete(new FinanceResult.Success<>(settled(again.command(), 2, again.updatedAt(), REGISTERED)), again.updatedAt()).settled()).isFalse();
    }

    @Test void settledPostingCannotChangeVoucherOrBalancesAndStaleRevisionRetainsHighestFact() {
        var sending = sending(); var first = settled(sending.command(), 2, REGISTERED, REGISTERED);
        var done = sending.complete(new FinanceResult.Success<>(first), REGISTERED); var at = REGISTERED.plusSeconds(1);
        var query = done.requestQuery(at).claim(at, LEASE); var p = first.posting();
        var changed = new SupplierPayableSettlementObservation(first.operationId(), first.commandDigest(), first.status(), 3L, at,
                new SupplierPayableSettlementObservation.Posting(p.settlementReference(), p.holdReference(), p.ledgerVersion(), p.settledAmount(), p.settledBefore(), p.settledAfter(), p.bankPaymentReference(), p.bankReceiptReference(), "other-voucher", p.periodReference(), p.accountingDate(), p.settledAt()), null);
        assertThat(query.complete(new FinanceResult.Success<>(changed), at).status()).isEqualTo(SupplierPayableSettlementOperation.Status.RECONCILING);
        var older = query.complete(new FinanceResult.Success<>(settled(sending.command(), 1, at, REGISTERED)), at);
        assertThat(older.highestRevision()).isEqualTo(2); assertThat(older.observation()).isEqualTo(first); assertThat(older.failure()).isEqualTo(SupplierPayableSettlementOperation.Failure.STALE_OBSERVATION);
    }

    @Test void exactBalanceIncrementOriginalHoldBankReceiptAndAccountingDateAreRequired() {
        var command = sending().command(); var correct = settled(command, 1, REGISTERED, REGISTERED); var p = correct.posting();
        for (var changed : List.of(
                posting(command, "other-hold", "30", "bank-1", "receipt-1", p.accountingDate()),
                posting(command, p.holdReference(), "30.01", "bank-1", "receipt-1", p.accountingDate()),
                posting(command, p.holdReference(), "30", "other-bank", "receipt-1", p.accountingDate()),
                posting(command, p.holdReference(), "30", "bank-1", "other-receipt", p.accountingDate()),
                posting(command, p.holdReference(), "30", "bank-1", "receipt-1", p.accountingDate().plusDays(1)))) {
            var receipt = new SupplierPayableSettlementObservation(command.id(), command.digest(), correct.status(), 1L, REGISTERED, changed, null);
            assertThat(command.matches(receipt, true, REGISTERED)).isFalse();
        }
        assertThatThrownBy(() -> new SupplierPayableSettlementObservation.Posting("settlement-1", "hold-1", "v2", money("70"), money("30"), money("99.99"), "bank-1", "receipt-1", "voucher-1", "period-1", p.accountingDate(), REGISTERED)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new SupplierPayableSettlementObservation(command.id(), command.digest(), correct.status(), 1L, REGISTERED, null, null)).isInstanceOf(DomainException.class);
    }

    @Test void genericRejectionDoesNotProveNoSettlementAndConfirmedRejectionCannotBeAutomaticallyRetried() {
        var sending = sending(); var unknown = sending.complete(new FinanceResult.Rejected<>(FinanceResult.Reason.ACCOUNTING_PERIOD_CLOSED), REGISTERED);
        assertThat(unknown.status()).isEqualTo(SupplierPayableSettlementOperation.Status.UNKNOWN);
        var rejection = new SupplierPayableSettlementObservation(sending.command().id(), sending.command().digest(), SupplierPayableSettlementObservation.Status.REJECTED, 1L, REGISTERED, null, SupplierPayableSettlementObservation.Rejection.ACCOUNTING_PERIOD_CLOSED);
        var rejected = sending.complete(new FinanceResult.Success<>(rejection), REGISTERED);
        assertThat(rejected.status()).isEqualTo(SupplierPayableSettlementOperation.Status.REJECTED);
        assertThatThrownBy(() -> rejected.retryNotFound(REGISTERED)).isInstanceOf(DomainException.class);
        assertThat(rejected.command().payment().held().status()).isEqualTo(SupplierPayableHoldObservation.Status.HELD);
    }

    @Test void restoredStateCannotInventSuccessWithoutDispatchOrSendingWithoutFreshEvidence() {
        var command = command(bank(), REGISTERED);
        assertThatThrownBy(() -> new SupplierPayableSettlementOperation(command, 4, SupplierPayableSettlementOperation.Status.SETTLED, 1, 0,
                REGISTERED, REGISTERED, null, null, null, settled(command, 1, REGISTERED, REGISTERED), null, 1, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new SupplierPayableSettlementOperation(command, 3, SupplierPayableSettlementOperation.Status.SETTLING, 1, 1,
                REGISTERED, REGISTERED, null, REGISTERED.plusSeconds(30), null, null, null, 0, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new SupplierPayableSettlementOperation(command, 2, SupplierPayableSettlementOperation.Status.QUEUED, 0, 0,
                REGISTERED, REGISTERED, REGISTERED, null, null, absent(command, REGISTERED), null, 0, null)).isInstanceOf(DomainException.class);
    }

    private static SupplierPayableSettlementCommand command(SupplierPaymentOperation bank, Instant now) { return SupplierPayableSettlementCommand.register(SETTLEMENT_ID, bank, verified(bank, now), period(bank.command(), now), "finance", now); }
    static SupplierPayableSettlementOperation sending() { var command = command(bank(), REGISTERED); return SupplierPayableSettlementOperation.queue(command, REGISTERED).claim(REGISTERED, LEASE).readyToSend(evidence(command, REGISTERED), REGISTERED); }
    private static SupplierPayableSettlementEvidence evidence(SupplierPayableSettlementCommand command, Instant now) { return SupplierPayableSettlementEvidence.checked(command, held(command.payment().holdCommand(), now), paid(command.payment(), now), period(command.payment(), now), now); }
    private static PaymentObservation verified(SupplierPaymentOperation bank, Instant now) { return paid(bank.command(), now); }
    private static PaymentObservation paid(SupplierPaymentCommand payment, Instant now) { return new PaymentObservation(payment.id(), payment.digest(), PaymentObservation.Status.SUCCEEDED, 1L, now, "bank-1", payment.amount(), payment.payee().accountDigest(), AUTHORIZED_AT.plusSeconds(6), "receipt-1", null); }
    private static AccountingPeriodPort.OpenPeriod period(SupplierPaymentCommand payment, Instant now) {
        var day = now.atZone(ZoneId.of(payment.holdCommand().authorization().source().reservation().source().round().legalEntity().timeZone())).toLocalDate();
        return new AccountingPeriodPort.OpenPeriod(new AccountingPeriodPort.Request(payment.payee().legalEntityId(), "CNY", day), "period-1", "v1", day.minusDays(30), day.plusDays(30), now, now.plusSeconds(600));
    }
    static SupplierPayableSettlementObservation settled(SupplierPayableSettlementCommand command, long revision, Instant now, Instant settledAt) {
        var p = posting(command, "hold-1", "30", "bank-1", "receipt-1", command.period().request().accountingDate());
        var posting = new SupplierPayableSettlementObservation.Posting(p.settlementReference(), p.holdReference(), p.ledgerVersion(), p.settledAmount(), p.settledBefore(), p.settledAfter(), p.bankPaymentReference(), p.bankReceiptReference(), p.voucherReference(), p.periodReference(), p.accountingDate(), settledAt);
        return new SupplierPayableSettlementObservation(command.id(), command.digest(), SupplierPayableSettlementObservation.Status.SETTLED, revision, now, posting, null);
    }
    private static SupplierPayableSettlementObservation.Posting posting(SupplierPayableSettlementCommand command, String hold, String before, String bank, String receipt, LocalDate day) {
        Money amount = command.payment().amount(); return new SupplierPayableSettlementObservation.Posting("settlement-1", hold, "ledger-2", amount, money(before), money(before).plus(amount), bank, receipt, "voucher-1", "period-1", day, REGISTERED);
    }
    private static SupplierPayableSettlementObservation absent(SupplierPayableSettlementCommand command, Instant now) { return new SupplierPayableSettlementObservation(command.id(), command.digest(), SupplierPayableSettlementObservation.Status.NOT_FOUND, 0L, now, null, null); }
    private static SupplierPaymentOperation bank() {
        var holdCommand = new SupplierPayableHoldCommand(authorization()); var now = AUTHORIZED_AT.plusSeconds(5);
        var original = SupplierPayableHoldOperation.queue(holdCommand, AUTHORIZED_AT).claim(AUTHORIZED_AT, LEASE).complete(new FinanceResult.Success<>(held(holdCommand, now)), now);
        var directory = new PaymentAccountsPort.Directory(new PaymentAccountsPort.Request(holdCommand.authorization().payable().request().legalEntityId(), "CNY", "cashier"), "v1", now, now.plusSeconds(600), List.of(DEBIT));
        var payment = SupplierPaymentCommand.register(original, held(holdCommand, now), directory, DEBIT.reference(), "cashier", now);
        var proof = SupplierPaymentEvidence.checked(payment, directory, current(holdCommand.authorization().source(), "30", now), held(holdCommand, now), now);
        return SupplierPaymentOperation.queue(payment, now).claim(now, LEASE).readyToSend(proof, now).complete(new FinanceResult.Success<>(paid(payment, now.plusSeconds(1))), now.plusSeconds(1));
    }
    private static SupplierPayableHoldObservation held(SupplierPayableHoldCommand command, Instant now) { return new SupplierPayableHoldObservation(command.id(), command.digest(), SupplierPayableHoldObservation.Status.HELD, 1L, now, "hold-1", "ledger-1", command.authorization().source().amount(), command.authorization().payable().account().accountDigest(), AUTHORIZED_AT.plusSeconds(1), null); }
}
