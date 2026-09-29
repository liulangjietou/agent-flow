package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceResult;
import io.agentflow.finance.PaymentAccountsPort;
import io.agentflow.finance.PaymentObservation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.agentflow.procurement.ProcurementPayableTest.money;
import static io.agentflow.procurement.SupplierPaymentTestData.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 供应商银行指令的原预留约束、三方分离和未知恢复，不以合成回执冒充真实银行联调。
 * @author owlzhangfq@gmail.com
 */
class SupplierPaymentOperationTest {
    private static final Instant REGISTERED_AT = AUTHORIZED_AT.plusSeconds(3);
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final PaymentAccountsPort.DebitAccount DEBIT = new PaymentAccountsPort.DebitAccount("debit-1", "法人基本户", "****5678", "CNY", "debit-v1");

    @Test void registrationPinsOriginalApprovalHoldCashierAndSelectedAccountWithoutChangingAmount() {
        var original = original(); var command = register(original, REGISTERED_AT);
        assertThat(command.id()).isEqualTo(original.command().id()); assertThat(command.amount()).isEqualTo(money("70"));
        assertThat(command.payee()).isEqualTo(original.command().authorization().payable().account());
        assertThat(command.digest()).matches("[a-f0-9]{64}").isEqualTo(register(original, REGISTERED_AT).digest());
        assertThat(new SupplierPaymentCommand(command.holdCommand(), command.held(), "cashier-2", DEBIT, REGISTERED_AT).digest()).isNotEqualTo(command.digest());
        var otherDebit = new PaymentAccountsPort.DebitAccount("debit-2", "法人基本户", "****5678", "CNY", "debit-v1");
        assertThat(new SupplierPaymentCommand(command.holdCommand(), command.held(), "cashier", otherDebit, REGISTERED_AT).digest()).isNotEqualTo(command.digest());
        assertThat(new SupplierPaymentCommand(command.holdCommand(), command.held(), "cashier", DEBIT, REGISTERED_AT.plusNanos(1)).digest()).isNotEqualTo(command.digest());
        assertThat(command.toString()).isEqualTo("SupplierPaymentCommand[id=" + command.id() + "]");
    }

    @Test void registrationRejectsUnconfirmedChangedOrStaleHoldsAndMismatchedCashierDirectories() {
        var original = original(); var verified = held(original.command(), REGISTERED_AT);
        var queued = SupplierPayableHoldOperation.queue(original.command(), AUTHORIZED_AT);
        assertThatThrownBy(() -> SupplierPaymentCommand.register(queued, verified, directory(original.command(), "cashier", REGISTERED_AT), "debit-1", "cashier", REGISTERED_AT)).isInstanceOf(DomainException.class);
        var changed = new SupplierPayableHoldObservation(verified.authorizationId(), verified.commandDigest(), verified.status(), 2L, REGISTERED_AT,
                "other-hold", verified.ledgerVersion(), verified.heldAmount(), verified.accountDigest(), verified.heldAt(), null);
        assertThatThrownBy(() -> SupplierPaymentCommand.register(original, changed, directory(original.command(), "cashier", REGISTERED_AT), "debit-1", "cashier", REGISTERED_AT)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> SupplierPaymentCommand.register(original, verified, directory(original.command(), "cashier-2", REGISTERED_AT), "debit-1", "cashier", REGISTERED_AT)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> SupplierPaymentCommand.register(original, verified, directory(original.command(), "cashier", REGISTERED_AT), "missing", "cashier", REGISTERED_AT)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> SupplierPaymentCommand.register(original, verified, directory(original.command(), "cashier", REGISTERED_AT.plusSeconds(300)), "debit-1", "cashier", REGISTERED_AT.plusSeconds(300))).isInstanceOf(DomainException.class);
    }

    @Test void applicantAndFinancialAuthorizerCannotBecomeTheCashierAndExpiryIsExclusive() {
        var command = payment();
        for (String cashier : List.of("alice", "finance", " ", "cashier\n")) {
            assertThatThrownBy(() -> new SupplierPaymentCommand(command.holdCommand(), command.held(), cashier, DEBIT, REGISTERED_AT)).isInstanceOf(DomainException.class);
        }
        var deadline = command.holdCommand().authorization().expiresAt(); command.requireSendAt(deadline.minusNanos(1));
        assertThatThrownBy(() -> command.requireSendAt(deadline)).isInstanceOf(DomainException.class);
        var expired = SupplierPaymentOperation.queue(command, REGISTERED_AT).claim(deadline, LEASE);
        assertThat(expired.status()).isEqualTo(SupplierPaymentOperation.Status.EXPIRED); assertThat(expired.dispatches()).isZero();
    }

