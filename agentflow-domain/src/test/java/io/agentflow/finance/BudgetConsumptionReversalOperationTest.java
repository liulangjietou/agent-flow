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
 * 冲正恢复不重新付款、不改预算原消费，查无、未知和明确完成各自处理。
 * @author owlzhangfq@gmail.com
 */
class BudgetConsumptionReversalOperationTest {
    private static final Instant NOW = Instant.parse("2026-09-29T16:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(15);
    private static final LocalDate DATE = LocalDate.of(2026, 9, 29);

    @Test void expiredSendingAuthorizationDoesNotPreventUnknownResultQuery() {
        var queued = queue(); var running = queued.claim(NOW, LEASE);
        var unknown = running.unavailable(BudgetConsumptionReversalOperation.Failure.TIMEOUT, NOW.plusSeconds(1));
        var query = unknown.claim(NOW.plusSeconds(180), LEASE);
        assertThat(query.status()).isEqualTo(BudgetConsumptionReversalOperation.Status.QUERYING); assertThat(query.input()).isEqualTo(queued.input());
        var applied = query.complete(applied(query, NOW.plusSeconds(181), "reversal"), NOW.plusSeconds(181));
        assertThat(applied.status()).isEqualTo(BudgetConsumptionReversalOperation.Status.APPLIED);
        assertThat(applied.input().command().source().action()).isEqualTo(BudgetCommand.Action.CONSUME);
        assertThatThrownBy(() -> applied.claim(NOW.plusSeconds(182), LEASE)).isInstanceOf(DomainException.class);
        assertThat(queued.claim(NOW.plusSeconds(120), LEASE).status()).isEqualTo(BudgetConsumptionReversalOperation.Status.EXPIRED);
    }

    @Test void authoritativeNotFoundWaitsForExplicitRetryOfTheExactCommand() {
        var running = queue().claim(NOW, LEASE);
        var query = running.unavailable(BudgetConsumptionReversalOperation.Failure.CONNECTION, NOW.plusSeconds(1)).claim(NOW.plusSeconds(6), LEASE);
        var missing = query.complete(observed(query, BudgetConsumptionReversalObservation.Status.NOT_FOUND, NOW.plusSeconds(7)), NOW.plusSeconds(7));
        assertThat(missing.status()).isEqualTo(BudgetConsumptionReversalOperation.Status.NOT_FOUND); assertThat(missing.nextAttemptAt()).isNull();
        assertThatThrownBy(() -> missing.claim(NOW.plusSeconds(8), LEASE)).isInstanceOf(DomainException.class);
        var retry = missing.retryNotFound(NOW.plusSeconds(8)).claim(NOW.plusSeconds(8), LEASE);
        assertThat(retry.status()).isEqualTo(BudgetConsumptionReversalOperation.Status.EXECUTING); assertThat(retry.input()).isEqualTo(running.input());
        assertThatThrownBy(() -> missing.retryNotFound(NOW.plusSeconds(120))).isInstanceOf(DomainException.class);
        assertThat(retry.complete(observed(retry, BudgetConsumptionReversalObservation.Status.NOT_FOUND, NOW.plusSeconds(9)), NOW.plusSeconds(9)).failure())
                .isEqualTo(BudgetConsumptionReversalOperation.Failure.INVALID_RESPONSE);
    }

    @Test void observedPendingCannotDisappearAndOrdinaryQueriesCannotClearConflict() {
        var running = queue().claim(NOW, LEASE);
        var pending = running.complete(observed(running, BudgetConsumptionReversalObservation.Status.PENDING, NOW.plusSeconds(1)), NOW.plusSeconds(1));
        var query = pending.claim(NOW.plusSeconds(6), LEASE);
        var conflicting = query.complete(observed(query, BudgetConsumptionReversalObservation.Status.NOT_FOUND, NOW.plusSeconds(7)), NOW.plusSeconds(7));
        assertThat(conflicting.status()).isEqualTo(BudgetConsumptionReversalOperation.Status.RECONCILING);
        assertThat(conflicting.observation()).isEqualTo(pending.observation()); assertThat(conflicting.conflictingObservation().status()).isEqualTo(BudgetConsumptionReversalObservation.Status.NOT_FOUND);
        assertThatThrownBy(() -> conflicting.retryNotFound(NOW.plusSeconds(8))).isInstanceOf(DomainException.class);
        var recheck = conflicting.requestQuery(NOW.plusSeconds(8)).claim(NOW.plusSeconds(8), LEASE);
        assertThat(recheck.complete(applied(recheck, NOW.plusSeconds(9), "reversal"), NOW.plusSeconds(9)).status()).isEqualTo(BudgetConsumptionReversalOperation.Status.RECONCILING);
    }

    @Test void appliedEvidenceIsImmutableAcrossSubsequentExplicitQueries() {
        var running = queue().claim(NOW, LEASE); var done = running.complete(applied(running, NOW.plusSeconds(1), "original-reversal"), NOW.plusSeconds(1));
        var query = done.requestQuery(NOW.plusSeconds(2)).claim(NOW.plusSeconds(2), LEASE);
        var changed = query.complete(applied(query, NOW.plusSeconds(3), "other-reversal"), NOW.plusSeconds(3));
        assertThat(changed.status()).isEqualTo(BudgetConsumptionReversalOperation.Status.RECONCILING);
        assertThat(changed.observation()).isEqualTo(done.observation()); assertThat(changed.conflictingObservation().reference()).isEqualTo("other-reversal");
        var valid = done.requestQuery(NOW.plusSeconds(4)).claim(NOW.plusSeconds(4), LEASE);
        assertThat(valid.complete(applied(valid, NOW.plusSeconds(5), "original-reversal"), NOW.plusSeconds(5)).status()).isEqualTo(BudgetConsumptionReversalOperation.Status.APPLIED);
    }

    @Test void lateCompletionAndInvalidTransportCannotReleaseResourcesOrResend() {
        var running = queue().claim(NOW, LEASE);
        var expired = running.complete(applied(running, NOW.plusSeconds(15), "reversal"), NOW.plusSeconds(15));
        assertThat(expired.status()).isEqualTo(BudgetConsumptionReversalOperation.Status.UNKNOWN);
        assertThat(expired.failure()).isEqualTo(BudgetConsumptionReversalOperation.Failure.LEASE_EXPIRED);
        assertThat(expired.claim(NOW.plusSeconds(15), LEASE).status()).isEqualTo(BudgetConsumptionReversalOperation.Status.QUERYING);
        var invalid = running.complete(new FinanceResult.Rejected<>(FinanceResult.Reason.BUDGET_INSUFFICIENT), NOW.plusSeconds(1));
        assertThat(invalid.status()).isEqualTo(BudgetConsumptionReversalOperation.Status.UNKNOWN); assertThat(invalid.failure()).isEqualTo(BudgetConsumptionReversalOperation.Failure.INVALID_RESPONSE);
        assertThatThrownBy(() -> running.complete(applied(running, NOW, "reversal"), NOW.minusSeconds(1))).isInstanceOf(DomainException.class);
    }

    @Test void sourceRevocationOnlyStopsUnsentQueueAndSnapshotsCannotInventUnsentHistory() {
        var queued = queue(); var voided = queued.voidBeforeSend(NOW);
        assertThat(voided.status()).isEqualTo(BudgetConsumptionReversalOperation.Status.VOIDED); assertThat(voided.input()).isEqualTo(queued.input());
        assertThatThrownBy(() -> voided.claim(NOW, LEASE)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> queued.claim(NOW, LEASE).voidBeforeSend(NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new BudgetConsumptionReversalOperation(queued.input(), 2, queued.status(), 1, NOW, NOW, NOW, null, null, null, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> queued.claim(NOW, Duration.ZERO)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new BudgetConsumptionReversalOperation(queued.input(), 2, BudgetConsumptionReversalOperation.Status.APPLIED,
                0, NOW, NOW, null, null, applied(queued, NOW, "reversal").requireValue(), null, null)).isInstanceOf(DomainException.class);
    }

    private BudgetConsumptionReversalOperation queue() {
        var entity = UUID.randomUUID();
        var position = new BudgetPrecheckPort.Request(UUID.randomUUID(), 1, 2, "alice", entity, "CNY", DATE.minusDays(1),
                List.of(new BudgetPrecheckPort.Allocation(1, 1, "OFFICE", new CostAllocation("IT", null, new Money(new BigDecimal("100"), "CNY")))));
        var source = new BudgetCommand(UUID.randomUUID(), "demo", BudgetCommand.Action.CONSUME, position, new BudgetCommand.Expected(1, "frozen"));
        var consumed = new BudgetObservation(source.id(), source.digest(), BudgetObservation.Status.APPLIED, 2L, "consumed", NOW.minusSeconds(10), null);
        var period = new AccountingPeriodPort.OpenPeriod(new AccountingPeriodPort.Request(entity, "CNY", DATE), "period", "v1", DATE.minusDays(1), DATE.plusDays(1), NOW.minusSeconds(1), NOW.plusSeconds(300));
        var command = new BudgetConsumptionReversalCommand(UUID.randomUUID(), UUID.randomUUID(), source, consumed, period, "finance", "material", "reason", NOW, NOW.plusSeconds(120));
        return BudgetConsumptionReversalOperation.queue(new BudgetConsumptionReversalOperation.Input(3, command, "a".repeat(64)), NOW);
    }
    private FinanceResult<BudgetConsumptionReversalObservation> applied(BudgetConsumptionReversalOperation op, Instant at, String reference) {
        return new FinanceResult.Success<>(new BudgetConsumptionReversalObservation(op.input().command().id(), op.input().command().digest(),
                BudgetConsumptionReversalObservation.Status.APPLIED, at, 3L, reference, "period", DATE, NOW, null));
    }
    private FinanceResult<BudgetConsumptionReversalObservation> observed(BudgetConsumptionReversalOperation op, BudgetConsumptionReversalObservation.Status status, Instant at) {
        return new FinanceResult.Success<>(new BudgetConsumptionReversalObservation(op.input().command().id(), op.input().command().digest(), status, at, null, null, null, null, null, null));
    }
}
