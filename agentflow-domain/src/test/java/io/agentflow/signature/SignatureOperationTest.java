package io.agentflow.signature;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static io.agentflow.signature.SignatureTestFixtures.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 覆盖签署副作用不重发、回执不可替换、领取恢复以及结果文件与原件分离。
 * @author owlzhangfq@gmail.com
 */
class SignatureOperationTest {
    private static final Duration LEASE = Duration.ofSeconds(15);

    @Test
    void sendsOnlyOnceAndNotFoundNeverAuthorizesResendingOrLocalCancellation() {
        var sent = queued().claim(NOW, LEASE);
        assertThat(sent.status()).isEqualTo(SignatureOperation.Status.SENDING);
        var current = sent.unavailable(SignatureOperation.Failure.TIMEOUT, NOW.plusSeconds(1));
        var firstUnknown = current;
        assertThatThrownBy(() -> firstUnknown.claim(NOW.plusSeconds(2), LEASE)).isInstanceOf(DomainException.class);
        for (int index = 0; index < 5; index++) {
            var query = current.claim(current.nextAttemptAt(), LEASE);
            assertThat(query.status()).isEqualTo(SignatureOperation.Status.QUERYING);
            current = query.complete(receipt(request(), SignatureReceipt.Status.NOT_FOUND, 0, query.updatedAt()), query.updatedAt().plusSeconds(1));
            assertThat(current.status()).isEqualTo(SignatureOperation.Status.UNKNOWN);
            assertThat(current.failure()).isEqualTo(SignatureOperation.Failure.NOT_FOUND);
            assertThat(current.input()).isSameAs(sent.input());
            var unchanged = current;
            assertThatThrownBy(() -> unchanged.cancelUnsent(unchanged.updatedAt())).isInstanceOf(DomainException.class);
        }
    }

    @Test
    void notFoundIsOnlyValidForQueriesAndCannotArriveAsCallback() {
        var sent = queued().claim(NOW, LEASE); var missing = receipt(request(), SignatureReceipt.Status.NOT_FOUND, 0, NOW);
        assertThat(sent.complete(missing, NOW.plusSeconds(1)).failure()).isEqualTo(SignatureOperation.Failure.INVALID_RESPONSE);
        assertThatThrownBy(() -> sent.receiveCallback(missing, NOW.plusSeconds(1))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> sent.unavailable(SignatureOperation.Failure.NOT_FOUND, NOW.plusSeconds(1))).isInstanceOf(DomainException.class);
    }

    @Test
    void authorizationExpiryStopsFirstSendButNeverStopsRecoveryOfAnAlreadyIssuedOperation() {
        var expired = queued().claim(request().authorization().validUntil(), LEASE);
        assertThat(expired.status()).isEqualTo(SignatureOperation.Status.EXPIRED);
        assertThat(expired.attempts()).isZero();
        assertThat(expired.terminal()).isTrue();
        var unknown = queued().claim(NOW, LEASE).unavailable(SignatureOperation.Failure.TIMEOUT, NOW.plusSeconds(1));
        var query = unknown.claim(NOW.plusSeconds(7200), LEASE);
        assertThat(query.status()).isEqualTo(SignatureOperation.Status.QUERYING);
        var collecting = query.complete(receipt(request(), SignatureReceipt.Status.SIGNED, 1, query.updatedAt()), query.updatedAt().plusSeconds(1));
        assertThat(collecting.status()).isEqualTo(SignatureOperation.Status.COLLECTING);
    }

    @Test
    void lostLeaseDiscardsLateWorkerResultAndOnlyQueriesTheOriginalOperation() {
        var sent = queued().claim(NOW, LEASE);
        var unknown = sent.complete(receipt(request(), SignatureReceipt.Status.SIGNED, 1, NOW.plusSeconds(1)), sent.leaseUntil());
        assertThat(unknown.failure()).isEqualTo(SignatureOperation.Failure.LEASE_EXPIRED);
        assertThat(unknown.receipt()).isNull();
        assertThat(unknown.artifacts()).isEmpty();
        assertThat(unknown.claim(unknown.nextAttemptAt(), LEASE).status()).isEqualTo(SignatureOperation.Status.QUERYING);
    }

