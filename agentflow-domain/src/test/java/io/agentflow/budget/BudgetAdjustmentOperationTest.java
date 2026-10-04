package io.agentflow.budget;

import io.agentflow.common.DomainException;
import io.agentflow.notification.BudgetAdjustmentNotice;
import io.agentflow.finance.FinanceResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static io.agentflow.budget.BudgetAdjustmentTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 预算执行恢复只处理原命令，不能以超时、查无或单端回执宣布调拨成功。
 * @author owlzhangfq@gmail.com
 */
class BudgetAdjustmentOperationTest {
    private static final Instant START = NOW.plusSeconds(2);
    private static final Duration LEASE = Duration.ofSeconds(15);

    @Test void noticesSeparateActualUncertaintyFromIntentionalQueryAndPreserveResultKinds() {
        var queued = queue(); var running = queued.claim(START, LEASE);
        assertThat(BudgetAdjustmentNotice.from(queued)).isEmpty(); assertThat(BudgetAdjustmentNotice.from(running)).isEmpty();
        var unknown = running.unavailable(BudgetAdjustmentOperation.Failure.TIMEOUT, START.plusSeconds(1));
        assertThat(BudgetAdjustmentNotice.from(unknown)).contains(BudgetAdjustmentNotice.UNKNOWN);
        var applied = running.complete(new FinanceResult.Success<>(applied(running.command(), 1, START.plusSeconds(1))), START.plusSeconds(1));
        assertThat(BudgetAdjustmentNotice.from(applied)).contains(BudgetAdjustmentNotice.APPLIED);
        assertThat(BudgetAdjustmentNotice.from(applied.requestQuery(START.plusSeconds(2)))).isEmpty();
        assertThat(BudgetAdjustmentNotice.from(queued.claim(queued.command().expiresAt(), LEASE))).contains(BudgetAdjustmentNotice.EXPIRED);
        assertThat(BudgetAdjustmentNotice.from(queued.voidBeforeSend(START))).contains(BudgetAdjustmentNotice.VOIDED);
    }
    @Test void notificationKeysCannotSwapReviewOperationOrNoncanonicalIdentifiers() {
        var id = java.util.UUID.fromString("abcdefab-abcd-abcd-abcd-abcdefabcdef");
        for (var notice : BudgetAdjustmentNotice.values()) assertThat(BudgetAdjustmentNotice.source(notice.eventKey(id))).contains(new BudgetAdjustmentNotice.Source(notice.sourceType(), id, notice));
        for (String key : List.of("budget:" + id + ":APPLIED", "budget-adjustment:REVIEW:" + id + ":APPLIED", "budget-adjustment:OPERATION:" + id + ":REVIEW_BLOCKED", "budget-adjustment:OPERATION:1-1-1-1-1:APPLIED", "budget-adjustment:OPERATION:" + id.toString().toUpperCase() + ":APPLIED")) assertThat(BudgetAdjustmentNotice.source(key)).isEmpty();
        assertThat(BudgetAdjustmentNotice.source(null)).isEmpty();
    }

    @Test void timeoutAndExpiredAuthorizationContinueOriginalReadOnlyRecovery() {
        var queued = queue(); var running = queued.claim(START, LEASE);
        var unknown = running.unavailable(BudgetAdjustmentOperation.Failure.TIMEOUT, START.plusSeconds(1));
        var afterExpiry = queued.command().expiresAt().plusSeconds(1);
        var query = unknown.claim(afterExpiry, LEASE);
        assertThat(query.status()).isEqualTo(BudgetAdjustmentOperation.Status.QUERYING); assertThat(query.command()).isEqualTo(queued.command());
        var done = query.complete(new FinanceResult.Success<>(applied(query.command(), 1, afterExpiry)), afterExpiry);
        assertThat(done.status()).isEqualTo(BudgetAdjustmentOperation.Status.APPLIED); assertThat(done.safelyUnexecuted()).isFalse();
        var expired = queued.claim(queued.command().expiresAt(), LEASE);
        assertThat(expired.status()).isEqualTo(BudgetAdjustmentOperation.Status.EXPIRED); assertThat(expired.safelyUnexecuted()).isTrue();
        assertThatThrownBy(() -> expired.requestQuery(afterExpiry)).isInstanceOf(DomainException.class);
    }

