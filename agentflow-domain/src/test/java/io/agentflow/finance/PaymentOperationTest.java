package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 付款检查、发送崩溃、原操作恢复和冲突保留的状态边界。
 * @author owlzhangfq@gmail.com
 */
class PaymentOperationTest {
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:02Z");
    private static final Duration LEASE = Duration.ofSeconds(20);
    private final PaymentAuthorization authorization = authorization();
    private final PaymentCommand command = authorization.execution().command();

    @Test void checkingCrashAndReadFailureCanRecheckWithoutClaimingBankWasCalled() {
        var checking = queue().claim(NOW, LEASE);
        assertThat(checking.status()).isEqualTo(PaymentOperation.Status.CHECKING); assertThat(checking.dispatches()).isZero();
        var retry = checking.unavailableBeforeSend(PaymentOperation.Failure.TIMEOUT, NOW.plusSeconds(1));
        assertThat(retry.status()).isEqualTo(PaymentOperation.Status.QUEUED); assertThat(retry.dispatches()).isZero();
        var reclaimed = retry.claim(retry.nextAttemptAt(), LEASE);
        var expired = reclaimed.expire(reclaimed.leaseUntil());
        assertThat(expired.status()).isEqualTo(PaymentOperation.Status.QUEUED); assertThat(expired.observation()).isNull();
        assertThat(expired.input()).isEqualTo(checking.input()); assertThat(expired.dispatches()).isZero();
        assertThatThrownBy(() -> checking.complete(new FinanceResult.Success<>(fact(PaymentObservation.Status.SUCCEEDED, 1, NOW)), NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> retry.requestQuery(retry.updatedAt())).isInstanceOf(DomainException.class);
    }

    @Test void sendingLeaseExpiryOnlyQueriesOriginalCommandEvenAfterAuthorizationExpired() {
        var sending = sending(); var unknown = sending.expire(sending.leaseUntil());
        assertThat(unknown.status()).isEqualTo(PaymentOperation.Status.UNKNOWN); assertThat(unknown.dispatches()).isEqualTo(1);
        var afterExpiry = command.authorization().expiresAt().plusSeconds(30);
        var query = unknown.claim(afterExpiry, LEASE);
        assertThat(query.status()).isEqualTo(PaymentOperation.Status.QUERYING); assertThat(query.input()).isEqualTo(sending.input());
        assertThatThrownBy(() -> query.requireSendAt(afterExpiry)).isInstanceOf(DomainException.class);
        var paid = query.complete(new FinanceResult.Success<>(fact(PaymentObservation.Status.SUCCEEDED, 1, afterExpiry)), afterExpiry);
        assertThat(paid.settleable()).isTrue(); assertThat(paid.dispatches()).isEqualTo(1);
        assertThatThrownBy(() -> paid.retryNotFound(afterExpiry)).isInstanceOf(DomainException.class);
    }

    @Test void freshAccountEvidenceMustKeepOriginalCashierDebitVersionAndFrozenPayee() {
        var checking = queue().claim(NOW, LEASE); var debit = authorization.execution().debitAccount();
        var otherCashier = new PaymentAccountsPort.Directory(new PaymentAccountsPort.Request(command.payee().legalEntityId(), "CNY", "other"), "v1", NOW, NOW.plusSeconds(60), List.of(debit));
        var changedDebit = new PaymentAccountsPort.Directory(directory(NOW).request(), "v2", NOW, NOW.plusSeconds(60),
                List.of(new PaymentAccountsPort.DebitAccount(debit.reference(), debit.displayName(), debit.maskedAccount(), "CNY", "v2")));
        var changedPayee = new EmployeeAccountPort.Account(new EmployeeAccountSnapshot(command.payee().legalEntityId(), "alice", "payee-2", "****5678", "b".repeat(64), "v2"), NOW.plusSeconds(60));
        for (var directory : List.of(otherCashier, changedDebit)) assertThatThrownBy(() -> checking.readyToSend(directory, account(NOW), NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> checking.readyToSend(directory(NOW), changedPayee, NOW)).isInstanceOf(DomainException.class);
        var sending = checking.readyToSend(directory(NOW), account(NOW), NOW);
        assertThatCode(() -> sending.requireSendAt(NOW)).doesNotThrowAnyException();
        assertThatThrownBy(() -> sending.requireSendAt(sending.accountEvidence().validUntil())).isInstanceOf(DomainException.class);
        assertThat(sending.accountEvidence().payee()).isEqualTo(command.payee()); assertThat(sending.accountEvidence().request().cashierId()).isEqualTo("cashier");
        assertThatThrownBy(() -> sending.voidBeforeSend(PaymentOperation.Failure.SOURCE_CHANGED, NOW)).isInstanceOf(DomainException.class);
    }

    @Test void authoritativeMissingNeedsExplicitResendAndAnotherAccountCheckWithSameCommand() {
        var missing = sending().unavailable(PaymentOperation.Failure.TIMEOUT, NOW).claim(NOW.plusSeconds(5), LEASE)
                .complete(new FinanceResult.Success<>(fact(PaymentObservation.Status.NOT_FOUND, 0, NOW.plusSeconds(5))), NOW.plusSeconds(5));
        assertThat(missing.status()).isEqualTo(PaymentOperation.Status.NOT_FOUND); assertThat(missing.nextAttemptAt()).isNull();
        assertThatThrownBy(() -> missing.claim(NOW.plusSeconds(6), LEASE)).isInstanceOf(DomainException.class);
        var retry = missing.retryNotFound(NOW.plusSeconds(6)); assertThat(retry.accountEvidence()).isNull();
        var checking = retry.claim(NOW.plusSeconds(6), LEASE); assertThat(checking.status()).isEqualTo(PaymentOperation.Status.CHECKING);
        var sending = checking.readyToSend(directory(NOW.plusSeconds(6)), account(NOW.plusSeconds(6)), NOW.plusSeconds(6));
        assertThat(sending.input().command()).isEqualTo(command); assertThat(sending.dispatches()).isEqualTo(2);
        assertThatThrownBy(() -> missing.retryNotFound(command.authorization().expiresAt())).isInstanceOf(DomainException.class);
        var directMissing = sending().complete(new FinanceResult.Success<>(fact(PaymentObservation.Status.NOT_FOUND, 0, NOW)), NOW);
        assertThat(directMissing.status()).isEqualTo(PaymentOperation.Status.UNKNOWN); assertThat(directMissing.failure()).isEqualTo(PaymentOperation.Failure.INVALID_RESPONSE);
    }

    @Test void partialWrongAccountAndWrongCommandCannotConfirmPayment() {
        var paid = fact(PaymentObservation.Status.SUCCEEDED, 1, NOW);
        for (var invalid : List.of(
                new PaymentObservation(command.id(), command.digest(), paid.status(), 1L, NOW, "bank-1", new Money(new BigDecimal("99"), "CNY"), paid.accountDigest(), NOW, "receipt-1", null),
                new PaymentObservation(command.id(), command.digest(), paid.status(), 1L, NOW, "bank-1", command.amount(), "b".repeat(64), NOW, "receipt-1", null),
                new PaymentObservation(UUID.randomUUID(), command.digest(), paid.status(), 1L, NOW, "bank-1", command.amount(), paid.accountDigest(), NOW, "receipt-1", null))) {
            var result = sending().complete(new FinanceResult.Success<>(invalid), NOW);
            assertThat(result.settleable()).isFalse(); assertThat(result.status()).isEqualTo(PaymentOperation.Status.UNKNOWN); assertThat(result.observation()).isNull();
        }
    }

    @Test void pendingAndTransportFailureOnlyScheduleReadQueries() {
        var pending = sending().complete(new FinanceResult.Success<>(fact(PaymentObservation.Status.PENDING, 1, NOW)), NOW);
        assertThat(pending.settleable()).isFalse(); assertThat(pending.status()).isEqualTo(PaymentOperation.Status.UNKNOWN);
        var queried = pending.claim(pending.nextAttemptAt(), LEASE).unavailable(PaymentOperation.Failure.CONNECTION, pending.nextAttemptAt());
        assertThat(queried.observation()).isEqualTo(pending.observation()); assertThat(queried.dispatches()).isEqualTo(1);
        assertThat(queried.claim(queried.nextAttemptAt(), LEASE).status()).isEqualTo(PaymentOperation.Status.QUERYING);
    }

    @Test void sameRevisionIdenticalFactIsIdempotentButCannotReplaceReceiptOrAmount() {
        var paid = paid(); var now = NOW.plusSeconds(2);
        var repeated = query(paid, now).complete(new FinanceResult.Success<>(fact(PaymentObservation.Status.SUCCEEDED, 2, NOW)), now);
        assertThat(repeated.status()).isEqualTo(PaymentOperation.Status.SUCCEEDED); assertThat(repeated.observation()).isEqualTo(paid.observation());
        var different = new PaymentObservation(command.id(), command.digest(), PaymentObservation.Status.SUCCEEDED, 2L, now, "bank-1", command.amount(), command.payee().accountDigest(), NOW, "another-receipt", null);
        var conflict = query(paid, now).complete(new FinanceResult.Success<>(different), now);
        assertThat(conflict.status()).isEqualTo(PaymentOperation.Status.RECONCILING); assertThat(conflict.settleable()).isFalse();
        assertThat(conflict.observation()).isEqualTo(paid.observation()); assertThat(conflict.conflictingObservation()).isEqualTo(different);
    }

    @Test void staleDowngradeAndMissingKeepPaidEvidenceAndConflictRemainsSticky() {
        var paid = paid(); var now = NOW.plusSeconds(2);
        for (var incoming : List.of(fact(PaymentObservation.Status.PENDING, 1, now), fact(PaymentObservation.Status.FAILED, 3, now), fact(PaymentObservation.Status.NOT_FOUND, 0, now))) {
            var conflict = query(paid, now).complete(new FinanceResult.Success<>(incoming), now);
            assertThat(conflict.status()).isEqualTo(PaymentOperation.Status.RECONCILING); assertThat(conflict.observation()).isEqualTo(paid.observation());
            assertThat(conflict.highestRevision()).isEqualTo(Math.max(2, incoming.revision()));
            var repeated = query(conflict, now.plusSeconds(1)).complete(new FinanceResult.Success<>(fact(PaymentObservation.Status.SUCCEEDED, 4, now.plusSeconds(1))), now.plusSeconds(1));
            assertThat(repeated.status()).isEqualTo(PaymentOperation.Status.RECONCILING); assertThat(repeated.observation()).isEqualTo(paid.observation());
            assertThatThrownBy(() -> repeated.retryNotFound(repeated.updatedAt())).isInstanceOf(DomainException.class);
        }
    }

    @Test void reversalHasItsOwnReceiptAndTimeAndNeverBecomesAnotherSuccessfulSettlement() {
        var now = NOW.plusSeconds(2); var reversedFact = fact(PaymentObservation.Status.REVERSED, 3, now);
        var reversed = query(paid(), now).complete(new FinanceResult.Success<>(reversedFact), now);
        assertThat(reversed.status()).isEqualTo(PaymentOperation.Status.REVERSED); assertThat(reversed.settleable()).isFalse();
        assertThat(reversed.observation().receiptReference()).isEqualTo("reversal-receipt");
        var contradicted = query(reversed, now.plusSeconds(1)).complete(new FinanceResult.Success<>(fact(PaymentObservation.Status.SUCCEEDED, 4, now.plusSeconds(1))), now.plusSeconds(1));
        assertThat(contradicted.status()).isEqualTo(PaymentOperation.Status.RECONCILING); assertThat(contradicted.observation()).isEqualTo(reversedFact);
    }

    @Test void expirationAndSourceChangeBeforeSendingCannotCreatePaymentFacts() {
        var expired = queue().claim(command.authorization().expiresAt(), LEASE);
        assertThat(expired.status()).isEqualTo(PaymentOperation.Status.EXPIRED); assertThat(expired.dispatches()).isZero(); assertThat(expired.observation()).isNull();
        var voided = queue().claim(NOW, LEASE).voidBeforeSend(PaymentOperation.Failure.SOURCE_CHANGED, NOW);
        assertThat(voided.status()).isEqualTo(PaymentOperation.Status.VOIDED); assertThat(voided.dispatches()).isZero();
        assertThatThrownBy(() -> voided.claim(NOW, LEASE)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> voided.requestQuery(NOW)).isInstanceOf(DomainException.class);
        assertThat(sending().complete(new FinanceResult.Success<>(fact(PaymentObservation.Status.SUCCEEDED, 1, NOW.plusSeconds(20))), NOW.plusSeconds(20)).status()).isEqualTo(PaymentOperation.Status.UNKNOWN);
    }

    @Test void authorizationExpiringDuringAccountReadStopsBeforeSendingWithoutBankUncertainty() {
        var until = command.authorization().expiresAt(); var checking = queue().claim(until.minusSeconds(1), LEASE);
        var result = checking.readyToSend(directory(until), account(until), until);
        assertThat(result.status()).isEqualTo(PaymentOperation.Status.EXPIRED); assertThat(result.dispatches()).isZero();
        assertThat(result.failure()).isEqualTo(PaymentOperation.Failure.AUTHORIZATION_EXPIRED); assertThat(result.observation()).isNull();
    }

    @Test void restoredSendingAndRequeuedSnapshotsNeedOriginalAccountCheckAndAuthoritativeAbsence() {
        var sending = sending();
        assertThatThrownBy(() -> new PaymentOperation(sending.input(), sending.version(), sending.status(), sending.attempts(), 1, NOW, NOW, null, sending.leaseUntil(), null, null, null, 0, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new PaymentOperation(sending.input(), sending.version(), PaymentOperation.Status.QUEUED, sending.attempts(), 1, NOW, NOW, NOW, null, null, null, null, 0, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new PaymentOperation(sending.input(), sending.version(), PaymentOperation.Status.SUCCEEDED, sending.attempts(), 0, NOW, NOW, null, null, null, fact(PaymentObservation.Status.SUCCEEDED, 1, NOW), null, 1, null)).isInstanceOf(DomainException.class);
    }

    @Test void retirementStopsOnlyNeverDispatchedWorkAndPreservesFailedBankEvidence() {
        var checking = queue().claim(NOW, LEASE);
        for (var original : List.of(queue(), checking, queue().claim(command.authorization().expiresAt(), LEASE))) {
            var stopped = original.stopForRetirement(original.updatedAt());
            assertThat(stopped.retirementBasis()).isEqualTo(PaymentOperation.RetirementBasis.NEVER_DISPATCHED);
            assertThat(stopped.nextAttemptAt()).isNull(); assertThat(stopped.leaseUntil()).isNull();
            var retired = authorization.retire(stopped, "finance", "未发送，结束原执行", stopped.updatedAt());
            assertThat(retired.status()).isEqualTo(PaymentAuthorization.Status.RETIRED);
            assertThat(retired.execution()).isEqualTo(authorization.execution()); assertThat(retired.matchesRetirement(stopped)).isTrue();
        }
        var failed = sending().complete(new FinanceResult.Success<>(fact(PaymentObservation.Status.FAILED, 2, NOW)), NOW);
        assertThat(failed.stopForRetirement(NOW)).isSameAs(failed);
        assertThat(authorization.retire(failed, "finance", "银行已确认未付", NOW).retirement().basis()).isEqualTo(PaymentOperation.RetirementBasis.CONFIRMED_FAILED);
        assertThat(authorization.canRetire(failed, "cashier")).isFalse(); assertThat(authorization.canRetire(failed, "alice")).isFalse();
        assertThatThrownBy(() -> authorization.retire(checking, "finance", "未停止队列", NOW)).isInstanceOf(DomainException.class);
    }

    @Test void noRetirementForUnknownAbsenceReturnOrConflictingFactsEvenAfterResendStops() {
        var at = NOW.plusSeconds(1);
        var unknown = sending().unavailable(PaymentOperation.Failure.TIMEOUT, at);
        var missing = query(unknown, at).complete(new FinanceResult.Success<>(fact(PaymentObservation.Status.NOT_FOUND, 0, at)), at);
        var resending = missing.retryNotFound(at);
        var reversed = query(paid(), at).complete(new FinanceResult.Success<>(fact(PaymentObservation.Status.REVERSED, 3, at)), at);
        var conflict = query(paid(), at).complete(new FinanceResult.Success<>(fact(PaymentObservation.Status.FAILED, 3, at)), at);
        for (var unsafe : List.of(sending(), unknown, missing, resending, resending.claim(at, LEASE),
                resending.voidBeforeSend(PaymentOperation.Failure.ACCOUNT_CHANGED, at), resending.claim(command.authorization().expiresAt(), LEASE), paid(), reversed, conflict)) {
            assertThat(unsafe.retirementBasis()).isNull(); assertThat(authorization.canRetire(unsafe, "finance")).isFalse();
            assertThatThrownBy(() -> unsafe.stopForRetirement(unsafe.updatedAt())).isInstanceOf(DomainException.class);
        }
    }

    @Test void restoredRetirementMustKeepOriginalExecutionAndExactProofRevision() {
        var stopped = queue().stopForRetirement(NOW); var retired = authorization.retire(stopped, "finance", "停止未发送原件", NOW);
        assertThat(retired.matchesRetirement(queue())).isFalse(); assertThat(retired.canRetire(stopped, "finance")).isFalse();
        assertThatThrownBy(() -> new PaymentAuthorization(retired.terms(), retired.decision(), 3, PaymentAuthorization.Status.RETIRED, NOW, retired.execution(), null))
                .isInstanceOf(DomainException.class);
        var proof = retired.retirement();
        assertThatThrownBy(() -> new PaymentAuthorization(retired.terms(), retired.decision(), 3, PaymentAuthorization.Status.RETIRED, NOW, retired.execution(), null,
                new PaymentAuthorization.Retirement("cashier", proof.reason(), NOW, proof.operationVersion(), proof.basis()))).isInstanceOf(DomainException.class);
    }

    private PaymentOperation queue() { return PaymentOperation.queue(authorization, NOW); }
    private PaymentOperation sending() { return queue().claim(NOW, LEASE).readyToSend(directory(NOW), account(NOW), NOW); }
    private PaymentOperation paid() { return sending().complete(new FinanceResult.Success<>(fact(PaymentObservation.Status.SUCCEEDED, 2, NOW)), NOW); }
    private PaymentOperation query(PaymentOperation value, Instant now) { return value.requestQuery(now).claim(now, LEASE); }
    private PaymentAccountsPort.Directory directory(Instant now) { return directory(command.payee(), authorization.execution().debitAccount(), now); }
    private EmployeeAccountPort.Account account(Instant now) { return new EmployeeAccountPort.Account(command.payee(), now.plusSeconds(10)); }
    private PaymentObservation fact(PaymentObservation.Status status, long revision, Instant at) {
        boolean settled = status == PaymentObservation.Status.SUCCEEDED || status == PaymentObservation.Status.REVERSED;
        return new PaymentObservation(command.id(), command.digest(), status, revision, at, status == PaymentObservation.Status.NOT_FOUND ? null : "bank-1",
                settled ? command.amount() : null, settled ? command.payee().accountDigest() : null, settled ? status == PaymentObservation.Status.REVERSED ? at : NOW : null,
                settled ? status == PaymentObservation.Status.REVERSED ? "reversal-receipt" : "receipt-1" : null, status == PaymentObservation.Status.FAILED ? PaymentObservation.Failure.PAYMENT_REJECTED : null);
    }
    private static PaymentAuthorization authorization() {
        var command = VoucherCommandTest.advanceCommand(); var before = NOW.minusSeconds(1);
        var voucher = VoucherOperation.queue(new VoucherOperation.Input(command, "a".repeat(64)), NOW.minusSeconds(2)).claim(NOW.minusSeconds(2), LEASE)
                .complete(new FinanceResult.Success<>(new VoucherObservation(command.id(), command.digest(), VoucherObservation.Status.POSTED, 1L, before,
                        "posting-1", "voucher-1", command.period().periodReference(), command.accountingDate(), command.totals().gross(), command.totals().gross(), before, null)), before);
        var payee = new EmployeeAccountSnapshot(command.legalEntityId(), "alice", "payee-1", "****1234", "a".repeat(64), "v1");
        var debit = new PaymentAccountsPort.DebitAccount("debit-1", "业务账户", "****5678", "CNY", "v1");
        return PaymentAuthorization.issue(UUID.randomUUID(), voucher, payee, "finance", NOW, NOW.plusSeconds(3600))
                .registerExecution("cashier", directory(payee, debit, NOW), debit.reference(), new EmployeeAccountPort.Account(payee, NOW.plusSeconds(60)), voucher, NOW);
    }
    private static PaymentAccountsPort.Directory directory(EmployeeAccountSnapshot payee, PaymentAccountsPort.DebitAccount debit, Instant now) {
        return new PaymentAccountsPort.Directory(new PaymentAccountsPort.Request(payee.legalEntityId(), "CNY", "cashier"), "v1", now, now.plusSeconds(60), List.of(debit));
    }
}
