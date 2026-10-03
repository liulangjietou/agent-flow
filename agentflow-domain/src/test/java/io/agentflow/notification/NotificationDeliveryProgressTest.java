package io.agentflow.notification;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.agentflow.notification.NotificationDeliveryProgress.*;
import static org.assertj.core.api.Assertions.*;

/** 发送事实与重复风险的领域边界。
 * @author owlzhangfq@gmail.com
 */
class NotificationDeliveryProgressTest {
    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    @Test void temporaryRejectionsHaveABoundedRetryCycle() {
        var value = pending(NOW);
        for (int count = 1; count <= MAX_CYCLE_ATTEMPTS; count++) {
            var due = value.nextAttemptAt();
            assertThat(value.due(due.minusNanos(1))).isFalse();
            value = value.start(due, UUID.randomUUID()).complete(Outcome.retryable(FailureCode.SMTP_TEMPORARY_REJECTION), due);
            assertThat(value.attempts()).isEqualTo(count);
            assertThat(value.status()).isEqualTo(count == MAX_CYCLE_ATTEMPTS ? Status.FAILED : Status.RETRY_WAIT);
        }
        var retried = value.retry(value.version(), false, NOW.plusSeconds(600));
        assertThat(retried.attempts()).isEqualTo(MAX_CYCLE_ATTEMPTS);
        assertThat(retried.cycleAttempts()).isZero();
        assertThat(retried.status()).isEqualTo(Status.PENDING);
    }

    @Test void expiredLeaseIsUnknownAndCannotBeAutomaticallyClaimed() {
        var started = pending(NOW).start(NOW, UUID.randomUUID());
        assertThat(started.expire(started.leaseUntil().minusNanos(1))).isSameAs(started);
        var expired = started.expire(started.leaseUntil());
        assertThat(expired.status()).isEqualTo(Status.UNKNOWN);
        assertThat(expired.errorCode()).isEqualTo(FailureCode.LEASE_EXPIRED);
        assertThat(expired.due(NOW.plusSeconds(10000))).isFalse();
        assertThatThrownBy(() -> expired.start(NOW.plusSeconds(10000), UUID.randomUUID())).isInstanceOf(DomainException.class);
    }

    @Test void unknownNeedsExplicitDuplicateAcknowledgementAndCurrentVersion() {
        var unknown = pending(NOW).start(NOW, UUID.randomUUID()).complete(Outcome.unknown(FailureCode.SMTP_RESULT_UNKNOWN), NOW);
        assertThatThrownBy(() -> unknown.retry(unknown.version(), false, NOW)).isInstanceOfSatisfying(DomainException.class,
                failure -> assertThat(failure.code()).isEqualTo("NOTIFICATION_DUPLICATE_ACK_REQUIRED"));
        assertThatThrownBy(() -> unknown.retry(unknown.version() - 1, true, NOW)).isInstanceOfSatisfying(DomainException.class,
                failure -> assertThat(failure.code()).isEqualTo("CONCURRENCY_CONFLICT"));
        assertThat(unknown.retry(unknown.version(), true, NOW).status()).isEqualTo(Status.PENDING);
    }

    @Test void suppressionOnlyAffectsMessagesWhoseNextAttemptHasNotStarted() {
        var pending = pending(NOW);
        assertThat(pending.suppress(FailureCode.CONSENT_REVOKED, NOW).attempts()).isZero();
        var started = pending.start(NOW, UUID.randomUUID());
        assertThat(started.suppress(FailureCode.CONSENT_REVOKED, NOW)).isSameAs(started);
        var waiting = started.complete(Outcome.retryable(FailureCode.SMTP_CONNECT_FAILED), NOW);
        assertThat(waiting.suppress(FailureCode.CONSENT_REVOKED, NOW).status()).isEqualTo(Status.SUPPRESSED);
    }

    @Test void acceptedAndSuppressedCannotBeRetried() {
        var initial = pending(NOW);
        var accepted = initial.start(NOW, UUID.randomUUID()).complete(Outcome.accepted(), NOW);
        assertThat(accepted.status()).isEqualTo(Status.ACCEPTED);
        assertThat(accepted.errorCode()).isNull();
        assertThat(accepted.leaseToken()).isNull();
        for (var terminal : java.util.List.of(accepted, initial.suppress(FailureCode.CONSENT_REVOKED, NOW)))
            assertThatThrownBy(() -> terminal.retry(terminal.version(), true, NOW)).isInstanceOf(DomainException.class);
    }
}
