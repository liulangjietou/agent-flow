package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

/**
 * 凭证恢复不能遗失最高外部版本、已过账事实或跨租约的冲突证据。
 * @author owlzhangfq@gmail.com
 */
class VoucherOperationTest {
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(15);

    @Test
    void firstPostAndExactRepeatedObservationPreserveCommittedEvidence() {
        var posted = posted(); assertThat(posted.status()).isEqualTo(VoucherOperation.Status.POSTED);
        assertThat(posted.version()).isEqualTo(3); assertThat(posted.highestRevision()).isEqualTo(2); assertThat(posted.usablePosted()).isTrue();
        var query = query(posted); assertThat(query.usablePosted()).isFalse(); assertThat(query.observation()).isEqualTo(posted.observation());
        var again = query.complete(success(fact(query, VoucherObservation.Status.POSTED, 2, query.updatedAt().plusSeconds(1), "voucher-1")), query.updatedAt().plusSeconds(1));
        assertThat(again.status()).isEqualTo(VoucherOperation.Status.POSTED); assertThat(again.highestRevision()).isEqualTo(2);
        assertThat(again.observation().observedAt()).isAfter(posted.observation().observedAt());
    }

    @Test
    void crashedOrExpiredDispatchCanOnlyRecoverByOriginalQuery() {
        var claimed = queued().claim(NOW, LEASE); var unknown = claimed.expire(NOW.plusSeconds(15));
        assertThat(unknown.failure()).isEqualTo(VoucherOperation.Failure.LEASE_EXPIRED);
        var query = unknown.claim(unknown.nextAttemptAt(), LEASE); assertThat(query.status()).isEqualTo(VoucherOperation.Status.QUERYING);
        assertThat(query.input()).isEqualTo(claimed.input());
        var recovered = query.complete(success(fact(query, VoucherObservation.Status.POSTED, 2, query.updatedAt().plusSeconds(1), "voucher-1")), query.updatedAt().plusSeconds(1));
        assertThat(recovered.usablePosted()).isTrue();
        var late = claimed.complete(success(fact(claimed, VoucherObservation.Status.POSTED, 2, NOW.plusSeconds(15), "voucher-1")), NOW.plusSeconds(15));
        assertThat(late.status()).isEqualTo(VoucherOperation.Status.UNKNOWN); assertThat(late.observation()).isNull();
    }

    @Test
    void authoritativeNotFoundStopsUntilExplicitRetryOfExactCommandWithinExpiry() {
        var unknown = queued().claim(NOW, LEASE).unavailable(VoucherOperation.Failure.TIMEOUT, NOW.plusSeconds(1));
        var query = unknown.claim(unknown.nextAttemptAt(), LEASE); var missing = query.complete(success(fact(query, VoucherObservation.Status.NOT_FOUND, 0, query.updatedAt().plusSeconds(1), null)), query.updatedAt().plusSeconds(1));
        assertThat(missing.status()).isEqualTo(VoucherOperation.Status.NOT_FOUND); assertThat(missing.nextAttemptAt()).isNull();
        assertThatThrownBy(() -> missing.claim(missing.updatedAt(), LEASE)).isInstanceOf(DomainException.class);
        var retry = missing.retryNotFound(missing.updatedAt()).claim(missing.updatedAt(), LEASE);
        assertThat(retry.status()).isEqualTo(VoucherOperation.Status.POSTING); assertThat(retry.input()).isEqualTo(unknown.input());
        assertThatThrownBy(() -> missing.retryNotFound(NOW.plusSeconds(60))).isInstanceOf(DomainException.class);
        var expired = queued().claim(NOW.plusSeconds(60), LEASE); assertThat(expired.status()).isEqualTo(VoucherOperation.Status.EXPIRED); assertThat(expired.attempts()).isZero();
    }