    @Test void persistentHoldCanBeQueriedAndUsedAfterOriginalPayableReadExpiresWithinAuthorization() {
        var original = original(); var later = AUTHORIZED_AT.plusSeconds(3600);
        var command = register(original, later);
        assertThat(command.held().heldAt()).isEqualTo(AUTHORIZED_AT.plusSeconds(1));
        var checking = SupplierPaymentOperation.queue(command, later).claim(later, LEASE);
        var sending = checking.readyToSend(evidence(command, later.plusSeconds(1)), later.plusSeconds(1));
        sending.requireSendAt(later.plusSeconds(2)); assertThat(sending.dispatches()).isEqualTo(1);
    }

    @Test void evidenceRejectsChangedPayableAccountSelectedDebitVersionAndOriginalHoldIdentity() {
        var command = payment(); var now = REGISTERED_AT.plusSeconds(1); var payable = current(command.holdCommand().authorization().source(), "30", now);
        var oldAccount = payable.account(); var changedAccount = new SupplierAccountSnapshot(oldAccount.legalEntityId(), oldAccount.supplierReference(), "other-account", "****4321", "b".repeat(64), "v2");
        var changed = new ProcurementPayablePort.Payable(payable.request(), payable.sourceVersion(), payable.observedAt(), payable.validUntil(), payable.supplierName(), changedAccount,
                payable.contractReference(), payable.orderReference(), payable.matchingReference(), payable.accrualVoucherReference(), payable.budgetRecognitionReference(), payable.dueOn(), payable.gross(), payable.settled(), payable.lines());
        assertThatThrownBy(() -> SupplierPaymentEvidence.checked(command, directory(command.holdCommand(), "cashier", now), changed, held(command.holdCommand(), now), now)).isInstanceOf(DomainException.class);
        var anotherVersion = new PaymentAccountsPort.DebitAccount(DEBIT.reference(), DEBIT.displayName(), DEBIT.maskedAccount(), DEBIT.currency(), "v2");
        var directory = new PaymentAccountsPort.Directory(new PaymentAccountsPort.Request(command.payee().legalEntityId(), "CNY", "cashier"), "directory-v2", now, now.plusSeconds(600), List.of(anotherVersion));
        assertThatThrownBy(() -> SupplierPaymentEvidence.checked(command, directory, payable, held(command.holdCommand(), now), now)).isInstanceOf(DomainException.class);
        var other = new SupplierPayableHoldObservation(UUID.randomUUID(), command.holdCommand().digest(), SupplierPayableHoldObservation.Status.HELD, 1L, now,
                "hold-1", "ledger-1", command.amount(), command.payee().accountDigest(), AUTHORIZED_AT.plusSeconds(1), null);
        assertThatThrownBy(() -> SupplierPaymentEvidence.checked(command, directory(command.holdCommand(), "cashier", now), payable, other, now)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> SupplierPaymentEvidence.checked(command, directory(command.holdCommand(), "cashier", now), current(command.holdCommand().authorization().source(), "30.01", now), held(command.holdCommand(), now), now)).isInstanceOf(DomainException.class);
    }

    @Test void freshestResponseDoesNotExtendOlderEvidenceWindowAndSendRechecksExclusiveDeadline() {
        var command = payment(); var checkedAt = REGISTERED_AT.plusSeconds(290);
        var directory = directory(command.holdCommand(), "cashier", REGISTERED_AT); var payable = current(command.holdCommand().authorization().source(), "30", checkedAt);
        var proof = SupplierPaymentEvidence.checked(command, directory, payable, held(command.holdCommand(), checkedAt), checkedAt);
        assertThat(proof.validUntil()).isEqualTo(REGISTERED_AT.plusSeconds(300));
        var sending = SupplierPaymentOperation.queue(command, REGISTERED_AT).claim(checkedAt, LEASE).readyToSend(proof, checkedAt);
        sending.requireSendAt(proof.validUntil().minusNanos(1));
        assertThatThrownBy(() -> sending.requireSendAt(proof.validUntil())).isInstanceOf(DomainException.class);
    }

