package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.agentflow.procurement.ProcurementPayableTest.*;
import static io.agentflow.procurement.SupplierPaymentTestData.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 原应付预留按精确授权幂等执行，超时恢复、窗口边界及冲突不会制造可重复支付余额。
 * @author owlzhangfq@gmail.com
 */
class SupplierPayableHoldTest {
    private static final Duration LEASE = Duration.ofSeconds(30);

    @Test void commandDigestPinsTheAuthorizationCurrentBalanceOriginalApprovalAndDestination() {
        var authorization = authorization(); var command = new SupplierPayableHoldCommand(authorization);
        assertThat(command.digest()).matches("[a-f0-9]{64}").isEqualTo(new SupplierPayableHoldCommand(authorization).digest());
        var alternate = List.of(
                new SupplierPaymentAuthorization(UUID.randomUUID(), authorization.source(), authorization.payable(), "finance", AUTHORIZED_AT, authorization.expiresAt()),
                new SupplierPaymentAuthorization(AUTHORIZATION_ID, authorization.source(), current(authorization.source(), "20", AUTHORIZED_AT), "finance", AUTHORIZED_AT, authorization.expiresAt()),
                new SupplierPaymentAuthorization(AUTHORIZATION_ID, authorization.source(), authorization.payable(), "finance-2", AUTHORIZED_AT, authorization.expiresAt()),
                new SupplierPaymentAuthorization(AUTHORIZATION_ID, authorization.source(), authorization.payable(), "finance", AUTHORIZED_AT, authorization.expiresAt().minusSeconds(1)));
        for (var changed : alternate) assertThat(new SupplierPayableHoldCommand(changed).digest()).isNotEqualTo(command.digest());
        var source = authorization.source(); var reservation = source.reservation(); var old = reservation.source(); var round = old.round();
        var changedRound = new ProcurementPaymentRound(round.roundNo(), round.submittedRequestVersion(), round.submittedBy(), round.submittedAt(), round.content(), round.legalEntity(), round.catalogVersion(), "b".repeat(64), round.payable());
        var changedSource = new ApprovedProcurementPayment(new ProcurementPayableReservation(reservation.id(), new ProcurementPayableReservation.Source(old.tenantId(), old.requestId(), old.applicationId(), old.employeeId(), old.requestVersion(), changedRound), 1, reservation.heldAt(), null, null), source.approval(), source.approvedRequestVersion());
        var changedTarget = new SupplierPayableHoldCommand(new SupplierPaymentAuthorization(AUTHORIZATION_ID, changedSource, authorization.payable(), "finance", AUTHORIZED_AT, authorization.expiresAt()));
        assertThat(changedTarget.digest()).isNotEqualTo(command.digest()); assertThat(changedTarget.targetDigest()).isEqualTo("b".repeat(64));
        assertThat(command.toString()).isEqualTo("SupplierPayableHoldCommand[id=" + AUTHORIZATION_ID + "]");
    }

    @Test void sendDeadlineHonorsBothExternalEvidenceExpiryAndShorterAuthorization() {
        var auth = authorization(); var command = new SupplierPayableHoldCommand(auth);
        assertThat(command.sendDeadline()).isEqualTo(AUTHORIZED_AT.plusSeconds(300));
        var shortAuth = new SupplierPayableHoldCommand(new SupplierPaymentAuthorization(AUTHORIZATION_ID, auth.source(), auth.payable(), "finance", AUTHORIZED_AT, AUTHORIZED_AT.plusSeconds(10)));
        assertThat(shortAuth.sendDeadline()).isEqualTo(AUTHORIZED_AT.plusSeconds(10));
        var old = auth.payable(); var shortPayable = new ProcurementPayablePort.Payable(old.request(), old.sourceVersion(), old.observedAt(), AUTHORIZED_AT.plusSeconds(20), old.supplierName(), old.account(), old.contractReference(), old.orderReference(), old.matchingReference(), old.accrualVoucherReference(), old.budgetRecognitionReference(), old.dueOn(), old.gross(), old.settled(), old.lines());
        var shortEvidence = new SupplierPayableHoldCommand(new SupplierPaymentAuthorization(AUTHORIZATION_ID, auth.source(), shortPayable, "finance", AUTHORIZED_AT, auth.expiresAt()));
        assertThat(shortEvidence.sendDeadline()).isEqualTo(AUTHORIZED_AT.plusSeconds(20));
        shortEvidence.requireSendAt(shortEvidence.sendDeadline().minusNanos(1));
        fails("PROCUREMENT_PAYABLE_CHANGED", () -> shortEvidence.requireSendAt(shortEvidence.sendDeadline()));
        var expired = SupplierPayableHoldOperation.queue(command, AUTHORIZED_AT).claim(command.sendDeadline(), LEASE);
        assertThat(expired.status()).isEqualTo(SupplierPayableHoldOperation.Status.EXPIRED); assertThat(expired.dispatches()).isZero();
    }

