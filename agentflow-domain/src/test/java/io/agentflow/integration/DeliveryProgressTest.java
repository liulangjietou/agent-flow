package io.agentflow.integration;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

/**
 * 投递状态机覆盖退避、崩溃恢复、旧租约、人工重试与尝试上限。
 * @author owlzhangfq@gmail.com
 */
class DeliveryProgressTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void receiverDeadlineCanOnlyLengthenAutomaticBackoff() {
        for (long seconds : new long[]{-60, 0, 2, 120}) {
            var result = DeliveryProgress.pending(NOW).claim(NOW, "lease")
                    .finish("lease", DeliveryProgress.Outcome.http(429, NOW.plusSeconds(seconds)), NOW);
            Instant expected = NOW.plusSeconds(Math.max(5, seconds));
            assertThat(result.nextAttemptAt()).isEqualTo(expected);
            assertThat(result.due(expected.minusNanos(1))).isFalse();
            assertThat(result.due(expected)).isTrue();
        }
    }

    @Test
    void receiverDeadlineCannotRetryPermanentResultsOrExceedTheAttemptLimit() {
        for (int status : new int[]{204, 302, 400, 401}) {
            var result = DeliveryProgress.pending(NOW).claim(NOW, "lease")
                    .finish("lease", DeliveryProgress.Outcome.http(status, NOW.plusSeconds(120)), NOW);
            assertThat(result.nextAttemptAt()).isNull();
            assertThat(result.status()).isEqualTo(status == 204 ? DeliveryProgress.Status.DELIVERED : DeliveryProgress.Status.FAILED);
        }
        var result = DeliveryProgress.pending(NOW);
        for (int attempt = 0; attempt < DeliveryProgress.MAX_CYCLE_ATTEMPTS; attempt++) {
            Instant at = result.nextAttemptAt();
            result = result.claim(at, "lease").finish("lease", DeliveryProgress.Outcome.http(503, at.plusSeconds(86400)), at);
        }
        assertThat(result.status()).isEqualTo(DeliveryProgress.Status.FAILED);
        assertThat(result.nextAttemptAt()).isNull();
    }

    @Test
    void explicitManualRetryStillStartsImmediatelyAndKeepsLifetimeAttempts() {
        var waiting = DeliveryProgress.pending(NOW).claim(NOW, "lease")
                .finish("lease", DeliveryProgress.Outcome.http(429, NOW.plusSeconds(120)), NOW);
        var manual = waiting.retry(waiting.version(), NOW);
        assertThat(manual.nextAttemptAt()).isEqualTo(NOW);
        assertThat(manual.attempts()).isEqualTo(1);
        assertThat(manual.cycleAttempts()).isZero();
    }

    @Test
    void retriesTransientFailuresAndStopsAfterSixAttempts() {
        var progress = DeliveryProgress.pending(NOW);
        long[] delays = {5, 30, 120, 600, 3600};
        for (int i = 0; i < 6; i++) {
            Instant at = progress.nextAttemptAt();
            progress = progress.claim(at, "lease-" + i).finish("lease-" + i, DeliveryProgress.Outcome.http(503), at);
            assertThat(progress.attempts()).isEqualTo(i + 1);
            if (i < 5) {
                assertThat(progress.status()).isEqualTo(DeliveryProgress.Status.RETRY_WAIT);
                assertThat(progress.nextAttemptAt()).isEqualTo(at.plusSeconds(delays[i]));
                assertThat(progress.due(at)).isFalse();
            }
        }
        assertThat(progress.status()).isEqualTo(DeliveryProgress.Status.FAILED);
        assertThat(progress.nextAttemptAt()).isNull(); assertThat(progress.due(NOW.plusSeconds(10000))).isFalse();
    }

    @Test
    void neverTreatsCrashesAsSuccessOrAllowsOldLeaseToFinishNewClaim() {
        var claimed = DeliveryProgress.pending(NOW).claim(NOW, "old");
        assertThat(claimed.due(NOW.plusSeconds(29))).isFalse();
        var recovered = claimed.claim(NOW.plusSeconds(30), "new");
        assertThat(recovered.attempts()).isEqualTo(2);
        assertThatThrownBy(() -> recovered.finish("old", DeliveryProgress.Outcome.http(200), NOW.plusSeconds(31))).isInstanceOf(DomainException.class);
        assertThat(recovered.finish("new", DeliveryProgress.Outcome.http(204), NOW.plusSeconds(31)).status()).isEqualTo(DeliveryProgress.Status.DELIVERED);
        var lost = claimed;
        for (int i = 1; i < 6; i++) lost = lost.claim(NOW.plusSeconds(i * 30), "lease-" + i);
        lost = lost.claim(NOW.plusSeconds(180), "unused");
        assertThat(lost.status()).isEqualTo(DeliveryProgress.Status.FAILED);
        assertThat(lost.attempts()).isEqualTo(6); assertThat(lost.errorCode()).isEqualTo("OUTCOME_UNKNOWN");
    }

    @Test
    void manualRetryRequiresCurrentVersionAndPreservesLifetimeAttempts() {
        var pending = DeliveryProgress.pending(NOW);
        assertThatThrownBy(() -> pending.retry(1, NOW)).isInstanceOf(DomainException.class);
        var claimed = pending.claim(NOW, "lease");
        assertThatThrownBy(() -> claimed.retry(2, NOW)).isInstanceOf(DomainException.class);
        var completed = claimed.finish("lease", DeliveryProgress.Outcome.http(200), NOW);
        assertThatThrownBy(() -> completed.retry(1, NOW)).isInstanceOf(DomainException.class);
        var retry = completed.retry(completed.version(), NOW);
        assertThat(retry.attempts()).isEqualTo(1); assertThat(retry.cycleAttempts()).isZero();
        assertThat(retry.claim(NOW, "again").attempts()).isEqualTo(2);
    }

    @Test
    void classifiesRedirectAndClientFailuresWithoutRetryingAuthenticationErrors() {
        for (int code : new int[]{200, 201, 202, 204, 299}) assertThat(DeliveryProgress.Outcome.http(code).success()).isTrue();
        for (int code : new int[]{301, 302, 307, 400, 401, 403, 404, 410, 422}) assertThat(DeliveryProgress.Outcome.http(code).retryable()).isFalse();
        for (int code : new int[]{408, 425, 429, 500, 502, 503}) assertThat(DeliveryProgress.Outcome.http(code).retryable()).isTrue();
    }
}