    @Test void readFailureAndReadLeaseExpiryRecheckOriginalChoiceWithoutAWrite() {
        var command = payment(); var checking = SupplierPaymentOperation.queue(command, REGISTERED_AT).claim(REGISTERED_AT, LEASE);
        var unread = checking.unavailableBeforeSend(SupplierPaymentOperation.Failure.CONNECTION, REGISTERED_AT.plusSeconds(1));
        assertThat(unread.status()).isEqualTo(SupplierPaymentOperation.Status.QUEUED); assertThat(unread.dispatches()).isZero();
        var next = unread.claim(unread.nextAttemptAt(), LEASE);
        assertThatThrownBy(() -> next.readyToSend(evidence(command, REGISTERED_AT), next.updatedAt())).isInstanceOf(DomainException.class);
        assertThat(checking.expireLease(checking.leaseUntil()).status()).isEqualTo(SupplierPaymentOperation.Status.QUEUED);
        assertThatThrownBy(() -> unread.requestQuery(unread.updatedAt())).isInstanceOf(DomainException.class);
        assertThat(checking.voidBeforeSend(SupplierPaymentOperation.Failure.SOURCE_CHANGED, checking.updatedAt()).dispatches()).isZero();
    }

    @Test void sendTimeoutAndLateCompletionOnlyQueryOriginalAfterAuthorizationExpires() {
        var sending = sending(payment()); var unknown = sending.unavailable(SupplierPaymentOperation.Failure.TIMEOUT, sending.updatedAt().plusSeconds(1));
        var expired = sending.command().holdCommand().authorization().expiresAt().plusSeconds(1);
        var query = unknown.claim(expired, LEASE); assertThat(query.status()).isEqualTo(SupplierPaymentOperation.Status.QUERYING);
        assertThat(query.command()).isSameAs(sending.command()); assertThat(query.dispatches()).isEqualTo(1);
        assertThatThrownBy(() -> query.requireSendAt(expired)).isInstanceOf(DomainException.class);
        var success = query.complete(new FinanceResult.Success<>(paid(query.command(), 1, expired)), expired);
        assertThat(success.settleable()).isTrue();
        var late = sending.complete(new FinanceResult.Success<>(paid(sending.command(), 1, sending.leaseUntil())), sending.leaseUntil());
        assertThat(late.status()).isEqualTo(SupplierPaymentOperation.Status.UNKNOWN); assertThat(late.failure()).isEqualTo(SupplierPaymentOperation.Failure.LEASE_EXPIRED);
        assertThat(late.observation()).isNull();
    }

    @Test void notFoundRequiresExplicitRetryWithSameIdentityAndFreshPreflight() {
        var sending = sending(payment()); var invalid = sending.complete(new FinanceResult.Success<>(absent(sending.command(), sending.updatedAt())), sending.updatedAt());
        assertThat(invalid.failure()).isEqualTo(SupplierPaymentOperation.Failure.INVALID_RESPONSE);
        var query = invalid.claim(invalid.nextAttemptAt(), LEASE); var notFound = query.complete(new FinanceResult.Success<>(absent(query.command(), query.updatedAt())), query.updatedAt());
        assertThat(notFound.status()).isEqualTo(SupplierPaymentOperation.Status.NOT_FOUND); assertThat(notFound.nextAttemptAt()).isNull();
        assertThatThrownBy(() -> notFound.claim(notFound.updatedAt(), LEASE)).isInstanceOf(DomainException.class);
        var checking = notFound.retryNotFound(notFound.updatedAt()).claim(notFound.updatedAt(), LEASE);
        assertThat(checking.status()).isEqualTo(SupplierPaymentOperation.Status.CHECKING); assertThat(checking.evidence()).isNull();
        var resent = checking.readyToSend(evidence(checking.command(), checking.updatedAt()), checking.updatedAt());
        assertThat(resent.dispatches()).isEqualTo(2); assertThat(resent.command().digest()).isEqualTo(sending.command().digest());
        assertThatThrownBy(() -> notFound.retryNotFound(sending.command().holdCommand().authorization().expiresAt())).isInstanceOf(DomainException.class);
    }