    @Test void exactPositiveHoldCanBeRecoveredAfterAuthorizationExpiryButNotCreatedThen() {
        var command = new SupplierPayableHoldCommand(authorization()); var late = command.authorization().expiresAt().plusSeconds(20);
        assertThat(held(command, 1, late).matches(command, true, late)).isTrue();
        var changed = new SupplierPayableHoldObservation(command.id(), command.digest(), SupplierPayableHoldObservation.Status.HELD, 1L, late, "hold-1", "ledger-3", money("70"), account().accountDigest(), command.sendDeadline(), null);
        assertThat(changed.matches(command, true, late)).isFalse();
        assertThat(held(command, 1, AUTHORIZED_AT).matches(command, true, AUTHORIZED_AT.minusNanos(1))).isFalse();
        var different = new SupplierPayableHoldObservation(UUID.randomUUID(), command.digest(), SupplierPayableHoldObservation.Status.HELD, 1L, AUTHORIZED_AT, "hold-1", "ledger-3", money("70"), account().accountDigest(), AUTHORIZED_AT, null);
        assertThat(different.matches(command, true, AUTHORIZED_AT)).isFalse();
        for (String amount : List.of("69.99", "70.01")) {
            var partial = new SupplierPayableHoldObservation(command.id(), command.digest(), SupplierPayableHoldObservation.Status.HELD, 1L, AUTHORIZED_AT, "hold-1", "ledger-3", money(amount), account().accountDigest(), AUTHORIZED_AT, null);
            assertThat(partial.matches(command, true, AUTHORIZED_AT)).isFalse();
        }
        var wrongAccount = new SupplierPayableHoldObservation(command.id(), command.digest(), SupplierPayableHoldObservation.Status.HELD, 1L, AUTHORIZED_AT, "hold-1", "ledger-3", money("70"), "b".repeat(64), AUTHORIZED_AT, null);
        assertThat(wrongAccount.matches(command, true, AUTHORIZED_AT)).isFalse();
    }

    @Test void timeoutAndExpiredLeaseResumeAsQueryWithTheSameCommand() {
        var sending = queued().claim(AUTHORIZED_AT, LEASE);
        sending.requireSendAt(AUTHORIZED_AT.plusSeconds(1));
        var unknown = sending.complete(new FinanceResult.Unavailable<>(FinanceResult.Failure.TIMEOUT), AUTHORIZED_AT.plusSeconds(1));
        assertThat(unknown.status()).isEqualTo(SupplierPayableHoldOperation.Status.UNKNOWN);
        var querying = unknown.claim(sending.command().authorization().expiresAt().plusSeconds(1), LEASE);
        assertThat(querying.status()).isEqualTo(SupplierPayableHoldOperation.Status.QUERYING); assertThat(querying.dispatches()).isEqualTo(1);
        assertThat(querying.command()).isSameAs(sending.command());
        var completed = querying.complete(new FinanceResult.Success<>(held(sending.command(), 1, querying.updatedAt())), querying.updatedAt());
        assertThat(completed.status()).isEqualTo(SupplierPayableHoldOperation.Status.HELD);
        assertThat(sending.expire(sending.leaseUntil()).claim(sending.leaseUntil(), LEASE).status()).isEqualTo(SupplierPayableHoldOperation.Status.QUERYING);
        assertThat(sending.complete(new FinanceResult.Success<>(held(sending.command(), 1, sending.leaseUntil())), sending.leaseUntil()).failure()).isEqualTo(SupplierPayableHoldOperation.Failure.LEASE_EXPIRED);
        fails("SUPPLIER_PAYABLE_HOLD_STATE_CONFLICT", () -> querying.requireSendAt(querying.updatedAt()));
    }