    @Test void authoritativeNotFoundRequiresExplicitRetryWithOriginalCommandAndStillCurrentEvidence() {
        var running = queue().claim(START, LEASE);
        var query = running.unavailable(BudgetAdjustmentOperation.Failure.CONNECTION, START.plusSeconds(1)).claim(START.plusSeconds(6), LEASE);
        var missing = query.complete(observed(query, BudgetAdjustmentObservation.Status.NOT_FOUND, 0, START.plusSeconds(7)), START.plusSeconds(7));
        assertThat(missing.status()).isEqualTo(BudgetAdjustmentOperation.Status.NOT_FOUND); assertThat(missing.safelyUnexecuted()).isFalse(); assertThat(missing.nextAttemptAt()).isNull();
        assertThatThrownBy(() -> missing.claim(START.plusSeconds(8), LEASE)).isInstanceOf(DomainException.class);
        var retry = missing.retryNotFound(START.plusSeconds(8)).claim(START.plusSeconds(8), LEASE);
        assertThat(retry.status()).isEqualTo(BudgetAdjustmentOperation.Status.EXECUTING); assertThat(retry.command()).isEqualTo(running.command());
        assertThatThrownBy(() -> missing.retryNotFound(missing.command().expiresAt())).isInstanceOf(DomainException.class);
        assertThat(retry.complete(observed(retry, BudgetAdjustmentObservation.Status.NOT_FOUND, 0, START.plusSeconds(9)), START.plusSeconds(9)).failure()).isEqualTo(BudgetAdjustmentOperation.Failure.INVALID_RESPONSE);
    }

    @Test void pendingCannotDisappearAndRepeatedQueriesCannotClearContradictoryEvidence() {
        var running = queue().claim(START, LEASE);
        var pending = running.complete(observed(running, BudgetAdjustmentObservation.Status.PENDING, 1, START.plusSeconds(1)), START.plusSeconds(1));
        var query = pending.claim(START.plusSeconds(6), LEASE);
        var disputed = query.complete(observed(query, BudgetAdjustmentObservation.Status.NOT_FOUND, 0, START.plusSeconds(7)), START.plusSeconds(7));
        assertThat(disputed.status()).isEqualTo(BudgetAdjustmentOperation.Status.RECONCILING); assertThat(disputed.observation()).isEqualTo(pending.observation());
        assertThat(disputed.safelyUnexecuted()).isFalse();
        var recheck = disputed.requestQuery(START.plusSeconds(8)).claim(START.plusSeconds(8), LEASE);
        assertThat(recheck.complete(new FinanceResult.Success<>(applied(recheck.command(), 2, START.plusSeconds(9))), START.plusSeconds(9)).status()).isEqualTo(BudgetAdjustmentOperation.Status.RECONCILING);
    }

    @Test void appliedReceiptAndRevisionAreStableAcrossLaterQueries() {
        var running = queue().claim(START, LEASE); var original = applied(running.command(), 2, START.plusSeconds(1));
        var done = running.complete(new FinanceResult.Success<>(original), START.plusSeconds(1));
        var query = done.requestQuery(START.plusSeconds(2)).claim(START.plusSeconds(2), LEASE);
        assertThat(query.complete(new FinanceResult.Success<>(applied(query.command(), 1, START.plusSeconds(3))), START.plusSeconds(3)).status()).isEqualTo(BudgetAdjustmentOperation.Status.RECONCILING);
        var another = new BudgetAdjustmentObservation(original.operationId(), original.commandDigest(), original.status(), 3, START.plusSeconds(3), "other-posting", original.appliedAt(), original.changes(), null);
        assertThat(query.complete(new FinanceResult.Success<>(another), START.plusSeconds(3)).status()).isEqualTo(BudgetAdjustmentOperation.Status.RECONCILING);
        assertThat(query.complete(new FinanceResult.Success<>(applied(query.command(), 2, START.plusSeconds(3))), START.plusSeconds(3)).status()).isEqualTo(BudgetAdjustmentOperation.Status.APPLIED);
    }