    @Test
    void lowerRevisionAndNotFoundCannotEraseKnownPendingTransaction() {
        var initial = queued().claim(NOW, LEASE); var pending = initial.complete(success(fact(initial, VoucherObservation.Status.PENDING, 3, NOW.plusSeconds(1), null)), NOW.plusSeconds(1));
        var query = pending.claim(pending.nextAttemptAt(), LEASE); var stale = query.complete(success(fact(query, VoucherObservation.Status.POSTED, 2, query.updatedAt().plusSeconds(1), "voucher-1")), query.updatedAt().plusSeconds(1));
        assertThat(stale.status()).isEqualTo(VoucherOperation.Status.RECONCILING); assertThat(stale.failure()).isEqualTo(VoucherOperation.Failure.STALE_OBSERVATION);
        assertThat(stale.highestRevision()).isEqualTo(3); assertThat(stale.observation()).isEqualTo(pending.observation());
        var next = query(stale); var missing = next.complete(success(fact(next, VoucherObservation.Status.NOT_FOUND, 0, next.updatedAt().plusSeconds(1), null)), next.updatedAt().plusSeconds(1));
        assertThat(missing.status()).isEqualTo(VoucherOperation.Status.RECONCILING); assertThat(missing.highestRevision()).isEqualTo(3);
        assertThatThrownBy(() -> missing.retryNotFound(missing.updatedAt())).isInstanceOf(DomainException.class);
    }

    @Test
    void higherRevisionWithContradictoryStatusRetainsPriorPostedFactAcrossFurtherQueries() {
        var posted = posted(); var query = query(posted);
        var conflict = query.complete(success(fact(query, VoucherObservation.Status.PENDING, 3, query.updatedAt().plusSeconds(1), null)), query.updatedAt().plusSeconds(1));
        assertThat(conflict.status()).isEqualTo(VoucherOperation.Status.RECONCILING); assertThat(conflict.highestRevision()).isEqualTo(3);
        assertThat(conflict.observation()).isEqualTo(posted.observation()); assertThat(conflict.usablePosted()).isFalse();
        var requery = query(conflict); var still = requery.complete(success(fact(requery, VoucherObservation.Status.POSTED, 2, requery.updatedAt().plusSeconds(1), "voucher-1")), requery.updatedAt().plusSeconds(1));
        assertThat(still.highestRevision()).isEqualTo(3); assertThat(still.failure()).isEqualTo(VoucherOperation.Failure.STALE_OBSERVATION);
        var latest = query(still); var unresolved = latest.complete(success(fact(latest, VoucherObservation.Status.POSTED, 4, latest.updatedAt().plusSeconds(1), "voucher-1")), latest.updatedAt().plusSeconds(1));
        assertThat(unresolved.status()).isEqualTo(VoucherOperation.Status.RECONCILING); assertThat(unresolved.highestRevision()).isEqualTo(4);
        assertThat(unresolved.observation()).isEqualTo(posted.observation());
    }

    @Test
    void changedVoucherUnderSameRevisionIsAConflictAndReversalIsNotPosting() {
        var posted = posted(); var first = query(posted);
        var conflict = first.complete(success(fact(first, VoucherObservation.Status.POSTED, 2, first.updatedAt().plusSeconds(1), "other-voucher")), first.updatedAt().plusSeconds(1));
        assertThat(conflict.status()).isEqualTo(VoucherOperation.Status.RECONCILING);
        var query = query(posted); var reversed = query.complete(success(fact(query, VoucherObservation.Status.REVERSED, 3, query.updatedAt().plusSeconds(1), "voucher-1")), query.updatedAt().plusSeconds(1));
        assertThat(reversed.status()).isEqualTo(VoucherOperation.Status.REVERSED); assertThat(reversed.usablePosted()).isFalse();
        var requery = query(reversed); var contradictory = requery.complete(success(fact(requery, VoucherObservation.Status.POSTED, 4, requery.updatedAt().plusSeconds(1), "voucher-1")), requery.updatedAt().plusSeconds(1));
        assertThat(contradictory.status()).isEqualTo(VoucherOperation.Status.RECONCILING); assertThat(contradictory.observation()).isEqualTo(reversed.observation());
    }