    @Test void authoritativeNotFoundRequiresExplicitBoundedRetryAndNeverChangesIdentity() {
        var sending = queued().claim(AUTHORIZED_AT, LEASE);
        var notFound = absent(sending.command(), AUTHORIZED_AT);
        assertThat(notFound.matches(sending.command(), false, AUTHORIZED_AT)).isFalse();
        var invalid = sending.complete(new FinanceResult.Success<>(notFound), AUTHORIZED_AT);
        assertThat(invalid.failure()).isEqualTo(SupplierPayableHoldOperation.Failure.INVALID_RESPONSE);
        var query = invalid.claim(invalid.nextAttemptAt(), LEASE);
        var found = query.complete(new FinanceResult.Success<>(absent(query.command(), query.updatedAt())), query.updatedAt());
        assertThat(found.status()).isEqualTo(SupplierPayableHoldOperation.Status.NOT_FOUND); assertThat(found.nextAttemptAt()).isNull();
        fails("SUPPLIER_PAYABLE_HOLD_STATE_CONFLICT", () -> found.claim(found.updatedAt(), LEASE));
        var second = found.retryNotFound(found.updatedAt()).claim(found.updatedAt(), LEASE);
        assertThat(second.dispatches()).isEqualTo(2); assertThat(second.command().digest()).isEqualTo(sending.command().digest());
        fails("PROCUREMENT_PAYABLE_CHANGED", () -> found.retryNotFound(sending.command().sendDeadline()));
    }

    @Test void acknowledgedPendingCannotRegressToNotFoundAndTriggerASecondReserve() {
        var sending = queued().claim(AUTHORIZED_AT, LEASE);
        var pending = new SupplierPayableHoldObservation(sending.command().id(), sending.command().digest(), SupplierPayableHoldObservation.Status.PENDING, 1L, AUTHORIZED_AT, null, null, null, null, null, null);
        var waiting = sending.complete(new FinanceResult.Success<>(pending), AUTHORIZED_AT);
        var query = waiting.claim(waiting.nextAttemptAt(), LEASE);
        var disputed = query.complete(new FinanceResult.Success<>(absent(query.command(), query.updatedAt())), query.updatedAt());
        assertThat(disputed.status()).isEqualTo(SupplierPayableHoldOperation.Status.RECONCILING);
        assertThat(disputed.observation()).isSameAs(pending); assertThat(disputed.highestRevision()).isEqualTo(1);
        fails("SUPPLIER_PAYABLE_HOLD_STATE_CONFLICT", () -> disputed.retryNotFound(disputed.updatedAt()));
    }

    @Test void heldFactSurvivesLowerVersionsChangedReceiptsAndLaterConsistentQueries() {
        var sending = queued().claim(AUTHORIZED_AT, LEASE);
        var original = held(sending.command(), 2, AUTHORIZED_AT);
        var held = sending.complete(new FinanceResult.Success<>(original), AUTHORIZED_AT);
        held.requireHeldAt(AUTHORIZED_AT.plusSeconds(299));
        fails("SUPPLIER_PAYABLE_HOLD_UNAVAILABLE", () -> held.requireHeldAt(AUTHORIZED_AT.plusSeconds(300)));
        var query = held.requestQuery(AUTHORIZED_AT.plusSeconds(1)).claim(AUTHORIZED_AT.plusSeconds(1), LEASE);
        var stale = query.complete(new FinanceResult.Success<>(held(query.command(), 1, query.updatedAt())), query.updatedAt());
        assertThat(stale.status()).isEqualTo(SupplierPayableHoldOperation.Status.RECONCILING); assertThat(stale.observation()).isSameAs(original);
        var again = stale.requestQuery(stale.updatedAt()).claim(stale.updatedAt(), LEASE);
        var stillDisputed = again.complete(new FinanceResult.Success<>(held(again.command(), 3, again.updatedAt())), again.updatedAt());
        assertThat(stillDisputed.status()).isEqualTo(SupplierPayableHoldOperation.Status.RECONCILING); assertThat(stillDisputed.highestRevision()).isEqualTo(3);
        var changed = new SupplierPayableHoldObservation(query.command().id(), query.command().digest(), SupplierPayableHoldObservation.Status.HELD, 3L, query.updatedAt(), "another-hold", "ledger-4", money("70"), account().accountDigest(), AUTHORIZED_AT, null);
        var conflict = query.complete(new FinanceResult.Success<>(changed), query.updatedAt());
        assertThat(conflict.failure()).isEqualTo(SupplierPayableHoldOperation.Failure.INCONSISTENT_OBSERVATION);
        assertThat(conflict.observation()).isSameAs(original); assertThat(conflict.conflictingObservation()).isSameAs(changed);
        fails("SUPPLIER_PAYABLE_HOLD_UNAVAILABLE", () -> conflict.requireHeldAt(conflict.updatedAt()));
    }