    @Test void equalRevisionCannotChangePendingIntoAnAppliedOutcome() {
        var running = queue().claim(START, LEASE);
        var pending = running.complete(observed(running, BudgetAdjustmentObservation.Status.PENDING, 1, START.plusSeconds(1)), START.plusSeconds(1));
        var query = pending.claim(START.plusSeconds(6), LEASE);
        assertThat(query.complete(new FinanceResult.Success<>(applied(query.command(), 1, START.plusSeconds(7))), START.plusSeconds(7)).status()).isEqualTo(BudgetAdjustmentOperation.Status.RECONCILING);
        assertThat(query.complete(new FinanceResult.Success<>(applied(query.command(), 2, START.plusSeconds(7))), START.plusSeconds(7)).status()).isEqualTo(BudgetAdjustmentOperation.Status.APPLIED);
    }

    @Test void latePartialOrGenericRejectedResponsesStayUnknownWithoutResending() {
        var running = queue().claim(START, LEASE); var full = applied(running.command(), 1, START.plusSeconds(1));
        var half = new BudgetAdjustmentObservation(full.operationId(), full.commandDigest(), full.status(), full.revision(), full.observedAt(), full.reference(), full.appliedAt(), List.of(full.changes().get(0)), null);
        assertThat(running.complete(new FinanceResult.Success<>(half), START.plusSeconds(1)).failure()).isEqualTo(BudgetAdjustmentOperation.Failure.INVALID_RESPONSE);
        assertThat(running.complete(new FinanceResult.Rejected<>(FinanceResult.Reason.BUDGET_INSUFFICIENT), START.plusSeconds(1)).status()).isEqualTo(BudgetAdjustmentOperation.Status.UNKNOWN);
        var expired = running.complete(new FinanceResult.Success<>(full), START.plusSeconds(15));
        assertThat(expired.failure()).isEqualTo(BudgetAdjustmentOperation.Failure.LEASE_EXPIRED);
        assertThat(expired.claim(START.plusSeconds(15), LEASE).status()).isEqualTo(BudgetAdjustmentOperation.Status.QUERYING);
    }

    @Test void rejectionIsSafeOnlyWithBoundReceiptAndSnapshotsCannotInventAnUnsentHistory() {
        var queued = queue(); var running = queued.claim(START, LEASE);
        var rejected = new BudgetAdjustmentObservation(running.command().id(), running.command().digest(), BudgetAdjustmentObservation.Status.REJECTED, 1,
                START.plusSeconds(1), null, null, List.of(), BudgetAdjustmentObservation.Rejection.LEDGER_VERSION_CONFLICT);
        assertThat(running.complete(new FinanceResult.Success<>(rejected), START.plusSeconds(1)).safelyUnexecuted()).isTrue();
        assertThat(queued.voidBeforeSend(START).safelyUnexecuted()).isTrue();
        assertThatThrownBy(() -> running.voidBeforeSend(START)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new BudgetAdjustmentOperation(queued.command(), 2, BudgetAdjustmentOperation.Status.UNKNOWN, 0, START, START, START, null, null, null, BudgetAdjustmentOperation.Failure.TIMEOUT)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new BudgetAdjustmentOperation(queued.command(), 2, BudgetAdjustmentOperation.Status.QUEUED, 1, START, START, START, null, null, null, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> queued.claim(START, Duration.ZERO)).isInstanceOf(DomainException.class);
    }

    private BudgetAdjustmentOperation queue() { return BudgetAdjustmentOperation.queue(command(), START); }
    private FinanceResult<BudgetAdjustmentObservation> observed(BudgetAdjustmentOperation operation, BudgetAdjustmentObservation.Status status, long revision, Instant at) {
        return new FinanceResult.Success<>(new BudgetAdjustmentObservation(operation.command().id(), operation.command().digest(), status, revision, at, null, null, List.of(), null));
    }
}
