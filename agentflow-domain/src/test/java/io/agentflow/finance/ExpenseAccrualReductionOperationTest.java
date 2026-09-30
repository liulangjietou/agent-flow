package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

/**
 * 重启、查无、迟到、外部倒退和已过账矛盾不允许挂账差额被重复首次发送。
 * @author owlzhangfq@gmail.com
 */
class ExpenseAccrualReductionOperationTest {
    private static final Instant NOW = ExpenseAccrualReductionTest.NOW;
    private static final Duration LEASE = Duration.ofSeconds(30);
    private final ExpenseAccrualReductionCommand command = ExpenseAccrualReductionTest.command(ExpenseAccrualReductionTest.financial("30", "80", "4"), null, NOW);
    private final ExpenseAccrualReductionOperation.Input input = new ExpenseAccrualReductionOperation.Input(3, command, "a".repeat(64));

    @Test void unknownAndExpiredClaimsRecoverThroughQueriesEvenAfterAuthorizationExpires() {
        var posting = queued().claim(NOW, LEASE);
        var unknown = posting.complete(new FinanceResult.Unavailable<>(FinanceResult.Failure.TIMEOUT), NOW.plusSeconds(1));
        assertThat(unknown.status()).isEqualTo(ExpenseAccrualReductionOperation.Status.UNKNOWN);
        var query = unknown.claim(NOW.plusSeconds(400), LEASE); assertThat(query.status()).isEqualTo(ExpenseAccrualReductionOperation.Status.QUERYING);
        var posted = query.complete(new FinanceResult.Success<>(posted(2, NOW.plusSeconds(401), "reverse")), NOW.plusSeconds(401));
        assertThat(posted.status()).isEqualTo(ExpenseAccrualReductionOperation.Status.POSTED);
        assertThat(posted.input()).isEqualTo(input);
        assertThat(posting.complete(new FinanceResult.Success<>(posted(2, NOW.plusSeconds(30), "reverse")), NOW.plusSeconds(30)).failure()).isEqualTo(ExpenseAccrualReductionOperation.Failure.LEASE_EXPIRED);
    }
    @Test void queuedAuthorizationExpiryAndChangedSourceNeverSend() {
        assertThat(queued().claim(command.expiresAt(), LEASE).status()).isEqualTo(ExpenseAccrualReductionOperation.Status.EXPIRED);
        assertThat(queued().voidBeforeSend(NOW).status()).isEqualTo(ExpenseAccrualReductionOperation.Status.VOIDED);
        assertThatThrownBy(() -> queued().claim(NOW, LEASE).voidBeforeSend(NOW)).isInstanceOf(DomainException.class);
    }
    @Test void onlyExplicitUnseenNotFoundCanResendTheOriginalUnchangedCommand() {
        var first = queued().claim(NOW, LEASE); var absent = observation(ExpenseAccrualReductionObservation.Status.NOT_FOUND, 0, NOW.plusSeconds(1));
        assertThat(first.complete(new FinanceResult.Success<>(absent), NOW.plusSeconds(1)).failure()).isEqualTo(ExpenseAccrualReductionOperation.Failure.INVALID_RESPONSE);
        var query = first.expire(NOW.plusSeconds(30)).claim(NOW.plusSeconds(30), LEASE);
        var notFound = query.complete(new FinanceResult.Success<>(observation(ExpenseAccrualReductionObservation.Status.NOT_FOUND, 0, NOW.plusSeconds(31))), NOW.plusSeconds(31));
        assertThat(notFound.nextAttemptAt()).isNull(); assertThat(notFound.status()).isEqualTo(ExpenseAccrualReductionOperation.Status.NOT_FOUND);
        var resend = notFound.retryNotFound(NOW.plusSeconds(32)).claim(NOW.plusSeconds(32), LEASE);
        assertThat(resend.status()).isEqualTo(ExpenseAccrualReductionOperation.Status.POSTING); assertThat(resend.input()).isEqualTo(input);
        assertThatThrownBy(() -> notFound.retryNotFound(command.expiresAt())).isInstanceOf(DomainException.class);
    }
    @Test void acceptedPendingCannotBecomeNotFoundAndHighestRevisionNeverDecreases() {
        var accepted = queued().claim(NOW, LEASE).complete(new FinanceResult.Success<>(observation(ExpenseAccrualReductionObservation.Status.PENDING, 5, NOW.plusSeconds(1))), NOW.plusSeconds(1));
        var disputed = accepted.claim(NOW.plusSeconds(6), LEASE).complete(new FinanceResult.Success<>(observation(ExpenseAccrualReductionObservation.Status.NOT_FOUND, 0, NOW.plusSeconds(7))), NOW.plusSeconds(7));
        assertThat(disputed.status()).isEqualTo(ExpenseAccrualReductionOperation.Status.RECONCILING); assertThat(disputed.highestRevision()).isEqualTo(5);
        assertThat(disputed.observation().status()).isEqualTo(ExpenseAccrualReductionObservation.Status.PENDING);
        assertThatThrownBy(() -> disputed.retryNotFound(NOW.plusSeconds(8))).isInstanceOf(DomainException.class);
        var later = disputed.requestQuery(NOW.plusSeconds(8)).claim(NOW.plusSeconds(8), LEASE)
                .complete(new FinanceResult.Success<>(observation(ExpenseAccrualReductionObservation.Status.PENDING, 6, NOW.plusSeconds(9))), NOW.plusSeconds(9));
        assertThat(later.status()).isEqualTo(ExpenseAccrualReductionOperation.Status.RECONCILING); assertThat(later.highestRevision()).isEqualTo(6);
    }
    @Test void aPostedReductionCannotDisappearChangeVoucherOrBecomeFailure() {
        var original = posted(2, NOW.plusSeconds(1), "reverse");
        var posted = queued().claim(NOW, LEASE).complete(new FinanceResult.Success<>(original), NOW.plusSeconds(1));
        for (var incoming : new ExpenseAccrualReductionObservation[] {
                observation(ExpenseAccrualReductionObservation.Status.FAILED, 3, NOW.plusSeconds(3)),
                observation(ExpenseAccrualReductionObservation.Status.NOT_FOUND, 0, NOW.plusSeconds(3)),
                posted(3, NOW.plusSeconds(3), "other")}) {
            var disputed = posted.requestQuery(NOW.plusSeconds(2)).claim(NOW.plusSeconds(2), LEASE).complete(new FinanceResult.Success<>(incoming), NOW.plusSeconds(3));
            assertThat(disputed.status()).isEqualTo(ExpenseAccrualReductionOperation.Status.RECONCILING); assertThat(disputed.observation()).isEqualTo(original);
        }
        var same = posted.requestQuery(NOW.plusSeconds(2)).claim(NOW.plusSeconds(2), LEASE)
                .complete(new FinanceResult.Success<>(posted(3, NOW.plusSeconds(3), "reverse")), NOW.plusSeconds(3));
        assertThat(same.status()).isEqualTo(ExpenseAccrualReductionOperation.Status.POSTED);
    }
    @Test void restoredSnapshotsCannotTurnPreviouslySentCommandIntoFirstSendOrErasePosting() {
        var value = queued().claim(NOW, LEASE);
        assertThatThrownBy(() -> new ExpenseAccrualReductionOperation(input, value.version(), ExpenseAccrualReductionOperation.Status.QUEUED, value.attempts(), NOW, NOW, NOW, null, null, null, 0, null)).isInstanceOf(DomainException.class);
        var posted = value.complete(new FinanceResult.Success<>(posted(2, NOW.plusSeconds(1), "reverse")), NOW.plusSeconds(1));
        assertThatThrownBy(() -> new ExpenseAccrualReductionOperation(input, posted.version(), ExpenseAccrualReductionOperation.Status.POSTED, posted.attempts(), NOW, NOW.plusSeconds(1), null, null, null, null, 2, null)).isInstanceOf(DomainException.class);
    }
    @Test void retirementRequiresNoSendOrFailureIndependentOfOriginalOrAdjustmentConflicts() {
        assertThat(queued().retirementBasis()).isEqualTo(ExpenseAccrualReductionOperation.RetirementBasis.NEVER_DISPATCHED);
        assertThat(queued().stopForRetirement(NOW).status()).isEqualTo(ExpenseAccrualReductionOperation.Status.VOIDED);
        for (var reason : ExpenseAccrualReductionObservation.Rejection.values()) {
            var failure = new ExpenseAccrualReductionObservation(command.id(), command.adjustmentId(), command.digest(), ExpenseAccrualReductionObservation.Status.FAILED,
                    1, NOW.plusSeconds(1), "accepted", null, reason);
            var failed = queued().claim(NOW, LEASE).complete(new FinanceResult.Success<>(failure), NOW.plusSeconds(1));
            boolean safe = switch (reason) {
                case ACCOUNTING_PERIOD_CLOSED, LEGAL_ENTITY_UNAVAILABLE, ACCOUNT_UNAVAILABLE, AUTHORIZATION_REJECTED -> true;
                case ORIGINAL_NOT_POSTED, ORIGINAL_CHANGED, ADJUSTMENT_VERSION_CONFLICT -> false;
            };
            if (safe) assertThat(failed.retirementBasis()).isEqualTo(ExpenseAccrualReductionOperation.RetirementBasis.CONFIRMED_FAILED);
            else {
                assertThat(failed.retirementBasis()).isNull();
                assertThatThrownBy(() -> failed.stopForRetirement(NOW.plusSeconds(2))).isInstanceOf(DomainException.class);
            }
        }
        var unknown = queued().claim(NOW, LEASE).unavailable(ExpenseAccrualReductionOperation.Failure.TIMEOUT, NOW.plusSeconds(1));
        assertThat(unknown.retirementBasis()).isNull();
    }

