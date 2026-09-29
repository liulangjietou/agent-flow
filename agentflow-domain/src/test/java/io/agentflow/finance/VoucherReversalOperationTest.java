package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

/**
 * 重启、查无、迟到、外部倒退和已过账矛盾不允许冲销被重复首次发送。
 * @author owlzhangfq@gmail.com
 */
class VoucherReversalOperationTest {
    private static final Instant NOW = VoucherReversalCommandTest.NOW;
    private static final Duration LEASE = Duration.ofSeconds(30);
    private final VoucherReversalCommand command = VoucherReversalCommandTest.command(VoucherCommand.Kind.EMPLOYEE_ADVANCE);
    private final VoucherReversalOperation.Input input = new VoucherReversalOperation.Input(3, command, "a".repeat(64));

    @Test void unknownAndExpiredClaimsRecoverThroughQueriesEvenAfterAuthorizationExpires() {
        var posting = queued().claim(NOW, LEASE);
        var unknown = posting.complete(new FinanceResult.Unavailable<>(FinanceResult.Failure.TIMEOUT), NOW.plusSeconds(1));
        assertThat(unknown.status()).isEqualTo(VoucherReversalOperation.Status.UNKNOWN);
        var query = unknown.claim(NOW.plusSeconds(400), LEASE); assertThat(query.status()).isEqualTo(VoucherReversalOperation.Status.QUERYING);
        var posted = query.complete(new FinanceResult.Success<>(VoucherReversalCommandTest.posted(command, 2, NOW.plusSeconds(401), "reverse")), NOW.plusSeconds(401));
        assertThat(posted.status()).isEqualTo(VoucherReversalOperation.Status.POSTED);
        assertThat(posted.input()).isEqualTo(input);
        assertThat(posting.complete(new FinanceResult.Success<>(VoucherReversalCommandTest.posted(command, 2, NOW.plusSeconds(30), "reverse")), NOW.plusSeconds(30)).failure()).isEqualTo(VoucherReversalOperation.Failure.LEASE_EXPIRED);
    }
    @Test void queuedAuthorizationExpiryAndChangedSourceNeverSend() {
        assertThat(queued().claim(command.expiresAt(), LEASE).status()).isEqualTo(VoucherReversalOperation.Status.EXPIRED);
        assertThat(queued().voidBeforeSend(NOW).status()).isEqualTo(VoucherReversalOperation.Status.VOIDED);
        assertThatThrownBy(() -> queued().claim(NOW, LEASE).voidBeforeSend(NOW)).isInstanceOf(DomainException.class);
    }
    @Test void onlyExplicitUnseenNotFoundCanResendTheOriginalUnchangedCommand() {
        var first = queued().claim(NOW, LEASE); var absent = observation(VoucherReversalObservation.Status.NOT_FOUND, 0, NOW.plusSeconds(1));
        assertThat(first.complete(new FinanceResult.Success<>(absent), NOW.plusSeconds(1)).failure()).isEqualTo(VoucherReversalOperation.Failure.INVALID_RESPONSE);
        var query = first.expire(NOW.plusSeconds(30)).claim(NOW.plusSeconds(30), LEASE);
        var notFound = query.complete(new FinanceResult.Success<>(observation(VoucherReversalObservation.Status.NOT_FOUND, 0, NOW.plusSeconds(31))), NOW.plusSeconds(31));
        assertThat(notFound.nextAttemptAt()).isNull(); assertThat(notFound.status()).isEqualTo(VoucherReversalOperation.Status.NOT_FOUND);
        var resend = notFound.retryNotFound(NOW.plusSeconds(32)).claim(NOW.plusSeconds(32), LEASE);
        assertThat(resend.status()).isEqualTo(VoucherReversalOperation.Status.POSTING); assertThat(resend.input()).isEqualTo(input);
        assertThatThrownBy(() -> notFound.retryNotFound(command.expiresAt())).isInstanceOf(DomainException.class);
    }
    @Test void acceptedPendingCannotBecomeNotFoundAndHighestRevisionNeverDecreases() {
        var accepted = queued().claim(NOW, LEASE).complete(new FinanceResult.Success<>(observation(VoucherReversalObservation.Status.PENDING, 5, NOW.plusSeconds(1))), NOW.plusSeconds(1));
        var disputed = accepted.claim(NOW.plusSeconds(6), LEASE).complete(new FinanceResult.Success<>(observation(VoucherReversalObservation.Status.NOT_FOUND, 0, NOW.plusSeconds(7))), NOW.plusSeconds(7));
        assertThat(disputed.status()).isEqualTo(VoucherReversalOperation.Status.RECONCILING); assertThat(disputed.highestRevision()).isEqualTo(5);
        assertThat(disputed.observation().status()).isEqualTo(VoucherReversalObservation.Status.PENDING);
        assertThatThrownBy(() -> disputed.retryNotFound(NOW.plusSeconds(8))).isInstanceOf(DomainException.class);
        var later = disputed.requestQuery(NOW.plusSeconds(8)).claim(NOW.plusSeconds(8), LEASE)
                .complete(new FinanceResult.Success<>(observation(VoucherReversalObservation.Status.PENDING, 6, NOW.plusSeconds(9))), NOW.plusSeconds(9));
        assertThat(later.status()).isEqualTo(VoucherReversalOperation.Status.RECONCILING); assertThat(later.highestRevision()).isEqualTo(6);
    }
    @Test void aPostedReversalCannotDisappearChangeVoucherOrBecomeFailure() {
        var original = VoucherReversalCommandTest.posted(command, 2, NOW.plusSeconds(1), "reverse");
        var posted = queued().claim(NOW, LEASE).complete(new FinanceResult.Success<>(original), NOW.plusSeconds(1));
        for (var incoming : new VoucherReversalObservation[] {
                observation(VoucherReversalObservation.Status.FAILED, 3, NOW.plusSeconds(3)),
                observation(VoucherReversalObservation.Status.NOT_FOUND, 0, NOW.plusSeconds(3)),
                VoucherReversalCommandTest.posted(command, 3, NOW.plusSeconds(3), "other")}) {
            var disputed = posted.requestQuery(NOW.plusSeconds(2)).claim(NOW.plusSeconds(2), LEASE).complete(new FinanceResult.Success<>(incoming), NOW.plusSeconds(3));
            assertThat(disputed.status()).isEqualTo(VoucherReversalOperation.Status.RECONCILING); assertThat(disputed.observation()).isEqualTo(original);
        }
        var same = posted.requestQuery(NOW.plusSeconds(2)).claim(NOW.plusSeconds(2), LEASE)
                .complete(new FinanceResult.Success<>(VoucherReversalCommandTest.posted(command, 3, NOW.plusSeconds(3), "reverse")), NOW.plusSeconds(3));
        assertThat(same.status()).isEqualTo(VoucherReversalOperation.Status.POSTED);
    }
    @Test void restoredSnapshotsCannotTurnPreviouslySentCommandIntoFirstSendOrErasePosting() {
        var value = queued().claim(NOW, LEASE);
        assertThatThrownBy(() -> new VoucherReversalOperation(input, value.version(), VoucherReversalOperation.Status.QUEUED, value.attempts(), NOW, NOW, NOW, null, null, null, 0, null)).isInstanceOf(DomainException.class);
        var posted = value.complete(new FinanceResult.Success<>(VoucherReversalCommandTest.posted(command, 2, NOW.plusSeconds(1), "reverse")), NOW.plusSeconds(1));
        assertThatThrownBy(() -> new VoucherReversalOperation(input, posted.version(), VoucherReversalOperation.Status.POSTED, posted.attempts(), NOW, NOW.plusSeconds(1), null, null, null, null, 2, null)).isInstanceOf(DomainException.class);
    }
    private VoucherReversalOperation queued() { return VoucherReversalOperation.queue(input, NOW); }
    private VoucherReversalObservation observation(VoucherReversalObservation.Status status, long revision, Instant at) {
        return new VoucherReversalObservation(command.id(), command.digest(), status, revision, at, status == VoucherReversalObservation.Status.NOT_FOUND ? null : "accepted", null,
                status == VoucherReversalObservation.Status.FAILED ? VoucherReversalObservation.Rejection.ORIGINAL_CHANGED : null);
    }
}