    @Test
    void signedProviderFactOnlyCompletesAfterEveryResultIsStoredSeparatelyFromOriginals() {
        var collecting = collecting();
        assertThat(collecting.terminal()).isFalse();
        assertThat(collecting.artifacts()).hasSize(2);
        assertThat(collecting.artifacts()).extracting(SignatureOperation.StoredArtifact::contentId)
                .doesNotContainAnyElementsOf(request().documents().stream().map(SignatureRequest.Document::contentId).toList()).doesNotHaveDuplicates();
        var downloading = collecting.claim(collecting.nextAttemptAt(), LEASE);
        assertThat(downloading.status()).isEqualTo(SignatureOperation.Status.FETCHING_FILES);
        var verified = new ArrayList<>(downloading.artifacts()); Collections.reverse(verified);
        var done = downloading.completeFiles(verified, downloading.updatedAt().plusSeconds(1));
        assertThat(done.status()).isEqualTo(SignatureOperation.Status.SIGNED);
        assertThat(done.terminal()).isTrue();
        assertThat(done.input()).isSameAs(collecting.input());
        assertThat(done.artifacts()).isEqualTo(collecting.artifacts());
        assertThat(done.receipt()).isEqualTo(collecting.receipt());
        assertThatThrownBy(() -> done.claim(done.updatedAt(), LEASE)).isInstanceOf(DomainException.class);
    }

    @Test
    void incompleteCorruptOrReplacedFilesKeepTheSameReceiptAndReservedContentIds() {
        var collecting = collecting(); var downloading = collecting.claim(collecting.nextAttemptAt(), LEASE);
        var first = downloading.artifacts().get(0); var second = downloading.artifacts().get(1);
        var badResults = List.of(
                List.of(first), List.of(first, first),
                List.of(new SignatureOperation.StoredArtifact(first.documentId(), UUID.randomUUID(), first.size(), first.sha256()), second),
                List.of(new SignatureOperation.StoredArtifact(first.documentId(), first.contentId(), first.size() + 1, first.sha256()), second),
                List.of(new SignatureOperation.StoredArtifact(first.documentId(), first.contentId(), first.size(), "a".repeat(64)), second));
        for (var result : badResults) {
            var retry = downloading.completeFiles(result, downloading.updatedAt().plusSeconds(1));
            assertThat(retry.status()).isEqualTo(SignatureOperation.Status.COLLECTING);
            assertThat(retry.failure()).isEqualTo(SignatureOperation.Failure.ARTIFACT_MISMATCH);
            assertThat(retry.artifacts()).isEqualTo(collecting.artifacts());
            assertThat(retry.receipt()).isSameAs(collecting.receipt());
            assertThat(retry.claim(retry.nextAttemptAt(), LEASE).status()).isEqualTo(SignatureOperation.Status.FETCHING_FILES);
        }
    }

    @Test
    void downloadCrashAndLateCompletionPreserveFileReservationsInsteadOfQueryingOrSendingAgain() {
        var collecting = collecting(); var downloading = collecting.claim(collecting.nextAttemptAt(), LEASE);
        for (var retry : List.of(downloading.expire(downloading.leaseUntil()),
                downloading.completeFiles(downloading.artifacts(), downloading.leaseUntil()),
                downloading.unavailable(SignatureOperation.Failure.CONNECTION, downloading.updatedAt().plusSeconds(1)))) {
            assertThat(retry.status()).isEqualTo(SignatureOperation.Status.COLLECTING);
            assertThat(retry.receipt()).isSameAs(collecting.receipt());
            assertThat(retry.artifacts()).isEqualTo(collecting.artifacts());
            var reclaimed = retry.claim(retry.nextAttemptAt(), LEASE);
            assertThat(reclaimed.status()).isEqualTo(SignatureOperation.Status.FETCHING_FILES);
            assertThat(reclaimed.completeFiles(reclaimed.artifacts(), reclaimed.updatedAt().plusSeconds(1)).status()).isEqualTo(SignatureOperation.Status.SIGNED);
        }
    }

    @Test
    void callbacksCanFinishAnActiveClaimAndDuplicatesNeverResetFileReservationsOrLeases() {
        var sent = queued().claim(NOW, LEASE);
        var result = receipt(request(), SignatureReceipt.Status.SIGNED, 2, NOW.plusSeconds(1));
        var collecting = sent.receiveCallback(result, NOW.plusSeconds(2));
        assertThat(collecting.version()).isGreaterThan(sent.version());
        assertThat(collecting.leaseUntil()).isNull();
        assertThat(collecting.receiveCallback(result, NOW.plusSeconds(3))).isSameAs(collecting);
        var downloading = collecting.claim(collecting.nextAttemptAt(), LEASE);
        assertThat(downloading.receiveCallback(result, NOW.plusSeconds(4))).isSameAs(downloading);
        var done = downloading.completeFiles(downloading.artifacts(), NOW.plusSeconds(5));
        assertThat(done.receiveCallback(result, NOW.plusSeconds(6))).isSameAs(done);
        assertThatThrownBy(() -> queued().receiveCallback(result, NOW.plusSeconds(2))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> queued().cancelUnsent(NOW).receiveCallback(result, NOW.plusSeconds(2))).isInstanceOf(DomainException.class);
    }