    @Test void pendingCannotBecomeNotFoundOrPermitReplacementAndDisputeRemainsSticky() {
        var sending = sending(payment()); var pending = new PaymentObservation(sending.command().id(), sending.command().digest(), PaymentObservation.Status.PENDING,
                1L, sending.updatedAt(), "bank-1", null, null, null, null, null);
        var waiting = sending.complete(new FinanceResult.Success<>(pending), sending.updatedAt()); assertThat(waiting.settleable()).isFalse();
        var query = waiting.claim(waiting.nextAttemptAt(), LEASE); var dispute = query.complete(new FinanceResult.Success<>(absent(query.command(), query.updatedAt())), query.updatedAt());
        assertThat(dispute.status()).isEqualTo(SupplierPaymentOperation.Status.RECONCILING); assertThat(dispute.observation()).isEqualTo(pending); assertThat(dispute.highestRevision()).isEqualTo(1);
        assertThatThrownBy(() -> dispute.retryNotFound(dispute.updatedAt())).isInstanceOf(DomainException.class);
        var again = dispute.requestQuery(dispute.updatedAt()).claim(dispute.updatedAt(), LEASE);
        assertThat(again.complete(new FinanceResult.Success<>(paid(again.command(), 2, again.updatedAt())), again.updatedAt()).status()).isEqualTo(SupplierPaymentOperation.Status.RECONCILING);
    }

    @Test void changedBankReceiptPreservesOriginalSuccessAndOrdinaryQueryCannotClearConflict() {
        var sending = sending(payment()); var original = paid(sending.command(), 1, sending.updatedAt());
        var paid = sending.complete(new FinanceResult.Success<>(original), sending.updatedAt());
        var query = paid.requestQuery(paid.updatedAt().plusSeconds(1)).claim(paid.updatedAt().plusSeconds(1), LEASE);
        var changed = new PaymentObservation(original.authorizationId(), original.commandDigest(), original.status(), 2L, query.updatedAt(), original.paymentReference(),
                original.paidAmount(), original.accountDigest(), original.completedAt(), "other-receipt", null);
        var disputed = query.complete(new FinanceResult.Success<>(changed), query.updatedAt());
        assertThat(disputed.status()).isEqualTo(SupplierPaymentOperation.Status.RECONCILING); assertThat(disputed.observation()).isEqualTo(original); assertThat(disputed.settleable()).isFalse();
        var again = disputed.requestQuery(disputed.updatedAt()).claim(disputed.updatedAt(), LEASE);
        assertThat(again.complete(new FinanceResult.Success<>(paid(again.command(), 3, again.updatedAt())), again.updatedAt()).status()).isEqualTo(SupplierPaymentOperation.Status.RECONCILING);
    }

    @Test void reversedBankPaymentIsSeparateFromPaidAndDoesNotChangeTheOriginalPayableHold() {
        var command = payment(); var sending = sending(command); var original = paid(command, 1, sending.updatedAt());
        var paid = sending.complete(new FinanceResult.Success<>(original), sending.updatedAt()); var at = paid.updatedAt().plusSeconds(2);
        var query = paid.requestQuery(at).claim(at, LEASE);
        var returned = new PaymentObservation(command.id(), command.digest(), PaymentObservation.Status.REVERSED, 2L, at, original.paymentReference(), command.amount(), command.payee().accountDigest(), at, "return-1", null);
        var result = query.complete(new FinanceResult.Success<>(returned), at);
        assertThat(result.status()).isEqualTo(SupplierPaymentOperation.Status.REVERSED); assertThat(result.settleable()).isFalse();
        assertThat(result.command().held()).isEqualTo(command.held()); assertThat(result.command().held().status()).isEqualTo(SupplierPayableHoldObservation.Status.HELD);
    }

