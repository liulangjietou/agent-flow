package io.agentflow.budget;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceResult;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.agentflow.budget.BudgetAdjustmentTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 财务只读证据的期限、单次消费及无副作用结束均由原事实约束。
 * @author owlzhangfq@gmail.com
 */
class BudgetAdjustmentReviewTest {
    @Test void namedReadDoesNotAuthorizeAndOnlyExactActorAndEvidenceCanBeConsumedOnce() {
        var queue = queue(); var source = queue.input().source();
        var ready = queue.claim(NOW.plusSeconds(1), Duration.ofSeconds(30)).complete(new FinanceResult.Success<>(currentLedger(source)), NOW.plusSeconds(2));
        assertThat(queue.ledger()).isNull(); assertThat(ready.consumedOperationId()).isNull();
        var command = BudgetAdjustmentCommand.authorize(UUID.randomUUID(), source, ready.ledger(), "finance", "确认", NOW.plusSeconds(3));
        var consumed = ready.consume(command, NOW.plusSeconds(3));
        assertThat(consumed.supports(command)).isTrue(); assertThat(consumed.usable(NOW.plusSeconds(3))).isFalse();
        assertThatThrownBy(() -> consumed.consume(command, NOW.plusSeconds(3))).isInstanceOf(DomainException.class);
        var other = BudgetAdjustmentCommand.authorize(UUID.randomUUID(), source, ready.ledger(), "finance-2", "确认", NOW.plusSeconds(3));
        assertThatThrownBy(() -> ready.consume(other, NOW.plusSeconds(3))).isInstanceOf(DomainException.class);
        assertThat(consumed.toString()).doesNotContain("1000", "budget-source");
    }

    @Test void expiryUsesRemoteObservationAndLateLeaseCompletionDoesNotCreateFreshEvidence() {
        var queue = queue(); var running = queue.claim(NOW.plusSeconds(1), Duration.ofSeconds(10));
        var ready = running.complete(new FinanceResult.Success<>(currentLedger(queue.input().source())), NOW.plusSeconds(2));
        assertThat(ready.usable(NOW.plusSeconds(300))).isTrue(); assertThat(ready.usable(NOW.plusSeconds(301))).isFalse();
        assertThat(running.complete(new FinanceResult.Success<>(ready.ledger()), NOW.plusSeconds(11)).issue()).isEqualTo(BudgetAdjustmentReview.Issue.TIMEOUT);
        assertThatThrownBy(() -> ready.claim(NOW.plusSeconds(3), Duration.ofSeconds(1))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new BudgetAdjustmentReview(ready.input(), 3, ready.status(), NOW.plusSeconds(12), running.startedAt(), running.leaseUntil(), ready.ledger(), NOW.plusSeconds(12), null, null))
                .isInstanceOf(DomainException.class);
    }

    @Test void invalidSourcePeriodOrBalanceCannotBecomeUsable() {
        var queue = queue(); var source = queue.input().source(); var running = queue.claim(NOW.plusSeconds(1), Duration.ofSeconds(10));
        var ledger = currentLedger(source);
        var spent = new BudgetLedgerPort.Snapshot(ledger.request(), ledger.sourceVersion(), ledger.observedAt(), ledger.validUntil(),
                List.of(position("budget-source", "1000", "900", "50"), ledger.positions().get(1)));
        assertThat(running.complete(new FinanceResult.Success<>(spent), NOW.plusSeconds(2)).issue()).isEqualTo(BudgetAdjustmentReview.Issue.LEDGER_CHANGED);
        assertThat(running.complete(new FinanceResult.Success<>(source.round().ledger()), NOW.plusSeconds(2)).status()).isEqualTo(BudgetAdjustmentReview.Status.BLOCKED);
        assertThat(running.complete(new FinanceResult.Rejected<>(FinanceResult.Reason.ACCOUNTING_PERIOD_CLOSED), NOW.plusSeconds(2)).issue()).isEqualTo(BudgetAdjustmentReview.Issue.LEDGER_REJECTED);
        assertThat(running.complete(new FinanceResult.Unavailable<>(FinanceResult.Failure.CONNECTION), NOW.plusSeconds(2)).status()).isEqualTo(BudgetAdjustmentReview.Status.UNAVAILABLE);
    }

    @Test void identityAndVersionRestoreCannotInventReadsOrConsumeWithoutReady() {
        var queue = queue(); var input = queue.input();
        assertThatThrownBy(() -> new BudgetAdjustmentReview.Input(input.id(), input.source(), "alice", 1, input.requestedAt())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new BudgetAdjustmentReview.Input(input.id(), input.source(), "finance", 0, input.requestedAt())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new BudgetAdjustmentReview.Input(input.id(), input.source(), "finance", 1, NOW.minusSeconds(1))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> queue.consume(command(), NOW.plusSeconds(2))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new BudgetAdjustmentReview(input, 2, BudgetAdjustmentReview.Status.QUEUED, input.requestedAt(), null, null, null, null, null, null)).isInstanceOf(DomainException.class);
        assertThat(queue.voidSource(NOW.plusSeconds(1)).status()).isEqualTo(BudgetAdjustmentReview.Status.VOIDED);
        assertThat(queue.claim(NOW.plusSeconds(1), Duration.ofSeconds(10)).voidSource(NOW.plusSeconds(2)).version()).isEqualTo(3);
    }

    @Test void retirementRequiresStoppedUnsentOrOriginalRejectionAndNamedIndependentActor() {
        var command = command(); var queued = BudgetAdjustmentOperation.queue(command, command.authorizedAt());
        assertThatThrownBy(() -> BudgetAdjustmentRetirement.from(queued, "finance", NOW.plusSeconds(3))).isInstanceOf(DomainException.class);
        var stopped = queued.voidBeforeSend(NOW.plusSeconds(3));
        var retirement = BudgetAdjustmentRetirement.from(stopped, "finance", NOW.plusSeconds(4));
        assertThat(retirement.basis()).isEqualTo(BudgetAdjustmentRetirement.Basis.NEVER_SENT); assertThat(retirement.matches(stopped)).isTrue();
        assertThatThrownBy(() -> BudgetAdjustmentRetirement.from(stopped, "alice", NOW.plusSeconds(4))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> BudgetAdjustmentRetirement.from(stopped, "finance", NOW.plusSeconds(2))).isInstanceOf(DomainException.class);
        var running = queued.claim(command.authorizedAt(), Duration.ofSeconds(10));
        var unknown = running.unavailable(BudgetAdjustmentOperation.Failure.TIMEOUT, NOW.plusSeconds(3));
        assertThatThrownBy(() -> BudgetAdjustmentRetirement.from(unknown, "finance", NOW.plusSeconds(4))).isInstanceOf(DomainException.class);
        var rejection = new BudgetAdjustmentObservation(command.id(), command.digest(), BudgetAdjustmentObservation.Status.REJECTED, 1, NOW.plusSeconds(3),
                null, null, List.of(), BudgetAdjustmentObservation.Rejection.LEDGER_VERSION_CONFLICT);
        var rejected = running.complete(new FinanceResult.Success<>(rejection), NOW.plusSeconds(3));
        assertThat(BudgetAdjustmentRetirement.from(rejected, "finance", NOW.plusSeconds(4)).basis()).isEqualTo(BudgetAdjustmentRetirement.Basis.REJECTED);
    }
    private BudgetAdjustmentReview queue() {
        return BudgetAdjustmentReview.queue(new BudgetAdjustmentReview.Input(UUID.randomUUID(), ApprovedBudgetAdjustment.from(approved()), "finance", 1, NOW.plusSeconds(1)));
    }
}