    @Test void duplicateReceiptCanRefreshObservationTimeButCannotChangeFactsAtSameRevision() {
        var sending = queued().claim(AUTHORIZED_AT, LEASE);
        var first = sending.complete(new FinanceResult.Success<>(held(sending.command(), 1, AUTHORIZED_AT)), AUTHORIZED_AT);
        var query = first.requestQuery(AUTHORIZED_AT.plusSeconds(310)).claim(AUTHORIZED_AT.plusSeconds(310), LEASE);
        var refreshed = query.complete(new FinanceResult.Success<>(held(query.command(), 1, query.updatedAt())), query.updatedAt());
        refreshed.requireHeldAt(query.updatedAt()); assertThat(refreshed.observation().heldAt()).isEqualTo(AUTHORIZED_AT);
        var next = refreshed.requestQuery(refreshed.updatedAt()).claim(refreshed.updatedAt(), LEASE);
        var older = next.complete(new FinanceResult.Success<>(held(next.command(), 1, AUTHORIZED_AT)), next.updatedAt());
        assertThat(older.observation().observedAt()).isEqualTo(refreshed.observation().observedAt());
        var rejected = new SupplierPayableHoldObservation(next.command().id(), next.command().digest(), SupplierPayableHoldObservation.Status.REJECTED, 1L, next.updatedAt(), null, null, null, null, null, SupplierPayableHoldObservation.Rejection.PAYABLE_INSUFFICIENT);
        assertThat(next.complete(new FinanceResult.Success<>(rejected), next.updatedAt()).status()).isEqualTo(SupplierPayableHoldOperation.Status.RECONCILING);
    }

    @Test void rejectedEnvelopeIsNotAnAuthoritativeCommandRejectionAndBusinessRejectionCannotBecomeHeld() {
        var sending = queued().claim(AUTHORIZED_AT, LEASE);
        var unknown = sending.complete(new FinanceResult.Rejected<>(FinanceResult.Reason.PROCUREMENT_PAYABLE_UNAVAILABLE), AUTHORIZED_AT);
        assertThat(unknown.status()).isEqualTo(SupplierPayableHoldOperation.Status.UNKNOWN);
        var rejected = new SupplierPayableHoldObservation(sending.command().id(), sending.command().digest(), SupplierPayableHoldObservation.Status.REJECTED, 1L, AUTHORIZED_AT, null, null, null, null, null, SupplierPayableHoldObservation.Rejection.PAYABLE_VERSION_CONFLICT);
        var terminal = sending.complete(new FinanceResult.Success<>(rejected), AUTHORIZED_AT);
        assertThat(terminal.status()).isEqualTo(SupplierPayableHoldOperation.Status.REJECTED);
        var query = terminal.requestQuery(AUTHORIZED_AT).claim(AUTHORIZED_AT, LEASE);
        assertThat(query.complete(new FinanceResult.Success<>(held(query.command(), 2, AUTHORIZED_AT)), AUTHORIZED_AT).status()).isEqualTo(SupplierPayableHoldOperation.Status.RECONCILING);
    }

    @Test void malformedEvidenceCannotCarrySuccessDataInNegativeStates() {
        var command = queued().command();
        fails("INVALID_SUPPLIER_PAYABLE_HOLD_OBSERVATION", () -> new SupplierPayableHoldObservation(command.id(), command.digest(), SupplierPayableHoldObservation.Status.HELD, 1L, AUTHORIZED_AT, "hold", "v3", money("0"), account().accountDigest(), AUTHORIZED_AT, null));
        fails("INVALID_SUPPLIER_PAYABLE_HOLD_OBSERVATION", () -> new SupplierPayableHoldObservation(command.id(), command.digest(), SupplierPayableHoldObservation.Status.NOT_FOUND, 1L, AUTHORIZED_AT, null, null, null, null, null, null));
        fails("INVALID_SUPPLIER_PAYABLE_HOLD_OBSERVATION", () -> new SupplierPayableHoldObservation(command.id(), command.digest(), SupplierPayableHoldObservation.Status.REJECTED, 1L, AUTHORIZED_AT, "hold", null, null, null, null, SupplierPayableHoldObservation.Rejection.PAYABLE_VERSION_CONFLICT));
        fails("INVALID_SUPPLIER_PAYABLE_HOLD_OBSERVATION", () -> new SupplierPayableHoldObservation(command.id(), command.digest(), SupplierPayableHoldObservation.Status.PENDING, 1L, AUTHORIZED_AT, null, null, null, null, null, SupplierPayableHoldObservation.Rejection.PAYABLE_VERSION_CONFLICT));
        var queued = queued();
        fails("SUPPLIER_PAYABLE_HOLD_STATE_CONFLICT", () -> queued.requestQuery(AUTHORIZED_AT));
        fails("SUPPLIER_PAYABLE_HOLD_STATE_CONFLICT", () -> queued.claim(AUTHORIZED_AT.minusNanos(1), LEASE));
    }