    @Test void aNeverDispatchedSnapshotCannotContainExternalPostingEvidence() {
        var observed = posted(2, NOW.plusSeconds(1), "first");
        assertThatThrownBy(() -> new ExpenseAccrualReductionOperation(input, 2, ExpenseAccrualReductionOperation.Status.POSTED,
                0, NOW, NOW.plusSeconds(1), null, null, observed, null, 2, null)).isInstanceOf(DomainException.class);
    }

    private ExpenseAccrualReductionObservation posted(long revision, Instant observedAt, String reference) {
        var original = ExpenseAccrualReductionTest.posted(command, NOW.plusSeconds(1), reference);
        var accepted = original.posting().original();
        var latest = new VoucherObservation(accepted.operationId(), accepted.commandDigest(), accepted.status(), accepted.revision(), observedAt,
                accepted.postingReference(), accepted.voucherReference(), accepted.periodReference(), accepted.accountingDate(), accepted.debitTotal(), accepted.creditTotal(), accepted.postedAt(), null);
        var posting = new ExpenseAccrualReductionObservation.Posting(latest, original.posting().adjustmentRevision(), original.posting().beforeDigest(), original.posting().afterDigest(), original.posting().voucher());
        return new ExpenseAccrualReductionObservation(command.id(), command.adjustmentId(), command.digest(), original.status(), revision,
                observedAt, original.acceptanceReference(), posting, null);
    }

    private ExpenseAccrualReductionOperation queued() { return ExpenseAccrualReductionOperation.queue(input, NOW); }
    private ExpenseAccrualReductionObservation observation(ExpenseAccrualReductionObservation.Status status, long revision, Instant at) {
        return new ExpenseAccrualReductionObservation(command.id(), command.adjustmentId(), command.digest(), status, revision, at, status == ExpenseAccrualReductionObservation.Status.NOT_FOUND ? null : "accepted", null,
                status == ExpenseAccrualReductionObservation.Status.FAILED ? ExpenseAccrualReductionObservation.Rejection.ORIGINAL_CHANGED : null);
    }
}
