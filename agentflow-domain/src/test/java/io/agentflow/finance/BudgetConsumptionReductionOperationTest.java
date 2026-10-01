package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.expense.CostAllocation;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 独立差额恢复不重新付款、不改预算原消费，查无、未知和明确完成各自处理。
 * @author owlzhangfq@gmail.com
 */
class BudgetConsumptionReductionOperationTest {
    private static final Instant NOW = Instant.parse("2026-09-29T16:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(15);
    private static final LocalDate DATE = LocalDate.of(2026, 9, 29);

    @Test void expiredSendingAuthorizationDoesNotPreventUnknownResultQuery() {
        var queued = queue(); var running = queued.claim(NOW, LEASE);
        var unknown = running.unavailable(BudgetConsumptionReductionOperation.Failure.TIMEOUT, NOW.plusSeconds(1));
        var query = unknown.claim(NOW.plusSeconds(180), LEASE);
        assertThat(query.status()).isEqualTo(BudgetConsumptionReductionOperation.Status.QUERYING); assertThat(query.input()).isEqualTo(queued.input());
        var applied = query.complete(applied(query, NOW.plusSeconds(181), "reversal"), NOW.plusSeconds(181));
        assertThat(applied.status()).isEqualTo(BudgetConsumptionReductionOperation.Status.APPLIED);
        assertThat(applied.input().command().source().action()).isEqualTo(BudgetCommand.Action.CONSUME);
        assertThatThrownBy(() -> applied.claim(NOW.plusSeconds(182), LEASE)).isInstanceOf(DomainException.class);
        assertThat(queued.claim(NOW.plusSeconds(120), LEASE).status()).isEqualTo(BudgetConsumptionReductionOperation.Status.EXPIRED);
    }

    @Test void authoritativeNotFoundWaitsForExplicitRetryOfTheExactCommand() {
        var running = queue().claim(NOW, LEASE);
        var query = running.unavailable(BudgetConsumptionReductionOperation.Failure.CONNECTION, NOW.plusSeconds(1)).claim(NOW.plusSeconds(6), LEASE);
        var missing = query.complete(observed(query, BudgetConsumptionReductionObservation.Status.NOT_FOUND, NOW.plusSeconds(7)), NOW.plusSeconds(7));
        assertThat(missing.status()).isEqualTo(BudgetConsumptionReductionOperation.Status.NOT_FOUND); assertThat(missing.nextAttemptAt()).isNull();
        assertThatThrownBy(() -> missing.claim(NOW.plusSeconds(8), LEASE)).isInstanceOf(DomainException.class);
        var retry = missing.retryNotFound(NOW.plusSeconds(8)).claim(NOW.plusSeconds(8), LEASE);
        assertThat(retry.status()).isEqualTo(BudgetConsumptionReductionOperation.Status.EXECUTING); assertThat(retry.input()).isEqualTo(running.input());
        assertThatThrownBy(() -> missing.retryNotFound(NOW.plusSeconds(120))).isInstanceOf(DomainException.class);
        assertThat(retry.complete(observed(retry, BudgetConsumptionReductionObservation.Status.NOT_FOUND, NOW.plusSeconds(9)), NOW.plusSeconds(9)).failure())
                .isEqualTo(BudgetConsumptionReductionOperation.Failure.INVALID_RESPONSE);
    }

    @Test void observedPendingCannotDisappearAndOrdinaryQueriesCannotClearConflict() {
        var running = queue().claim(NOW, LEASE);
        var pending = running.complete(observed(running, BudgetConsumptionReductionObservation.Status.PENDING, NOW.plusSeconds(1)), NOW.plusSeconds(1));
        var query = pending.claim(NOW.plusSeconds(6), LEASE);
        var conflicting = query.complete(observed(query, BudgetConsumptionReductionObservation.Status.NOT_FOUND, NOW.plusSeconds(7)), NOW.plusSeconds(7));
        assertThat(conflicting.status()).isEqualTo(BudgetConsumptionReductionOperation.Status.RECONCILING);
        assertThat(conflicting.observation()).isEqualTo(pending.observation()); assertThat(conflicting.conflictingObservation().status()).isEqualTo(BudgetConsumptionReductionObservation.Status.NOT_FOUND);
        assertThatThrownBy(() -> conflicting.retryNotFound(NOW.plusSeconds(8))).isInstanceOf(DomainException.class);
        var recheck = conflicting.requestQuery(NOW.plusSeconds(8)).claim(NOW.plusSeconds(8), LEASE);
        assertThat(recheck.complete(applied(recheck, NOW.plusSeconds(9), "reversal"), NOW.plusSeconds(9)).status()).isEqualTo(BudgetConsumptionReductionOperation.Status.RECONCILING);
    }

    @Test void appliedEvidenceIsImmutableAcrossSubsequentExplicitQueries() {
        var running = queue().claim(NOW, LEASE); var done = running.complete(applied(running, NOW.plusSeconds(1), "original-reversal"), NOW.plusSeconds(1));
        var query = done.requestQuery(NOW.plusSeconds(2)).claim(NOW.plusSeconds(2), LEASE);
        var changed = query.complete(applied(query, NOW.plusSeconds(3), "other-reversal"), NOW.plusSeconds(3));
        assertThat(changed.status()).isEqualTo(BudgetConsumptionReductionOperation.Status.RECONCILING);
        assertThat(changed.observation()).isEqualTo(done.observation()); assertThat(changed.conflictingObservation().posting().reference()).isEqualTo("other-reversal");
        var valid = done.requestQuery(NOW.plusSeconds(4)).claim(NOW.plusSeconds(4), LEASE);
        assertThat(valid.complete(applied(valid, NOW.plusSeconds(5), "original-reversal"), NOW.plusSeconds(5)).status()).isEqualTo(BudgetConsumptionReductionOperation.Status.APPLIED);
    }

    @Test void lateCompletionAndInvalidTransportCannotReleaseResourcesOrResend() {
        var running = queue().claim(NOW, LEASE);
        var expired = running.complete(applied(running, NOW.plusSeconds(15), "reversal"), NOW.plusSeconds(15));
        assertThat(expired.status()).isEqualTo(BudgetConsumptionReductionOperation.Status.UNKNOWN);
        assertThat(expired.failure()).isEqualTo(BudgetConsumptionReductionOperation.Failure.LEASE_EXPIRED);
        assertThat(expired.claim(NOW.plusSeconds(15), LEASE).status()).isEqualTo(BudgetConsumptionReductionOperation.Status.QUERYING);
        var invalid = running.complete(new FinanceResult.Rejected<>(FinanceResult.Reason.BUDGET_INSUFFICIENT), NOW.plusSeconds(1));
        assertThat(invalid.status()).isEqualTo(BudgetConsumptionReductionOperation.Status.UNKNOWN); assertThat(invalid.failure()).isEqualTo(BudgetConsumptionReductionOperation.Failure.INVALID_RESPONSE);
        assertThatThrownBy(() -> running.complete(applied(running, NOW, "reversal"), NOW.minusSeconds(1))).isInstanceOf(DomainException.class);
    }

    @Test void sourceRevocationOnlyStopsUnsentQueueAndSnapshotsCannotInventUnsentHistory() {
        var queued = queue(); var voided = queued.voidBeforeSend(NOW);
        assertThat(voided.status()).isEqualTo(BudgetConsumptionReductionOperation.Status.VOIDED); assertThat(voided.input()).isEqualTo(queued.input());
        assertThatThrownBy(() -> voided.claim(NOW, LEASE)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> queued.claim(NOW, LEASE).voidBeforeSend(NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new BudgetConsumptionReductionOperation(queued.input(), 2, queued.status(), 1, NOW, NOW, NOW, null, null, null, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> queued.claim(NOW, Duration.ZERO)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new BudgetConsumptionReductionOperation(queued.input(), 2, BudgetConsumptionReductionOperation.Status.APPLIED,
                0, NOW, NOW, null, null, applied(queued, NOW, "reversal").requireValue(), null, null)).isInstanceOf(DomainException.class);
    }

    @Test void safeRetirementRequiresNoSendOrAnUnambiguousFinalRejection() {
        var queued = queue();
        assertThat(queued.safelyUnexecuted()).isTrue();
        assertThat(queued.voidBeforeSend(NOW).safelyUnexecuted()).isTrue();
        assertThat(queued.claim(NOW.plusSeconds(120), LEASE).safelyUnexecuted()).isTrue();
        var running = queued.claim(NOW, LEASE);
        assertThat(running.safelyUnexecuted()).isFalse();
        var unknown = running.unavailable(BudgetConsumptionReductionOperation.Failure.TIMEOUT, NOW.plusSeconds(1));
        assertThat(unknown.safelyUnexecuted()).isFalse();
        var query = unknown.claim(NOW.plusSeconds(6), LEASE);
        assertThat(query.complete(observed(query, BudgetConsumptionReductionObservation.Status.NOT_FOUND, NOW.plusSeconds(7)), NOW.plusSeconds(7)).safelyUnexecuted()).isFalse();
        var command = running.input().command();
        var rejected = new BudgetConsumptionReductionObservation(command.id(), command.adjustmentId(), command.digest(), BudgetConsumptionReductionObservation.Status.REJECTED,
                NOW.plusSeconds(1), null, BudgetConsumptionReductionObservation.Rejection.LEDGER_VERSION_CONFLICT);
        var failed = running.complete(new FinanceResult.Success<>(rejected), NOW.plusSeconds(1));
        assertThat(failed.safelyUnexecuted()).isTrue();
        var changed = failed.requestQuery(NOW.plusSeconds(2)).claim(NOW.plusSeconds(2), LEASE)
                .complete(applied(failed, NOW.plusSeconds(3), "unexpected-applied"), NOW.plusSeconds(3));
        assertThat(changed.status()).isEqualTo(BudgetConsumptionReductionOperation.Status.RECONCILING);
        assertThat(changed.safelyUnexecuted()).isFalse();
    }

    @Test void disputeAcceptsOnlyExplicitFreshTerminalEvidenceEvenAfterSendExpiry() {
        var running = queue().claim(NOW, LEASE);
        var pending = running.complete(observed(running, BudgetConsumptionReductionObservation.Status.PENDING, NOW.plusSeconds(1)), NOW.plusSeconds(1));
        var missingQuery = pending.claim(NOW.plusSeconds(6), LEASE);
        var disputed = missingQuery.complete(observed(missingQuery, BudgetConsumptionReductionObservation.Status.NOT_FOUND, NOW.plusSeconds(7)), NOW.plusSeconds(7));
        var empty = new BudgetConsumptionReductionOperation.ResolutionHistory(null, false, NOW.plusSeconds(7));
        assertThat(disputed.resolutionIssue(empty, NOW.plusSeconds(8))).isEqualTo(BudgetConsumptionReductionOperation.ResolutionIssue.NON_TERMINAL);
        var query = disputed.requestQuery(NOW.plusSeconds(400)).claim(NOW.plusSeconds(400), LEASE);
        var candidate = query.complete(applied(query, NOW.plusSeconds(401), "actual-reduction"), NOW.plusSeconds(401));
        var resolved = candidate.resolveDispute(BudgetConsumptionReductionObservation.Status.APPLIED, empty, NOW.plusSeconds(402));
        assertThat(resolved.status()).isEqualTo(BudgetConsumptionReductionOperation.Status.APPLIED);
        assertThat(resolved.input()).isEqualTo(running.input()); assertThat(resolved.attempts()).isEqualTo(candidate.attempts());
        assertThat(candidate.acceptsSuccessor(resolved)).isFalse(); assertThat(resolved.conflictingObservation()).isNull();
        assertThat(candidate.resolutionIssue(empty, NOW.plusSeconds(701))).isEqualTo(BudgetConsumptionReductionOperation.ResolutionIssue.EXPIRED_EVIDENCE);
        assertThatThrownBy(() -> candidate.resolveDispute(BudgetConsumptionReductionObservation.Status.REJECTED, empty, NOW.plusSeconds(402))).isInstanceOf(DomainException.class);
    }

    @Test void disputeKeepsOriginalAppliedPostingAndRejectsLaterNoEffect() {
        var running = queue().claim(NOW, LEASE); var applied = running.complete(applied(running, NOW.plusSeconds(1), "original"), NOW.plusSeconds(1));
        var query = applied.requestQuery(NOW.plusSeconds(2)).claim(NOW.plusSeconds(2), LEASE);
        var rejected = query.complete(rejected(query, NOW.plusSeconds(3), BudgetConsumptionReductionObservation.Rejection.LEDGER_VERSION_CONFLICT), NOW.plusSeconds(3));
        var history = new BudgetConsumptionReductionOperation.ResolutionHistory(applied.observation(), true, NOW.plusSeconds(1));
        assertThat(rejected.resolutionIssue(history, NOW.plusSeconds(4))).isEqualTo(BudgetConsumptionReductionOperation.ResolutionIssue.EFFECT_ALREADY_OBSERVED);
        var other = query.complete(applied(query, NOW.plusSeconds(3), "other"), NOW.plusSeconds(3));
        assertThat(other.resolutionIssue(history, NOW.plusSeconds(4))).isEqualTo(BudgetConsumptionReductionOperation.ResolutionIssue.DIFFERENT_POSTING);
        var requery = other.requestQuery(NOW.plusSeconds(5)).claim(NOW.plusSeconds(5), LEASE);
        var restored = requery.complete(applied(requery, NOW.plusSeconds(6), "original"), NOW.plusSeconds(6));
        assertThat(restored.resolveDispute(BudgetConsumptionReductionObservation.Status.APPLIED, history, NOW.plusSeconds(7)).observation().posting()).isEqualTo(applied.observation().posting());
    }

    @Test void disputeCannotEraseAnEarlierConflictingSuccessOrUseOlderCandidate() {
        var running = queue().claim(NOW, LEASE);
        var failed = running.complete(rejected(running, NOW.plusSeconds(1), BudgetConsumptionReductionObservation.Rejection.LEDGER_VERSION_CONFLICT), NOW.plusSeconds(1));
        var query = failed.requestQuery(NOW.plusSeconds(2)).claim(NOW.plusSeconds(2), LEASE);
        var seen = query.complete(applied(query, NOW.plusSeconds(3), "observed-effect"), NOW.plusSeconds(3));
        var laterQuery = seen.requestQuery(NOW.plusSeconds(4)).claim(NOW.plusSeconds(4), LEASE);
        var later = laterQuery.complete(rejected(laterQuery, NOW.plusSeconds(5), BudgetConsumptionReductionObservation.Rejection.ACCOUNTING_PERIOD_CLOSED), NOW.plusSeconds(5));
        var history = new BudgetConsumptionReductionOperation.ResolutionHistory(null, true, NOW.plusSeconds(3));
        assertThat(later.resolutionIssue(history, NOW.plusSeconds(6))).isEqualTo(BudgetConsumptionReductionOperation.ResolutionIssue.EFFECT_ALREADY_OBSERVED);
        var older = laterQuery.complete(applied(laterQuery, NOW.plusSeconds(2), "observed-effect"), NOW.plusSeconds(5));
        assertThat(older.resolutionIssue(history, NOW.plusSeconds(6))).isEqualTo(BudgetConsumptionReductionOperation.ResolutionIssue.STALE_EVIDENCE);
    }

    @Test void disputeCanAdoptTerminalRejectionOnlyWithoutAnyHistoricalEffect() {
        var running = queue().claim(NOW, LEASE);
        var failed = running.complete(rejected(running, NOW.plusSeconds(1), BudgetConsumptionReductionObservation.Rejection.ACCOUNTING_PERIOD_CLOSED), NOW.plusSeconds(1));
        var query = failed.requestQuery(NOW.plusSeconds(2)).claim(NOW.plusSeconds(2), LEASE);
        var candidate = query.complete(rejected(query, NOW.plusSeconds(3), BudgetConsumptionReductionObservation.Rejection.BUDGET_POLICY_UNAVAILABLE), NOW.plusSeconds(3));
        var history = new BudgetConsumptionReductionOperation.ResolutionHistory(null, false, NOW.plusSeconds(1));
        var resolved = candidate.resolveDispute(BudgetConsumptionReductionObservation.Status.REJECTED, history, NOW.plusSeconds(4));
        assertThat(resolved.safelyUnexecuted()).isTrue(); assertThat(candidate.acceptsSuccessor(resolved)).isFalse();
        var ambiguous = query.complete(rejected(query, NOW.plusSeconds(3), BudgetConsumptionReductionObservation.Rejection.CONSUMPTION_ALREADY_REVERSED), NOW.plusSeconds(3));
        assertThat(ambiguous.resolutionIssue(history, NOW.plusSeconds(4))).isEqualTo(BudgetConsumptionReductionOperation.ResolutionIssue.EFFECT_ALREADY_OBSERVED);
        var foreign = queue(); var foreignEvidence = applied(foreign, NOW.plusSeconds(1), "foreign").requireValue();
        assertThat(candidate.resolutionIssue(new BudgetConsumptionReductionOperation.ResolutionHistory(foreignEvidence, true, NOW.plusSeconds(1)), NOW.plusSeconds(4)))
                .isEqualTo(BudgetConsumptionReductionOperation.ResolutionIssue.HISTORY_CHANGED);
    }

    private FinanceResult<BudgetConsumptionReductionObservation> rejected(BudgetConsumptionReductionOperation operation, Instant at, BudgetConsumptionReductionObservation.Rejection reason) {
        var command = operation.input().command();
        return new FinanceResult.Success<>(new BudgetConsumptionReductionObservation(command.id(), command.adjustmentId(), command.digest(),
                BudgetConsumptionReductionObservation.Status.REJECTED, at, null, reason));
    }

    private BudgetConsumptionReductionOperation queue() {
        var entity = UUID.randomUUID();
        var position = new BudgetPrecheckPort.Request(UUID.randomUUID(), 1, 2, "alice", entity, "CNY", DATE.minusDays(1),
                List.of(new BudgetPrecheckPort.Allocation(1, 1, "OFFICE", new CostAllocation("IT", null, new Money(new BigDecimal("100"), "CNY")))));
        var source = new BudgetCommand(UUID.randomUUID(), "demo", BudgetCommand.Action.CONSUME, position, new BudgetCommand.Expected(1, "frozen"));
        var consumed = new BudgetObservation(source.id(), source.digest(), BudgetObservation.Status.APPLIED, 2L, "consumed", NOW.minusSeconds(10), null);
        var period = new AccountingPeriodPort.OpenPeriod(new AccountingPeriodPort.Request(entity, "CNY", DATE), "period", "v1", DATE.minusDays(1), DATE.plusDays(1), NOW.minusSeconds(1), NOW.plusSeconds(300));
        var command = new BudgetConsumptionReductionCommand(UUID.randomUUID(), UUID.randomUUID(), source, consumed, null, position.allocations(),
                List.of(new BudgetPrecheckPort.Allocation(1, 1, "OFFICE", new CostAllocation("IT", null, new Money(new BigDecimal("80"), "CNY")))),
                period, "finance", "material", "reason", NOW, NOW.plusSeconds(120));
        return BudgetConsumptionReductionOperation.queue(new BudgetConsumptionReductionOperation.Input(3, command, "a".repeat(64)), NOW);
    }
    private FinanceResult<BudgetConsumptionReductionObservation> applied(BudgetConsumptionReductionOperation op, Instant at, String reference) {
        var command = op.input().command();
        var posting = new BudgetConsumptionReductionObservation.Posting(command.source().id(), command.source().digest(), command.consumed().reference(), 3,
                reference, command.beforeDigest(), command.afterDigest(), command.reducedAmount(), "period", DATE, NOW);
        return new FinanceResult.Success<>(new BudgetConsumptionReductionObservation(command.id(), command.adjustmentId(), command.digest(),
                BudgetConsumptionReductionObservation.Status.APPLIED, at, posting, null));
    }
    private FinanceResult<BudgetConsumptionReductionObservation> observed(BudgetConsumptionReductionOperation op, BudgetConsumptionReductionObservation.Status status, Instant at) {
        var command = op.input().command();
        return new FinanceResult.Success<>(new BudgetConsumptionReductionObservation(command.id(), command.adjustmentId(), command.digest(), status, at, null, null));
    }
}