    @Test
    void olderCallbacksDoNotRegressAcceptedTerminalFacts() {
        var collecting = collecting();
        var old = receipt(request(), SignatureReceipt.Status.PENDING, 1, NOW);
        assertThat(collecting.receiveCallback(old, NOW.plusSeconds(2))).isSameAs(collecting);
        var downloading = collecting.claim(collecting.nextAttemptAt(), LEASE);
        assertThat(downloading.receiveCallback(old, NOW.plusSeconds(2))).isSameAs(downloading);
    }

    @Test
    void sameRevisionConflictAndChangedFinalReceiptCannotReplaceSignedResults() {
        var collecting = collecting();
        var conflicting = new SignatureReceipt(request().id(), request().digest(), collecting.receipt().revision(), SignatureReceipt.Status.DECLINED,
                NOW.plusSeconds(1), collecting.receipt().providerReference(), NOW.plusSeconds(1), List.of());
        assertThatThrownBy(() -> collecting.receiveCallback(conflicting, NOW.plusSeconds(2))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> collecting.receiveCallback(receipt(request(), SignatureReceipt.Status.CANCELLED, 3, NOW.plusSeconds(2)), NOW.plusSeconds(2)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void pendingQueriesKeepBoundedBackoffAndPreserveKnownFactsAcrossMissingAndOlderResponses() {
        var known = receipt(request(), SignatureReceipt.Status.PENDING, 2, NOW.plusSeconds(1));
        var pending = queued().claim(NOW, LEASE).complete(known, NOW.plusSeconds(1));
        var query = pending.claim(pending.nextAttemptAt(), LEASE);
        var missing = query.complete(receipt(request(), SignatureReceipt.Status.NOT_FOUND, 0, query.updatedAt()), query.updatedAt().plusSeconds(1));
        assertThat(missing.receipt()).isSameAs(known);
        query = missing.claim(missing.nextAttemptAt(), LEASE);
        var stale = query.complete(receipt(request(), SignatureReceipt.Status.PENDING, 1, NOW), query.updatedAt().plusSeconds(1));
        assertThat(stale.failure()).isEqualTo(SignatureOperation.Failure.STALE_RESPONSE);
        assertThat(stale.receipt()).isSameAs(known);
        var current = stale;
        for (int index = 0; index < 12; index++) {
            var active = current.claim(current.nextAttemptAt(), LEASE);
            current = active.complete(known, active.updatedAt().plusSeconds(1));
            assertThat(current.status()).isEqualTo(SignatureOperation.Status.PENDING);
            assertThat(Duration.between(current.updatedAt(), current.nextAttemptAt()).toSeconds()).isBetween(5L, 300L);
        }
        assertThat(Duration.between(current.updatedAt(), current.nextAttemptAt())).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    void newerRevisionCannotChangeProviderIdentityOrMoveFactTimeBackwards() {
        var known = receipt(request(), SignatureReceipt.Status.PENDING, 2, NOW.plusSeconds(2));
        var pending = queued().claim(NOW, LEASE).complete(known, NOW.plusSeconds(2));
        var query = pending.claim(pending.nextAttemptAt(), LEASE);
        var otherProvider = new SignatureReceipt(request().id(), request().digest(), 3, SignatureReceipt.Status.PENDING, query.updatedAt(), "different-provider-reference", null, List.of());
        for (var bad : List.of(otherProvider, receipt(request(), SignatureReceipt.Status.PENDING, 3, NOW.plusSeconds(1)),
                receipt(request(), SignatureReceipt.Status.PENDING, 2, NOW.plusSeconds(3)))) {
            var failed = query.complete(bad, query.updatedAt().plusSeconds(1));
            assertThat(failed.failure()).isEqualTo(SignatureOperation.Failure.CONFLICTING_RECEIPT);
            assertThat(failed.receipt()).isSameAs(known);
        }
    }

    @Test
    void declineAndRemoteCancellationAreTerminalProviderFactsAndCannotBeRetried() {
        for (var outcome : List.of(SignatureReceipt.Status.DECLINED, SignatureReceipt.Status.CANCELLED)) {
            var sent = queued().claim(NOW, LEASE); var value = receipt(request(), outcome, 1, NOW.plusSeconds(1));
            var terminal = sent.complete(value, NOW.plusSeconds(1));
            assertThat(terminal.terminal()).isTrue();
            assertThat(terminal.status().name()).isEqualTo(outcome.name());
            assertThat(terminal.artifacts()).isEmpty();
            assertThat(terminal.receiveCallback(value, NOW.plusSeconds(2))).isSameAs(terminal);
            assertThatThrownBy(() -> terminal.claim(NOW.plusSeconds(2), LEASE)).isInstanceOf(DomainException.class);
        }
        var cancelled = queued().cancelUnsent(NOW.plusSeconds(1));
        assertThat(cancelled.attempts()).isZero();
        assertThat(cancelled.receipt()).isNull();
        assertThat(cancelled.terminal()).isTrue();
    }

    @Test
    void wrongAuthorizationAndFutureFactsRemainUnknownWithoutAdoptingTheirEvidence() {
        var sent = queued().claim(NOW, LEASE); var result = receipt(request(), SignatureReceipt.Status.SIGNED, 1, NOW);
        var wrongRequest = new SignatureReceipt(result.operationId(), "a".repeat(64), 1, result.status(), NOW, result.providerReference(), NOW, result.artifacts());
        for (var value : List.of(wrongRequest, receipt(request(), SignatureReceipt.Status.SIGNED, 1, NOW.plusSeconds(2)))) {
            var invalid = sent.complete(value, NOW.plusSeconds(1));
            assertThat(invalid.status()).isEqualTo(SignatureOperation.Status.UNKNOWN);
            assertThat(invalid.failure()).isEqualTo(SignatureOperation.Failure.INVALID_RESPONSE);
            assertThat(invalid.receipt()).isNull();
            assertThat(invalid.artifacts()).isEmpty();
        }
    }

    @Test
    void restorationRejectsResendStatesAndSignedArtifactsThatReplaceOriginalContent() {
        var input = queued().input();
        assertThatThrownBy(() -> new SignatureOperation(input, 4, SignatureOperation.Status.QUEUED, 1, NOW, NOW, null, null, null, List.of())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new SignatureOperation(input, 2, SignatureOperation.Status.UNKNOWN, 1, NOW, NOW, null, null, null, List.of())).isInstanceOf(DomainException.class);
        var collecting = collecting(); var first = collecting.artifacts().get(0);
        var overwrite = new SignatureOperation.StoredArtifact(first.documentId(), request().documents().get(1).contentId(), first.size(), first.sha256());
        assertThatThrownBy(() -> restoreFiles(collecting, List.of(overwrite, collecting.artifacts().get(1)))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> restoreFiles(collecting, List.of(first))).isInstanceOf(DomainException.class);
    }

    @Test
    void restorationCannotClaimThatTheFirstSendStartedAfterAuthorizationExpired() {
        var queued = queued(); var expiredAt = queued.input().request().authorization().validUntil();
        assertThatThrownBy(() -> new SignatureOperation(queued.input(), 2, SignatureOperation.Status.SENDING, 1,
                expiredAt, null, expiredAt.plus(LEASE), null, null, List.of())).isInstanceOf(DomainException.class);
    }

    @Test
    void clockReversalAndInvalidClaimsFailBeforeAnyTransition() {
        var queued = queued(); var sent = queued.claim(NOW, LEASE);
        for (var duration : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMinutes(6))) {
            assertThatThrownBy(() -> queued.claim(NOW, duration)).isInstanceOf(DomainException.class);
        }
        assertThatThrownBy(() -> queued.claim(NOW.minusSeconds(1), LEASE)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> sent.expire(NOW.plusSeconds(1))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> sent.complete(receipt(request(), SignatureReceipt.Status.PENDING, 1, NOW), NOW.minusSeconds(1))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> sent.unavailable(SignatureOperation.Failure.CONNECTION, NOW.minusSeconds(1))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> sent.unavailable(SignatureOperation.Failure.LEASE_EXPIRED, NOW.plusSeconds(1))).isInstanceOf(DomainException.class);
    }

    private SignatureOperation collecting() {
        return queued().claim(NOW, LEASE).complete(receipt(request(), SignatureReceipt.Status.SIGNED, 2, NOW.plusSeconds(1)), NOW.plusSeconds(1));
    }

    private SignatureOperation restoreFiles(SignatureOperation value, List<SignatureOperation.StoredArtifact> files) {
        return new SignatureOperation(value.input(), value.version(), value.status(), value.attempts(), value.updatedAt(), value.nextAttemptAt(), value.leaseUntil(), value.receipt(), value.failure(), files);
    }
}