    @Test
    void explicitFailureCannotFlipToPostedAndQueriesKeepRunningAfterCommandExpiry() {
        var first = queued().claim(NOW, LEASE); var failed = first.complete(success(fact(first, VoucherObservation.Status.FAILED, 1, NOW.plusSeconds(1), null)), NOW.plusSeconds(1));
        assertThat(failed.status()).isEqualTo(VoucherOperation.Status.FAILED);
        var query = failed.requestQuery(NOW.plusSeconds(100)).claim(NOW.plusSeconds(100), LEASE);
        assertThat(query.status()).isEqualTo(VoucherOperation.Status.QUERYING);
        var inconsistent = query.complete(success(fact(query, VoucherObservation.Status.POSTED, 2, query.updatedAt().plusSeconds(1), "voucher-1")), query.updatedAt().plusSeconds(1));
        assertThat(inconsistent.status()).isEqualTo(VoucherOperation.Status.RECONCILING); assertThat(inconsistent.observation()).isEqualTo(failed.observation());
    }

    @Test
    void transportErrorsKeepHighWaterEvidenceAndBackoffSurvivesClaims() {
        var first = queued().claim(NOW, LEASE); var pending = first.complete(success(fact(first, VoucherObservation.Status.PENDING, 1, NOW.plusSeconds(1), null)), NOW.plusSeconds(1));
        for (int index = 0; index < 10; index++) {
            var claim = pending.claim(pending.nextAttemptAt(), LEASE); pending = claim.unavailable(VoucherOperation.Failure.CONNECTION, claim.updatedAt().plusSeconds(1));
            assertThat(pending.highestRevision()).isEqualTo(1); assertThat(pending.observation().status()).isEqualTo(VoucherObservation.Status.PENDING);
            assertThat(Duration.between(pending.updatedAt(), pending.nextAttemptAt()).toSeconds()).isBetween(5L, 300L);
        }
        var current = pending;
        assertThatThrownBy(() -> current.claim(current.updatedAt().minusSeconds(1), LEASE)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> current.claim(current.nextAttemptAt(), Duration.ZERO)).isInstanceOf(DomainException.class);
    }

    @Test
    void persistedStateCannotRestorePreviouslySentOperationAsAnotherFirstPost() {
        var initial = queued();
        assertThatThrownBy(() -> new VoucherOperation(initial.input(), 3, VoucherOperation.Status.QUEUED, 1, NOW, NOW, NOW, null, null, null, 0, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new VoucherOperation(initial.input(), 4, VoucherOperation.Status.POSTING, 2, NOW, NOW, null, NOW.plus(LEASE), null, null, 0, null)).isInstanceOf(DomainException.class);
    }

    private static VoucherOperation queued() { return VoucherOperation.queue(new VoucherOperation.Input(VoucherCommandTest.advanceCommand(), "a".repeat(64)), NOW); }
    private static VoucherOperation posted() {
        var claim = queued().claim(NOW, LEASE); return claim.complete(success(fact(claim, VoucherObservation.Status.POSTED, 2, NOW.plusSeconds(1), "voucher-1")), NOW.plusSeconds(1));
    }
    private static VoucherOperation query(VoucherOperation previous) { var when = previous.updatedAt().plusSeconds(1); return previous.requestQuery(when).claim(when, LEASE); }
    private static FinanceResult.Success<VoucherObservation> success(VoucherObservation value) { return new FinanceResult.Success<>(value); }
    private static VoucherObservation fact(VoucherOperation operation, VoucherObservation.Status status, long revision, Instant at, String voucher) {
        var command = operation.input().command(); boolean posted = status == VoucherObservation.Status.POSTED || status == VoucherObservation.Status.REVERSED;
        return new VoucherObservation(command.id(), command.digest(), status, revision, at, status == VoucherObservation.Status.NOT_FOUND ? null : "posting-1", voucher,
                posted ? command.period().periodReference() : null, posted ? command.accountingDate() : null, posted ? command.totals().gross() : null,
                posted ? command.totals().gross() : null, posted ? NOW.plusSeconds(1) : null, status == VoucherObservation.Status.FAILED ? VoucherObservation.Failure.ACCOUNTING_PERIOD_CLOSED : null);
    }
}