    @Test void persistedInitialStateCannotClaimPriorDispatchOrInventAnOldHoldBeforeQueueCreation() {
        var queued = queued(); var command = queued.command();
        fails("INVALID_SUPPLIER_PAYABLE_HOLD_OPERATION", () -> new SupplierPayableHoldOperation(command, 1, SupplierPayableHoldOperation.Status.QUEUED,
                1, 0, AUTHORIZED_AT, AUTHORIZED_AT, AUTHORIZED_AT, null, null, null, 0, null));
        fails("INVALID_SUPPLIER_PAYABLE_HOLD_OPERATION", () -> new SupplierPayableHoldOperation(command, 1, SupplierPayableHoldOperation.Status.QUEUED,
                0, 0, AUTHORIZED_AT, AUTHORIZED_AT, AUTHORIZED_AT, null, absent(command, AUTHORIZED_AT), null, 0, null));
    }

    @Test void aReceiptBeforeTheOriginalQueueWasSavedIsNotAnAcceptableHold() {
        var command = queued().command(); var queuedAt = AUTHORIZED_AT.plusSeconds(10);
        var sending = SupplierPayableHoldOperation.queue(command, queuedAt).claim(queuedAt, LEASE);
        var completed = sending.complete(new FinanceResult.Success<>(held(command, 1, queuedAt)), queuedAt);
        assertThat(completed.status()).isEqualTo(SupplierPayableHoldOperation.Status.UNKNOWN);
        assertThat(completed.failure()).isEqualTo(SupplierPayableHoldOperation.Failure.INVALID_RESPONSE);
    }

    @Test void sourceChangeStopsOnlyUnclaimedSendingAndPreservesPriorDispatchQueryability() {
        var queued = queued(); var stopped = queued.voidBeforeSend(AUTHORIZED_AT);
        assertThat(stopped.status()).isEqualTo(SupplierPayableHoldOperation.Status.VOIDED); assertThat(stopped.dispatches()).isZero();
        var sending = queued.claim(AUTHORIZED_AT, LEASE);
        fails("SUPPLIER_PAYABLE_HOLD_STATE_CONFLICT", () -> sending.voidBeforeSend(AUTHORIZED_AT));
        var unknown = sending.unavailable(SupplierPayableHoldOperation.Failure.TIMEOUT, AUTHORIZED_AT);
        fails("SUPPLIER_PAYABLE_HOLD_STATE_CONFLICT", () -> unknown.voidBeforeSend(AUTHORIZED_AT));
        var query = unknown.claim(unknown.nextAttemptAt(), LEASE);
        var absent = query.complete(new FinanceResult.Success<>(absent(query.command(), query.updatedAt())), query.updatedAt());
        var noResend = absent.retryNotFound(absent.updatedAt()).voidBeforeSend(absent.updatedAt());
        assertThat(noResend.observation()).isEqualTo(absent.observation()); assertThat(noResend.dispatches()).isEqualTo(1);
        assertThat(noResend.requestQuery(noResend.updatedAt()).claim(noResend.updatedAt(), LEASE).status()).isEqualTo(SupplierPayableHoldOperation.Status.QUERYING);
    }

    private static SupplierPayableHoldOperation queued() { return SupplierPayableHoldOperation.queue(new SupplierPayableHoldCommand(authorization()), AUTHORIZED_AT); }
    private static SupplierPayableHoldObservation held(SupplierPayableHoldCommand command, long revision, Instant observedAt) {
        return new SupplierPayableHoldObservation(command.id(), command.digest(), SupplierPayableHoldObservation.Status.HELD, revision, observedAt, "hold-1", "ledger-3", money("70"), account().accountDigest(), AUTHORIZED_AT, null);
    }
    private static SupplierPayableHoldObservation absent(SupplierPayableHoldCommand command, Instant observedAt) {
        return new SupplierPayableHoldObservation(command.id(), command.digest(), SupplierPayableHoldObservation.Status.NOT_FOUND, 0L, observedAt, null, null, null, null, null, null);
    }
    private static void fails(String code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
}