    @Test void mismatchedBankAmountAccountAndPreRegistrationCompletionNeverConfirmPayment() {
        var command = payment(); var now = REGISTERED_AT.plusSeconds(1); var correct = paid(command, 1, now);
        for (var value : List.of(
                new PaymentObservation(command.id(), command.digest(), correct.status(), 1L, now, "bank-1", money("69.99"), correct.accountDigest(), now, "receipt-1", null),
                new PaymentObservation(command.id(), command.digest(), correct.status(), 1L, now, "bank-1", correct.paidAmount(), "b".repeat(64), now, "receipt-1", null),
                new PaymentObservation(command.id(), command.digest(), correct.status(), 1L, now, "bank-1", correct.paidAmount(), correct.accountDigest(), REGISTERED_AT.minusNanos(1), "receipt-1", null))) {
            assertThat(command.matches(value, true, now)).isFalse();
            assertThat(sending(command).complete(new FinanceResult.Success<>(value), now).status()).isEqualTo(SupplierPaymentOperation.Status.UNKNOWN);
        }
    }

    @Test void restoredStateCannotInventSendingWithoutEvidenceOrBankSuccessWithoutDispatch() {
        var command = payment();
        assertThatThrownBy(() -> new SupplierPaymentOperation(command, 3, SupplierPaymentOperation.Status.SENDING, 1, 1,
                REGISTERED_AT, REGISTERED_AT, null, REGISTERED_AT.plusSeconds(30), null, null, null, 0, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new SupplierPaymentOperation(command, 4, SupplierPaymentOperation.Status.SUCCEEDED, 1, 0,
                REGISTERED_AT, REGISTERED_AT, null, null, null, paid(command, 1, REGISTERED_AT), null, 1, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new SupplierPaymentOperation(command, 2, SupplierPaymentOperation.Status.QUEUED, 0, 0,
                REGISTERED_AT, REGISTERED_AT, REGISTERED_AT, null, null, absent(command, REGISTERED_AT), null, 0, null)).isInstanceOf(DomainException.class);
    }

    private static SupplierPaymentCommand payment() { return register(original(), REGISTERED_AT); }
    private static SupplierPaymentCommand register(SupplierPayableHoldOperation original, Instant now) {
        return SupplierPaymentCommand.register(original, held(original.command(), now), directory(original.command(), "cashier", now), "debit-1", "cashier", now);
    }
    private static SupplierPayableHoldOperation original() {
        var command = new SupplierPayableHoldCommand(authorization()); var at = AUTHORIZED_AT.plusSeconds(1);
        return SupplierPayableHoldOperation.queue(command, AUTHORIZED_AT).claim(AUTHORIZED_AT, LEASE).complete(new FinanceResult.Success<>(held(command, at)), at);
    }
    private static SupplierPayableHoldObservation held(SupplierPayableHoldCommand command, Instant now) {
        return new SupplierPayableHoldObservation(command.id(), command.digest(), SupplierPayableHoldObservation.Status.HELD, 1L, now,
                "hold-1", "ledger-1", command.authorization().source().amount(), command.authorization().payable().account().accountDigest(), AUTHORIZED_AT.plusSeconds(1), null);
    }
    private static PaymentAccountsPort.Directory directory(SupplierPayableHoldCommand command, String cashier, Instant now) {
        return new PaymentAccountsPort.Directory(new PaymentAccountsPort.Request(command.authorization().payable().request().legalEntityId(), "CNY", cashier), "directory-v1", now, now.plusSeconds(600), List.of(DEBIT));
    }
    private static SupplierPaymentEvidence evidence(SupplierPaymentCommand command, Instant now) {
        return SupplierPaymentEvidence.checked(command, directory(command.holdCommand(), "cashier", now), current(command.holdCommand().authorization().source(), "30", now), held(command.holdCommand(), now), now);
    }
    private static SupplierPaymentOperation sending(SupplierPaymentCommand command) {
        return SupplierPaymentOperation.queue(command, REGISTERED_AT).claim(REGISTERED_AT, LEASE).readyToSend(evidence(command, REGISTERED_AT.plusSeconds(1)), REGISTERED_AT.plusSeconds(1));
    }
    private static PaymentObservation paid(SupplierPaymentCommand command, long version, Instant now) {
        return new PaymentObservation(command.id(), command.digest(), PaymentObservation.Status.SUCCEEDED, version, now, "bank-1", command.amount(), command.payee().accountDigest(), command.registeredAt(), "receipt-1", null);
    }
    private static PaymentObservation absent(SupplierPaymentCommand command, Instant now) {
        return new PaymentObservation(command.id(), command.digest(), PaymentObservation.Status.NOT_FOUND, 0L, now, null, null, null, null, null, null);
    }
}
