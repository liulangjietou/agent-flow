package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.expense.CostAllocation;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 到期释放后的重提必须沿用最后释放凭据，不能重置外部台账或恢复已实际核销的预算。
 * @author owlzhangfq@gmail.com
 */
class BudgetRefreezeTest {
    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);
    private final UUID report = UUID.randomUUID();
    private final UUID entity = UUID.randomUUID();

    @Test void aNewRoundRefreezesAgainstTheActualReleaseReceiptAndKeepsRevisionContinuity() {
        var released = finalized(BudgetCommand.Action.RELEASE);
        var input = input(position(2, 4), released.confirmed().expected());
        var queued = released.enqueue(input);
        assertThat(queued.status()).isEqualTo(BudgetOccupation.Status.RELEASED);
        assertThat(queued.confirmed()).isEqualTo(released.confirmed());
        assertThat(queued.frozenFor(input.command().position())).isFalse();
        var reopened = queued.complete(applied(input, 3));
        assertThat(reopened.frozenFor(position(2, 4))).isTrue();
        assertThat(reopened.confirmed().revision()).isEqualTo(3);
        assertThat(released.confirmed().revision()).isEqualTo(2);
        assertThat(reopened.targetDigest()).isEqualTo(released.targetDigest());
    }

    @Test void oldRoundOldFinancialVersionOrMissingReleaseReceiptCannotReopenBudget() {
        var released = finalized(BudgetCommand.Action.RELEASE);
        for (var position : List.of(position(1, 4), position(2, 2))) {
            assertThatThrownBy(() -> released.enqueue(input(position, released.confirmed().expected()))).isInstanceOf(DomainException.class);
        }
        assertThatThrownBy(() -> released.enqueue(input(position(2, 4), null))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> released.enqueue(input(position(2, 4), new BudgetCommand.Expected(1, "v1")))).isInstanceOf(DomainException.class);
        var next = input(position(2, 4), released.confirmed().expected());
        assertThatThrownBy(() -> BudgetOccupation.begin(next)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> released.enqueue(new BudgetOperation.Input(next.command(), "b".repeat(64)))).isInstanceOf(DomainException.class);
    }

    @Test void consumedBudgetAndAnAlreadyFrozenLedgerCannotAcceptAnotherFreeze() {
        var consumed = finalized(BudgetCommand.Action.CONSUME);
        assertThatThrownBy(() -> consumed.enqueue(input(position(2, 4), consumed.confirmed().expected()))).isInstanceOf(DomainException.class);
        var frozen = frozen();
        assertThatThrownBy(() -> frozen.enqueue(input(position(2, 4), frozen.confirmed().expected()))).isInstanceOf(DomainException.class);
    }

    @Test void rejectedRefreezeKeepsTheReleasedReceiptForTheNextActualAttempt() {
        var released = finalized(BudgetCommand.Action.RELEASE); var input = input(position(2, 4), released.confirmed().expected());
        var running = BudgetOperation.queue(input, NOW).claim(NOW, LEASE);
        var command = input.command();
        var rejection = new BudgetObservation(command.id(), command.digest(), BudgetObservation.Status.REJECTED,
                null, null, null, BudgetObservation.Rejection.BUDGET_INSUFFICIENT);
        var unchanged = released.enqueue(input).complete(running.complete(new FinanceResult.Success<>(rejection), NOW.plusSeconds(1)));
        assertThat(unchanged.status()).isEqualTo(BudgetOccupation.Status.RELEASED);
        assertThat(unchanged.confirmed()).isEqualTo(released.confirmed());
        assertThat(unchanged.pendingOperationId()).isNull();
        var third = input(position(3, 6), unchanged.confirmed().expected());
        assertThat(unchanged.enqueue(third).pendingOperationId()).isEqualTo(third.command().id());
    }

    @Test void pendingRefreezeAndLateReleaseCannotOverwriteTheNewLedger() {
        var frozen = frozen(); var release = new BudgetOperation.Input(new BudgetCommand(UUID.randomUUID(), "tenant",
                BudgetCommand.Action.RELEASE, frozen.confirmed().position(), frozen.confirmed().expected()), frozen.targetDigest());
        var finished = applied(release, 2); var released = frozen.enqueue(release).complete(finished);
        var next = input(position(2, 4), released.confirmed().expected()); var pending = released.enqueue(next);
        assertThatThrownBy(() -> pending.enqueue(next)).isInstanceOf(DomainException.class).hasMessageContaining("reconciled");
        assertThatThrownBy(() -> pending.complete(finished)).isInstanceOf(DomainException.class);
    }

    private BudgetOccupation frozen() {
        var first = input(position(1, 2), null);
        return BudgetOccupation.begin(first).complete(applied(first, 1));
    }
    private BudgetOccupation finalized(BudgetCommand.Action action) {
        var frozen = frozen();
        var input = new BudgetOperation.Input(new BudgetCommand(UUID.randomUUID(), "tenant", action,
                frozen.confirmed().position(), frozen.confirmed().expected()), frozen.targetDigest());
        return frozen.enqueue(input).complete(applied(input, 2));
    }
    private BudgetOperation.Input input(BudgetPrecheckPort.Request position, BudgetCommand.Expected expected) {
        return new BudgetOperation.Input(new BudgetCommand(UUID.randomUUID(), "tenant", BudgetCommand.Action.FREEZE, position, expected), "a".repeat(64));
    }
    private BudgetPrecheckPort.Request position(int round, long version) {
        return new BudgetPrecheckPort.Request(report, round, version, "alice", entity, "CNY", LocalDate.of(2026, 10, 3),
                List.of(new BudgetPrecheckPort.Allocation(1, 1, "TRAVEL", new CostAllocation("IT", null, new Money(new BigDecimal("100.00"), "CNY")))));
    }
    private BudgetOperation applied(BudgetOperation.Input input, long revision) {
        var running = BudgetOperation.queue(input, NOW).claim(NOW, LEASE); var command = input.command();
        return running.complete(new FinanceResult.Success<>(new BudgetObservation(command.id(), command.digest(), BudgetObservation.Status.APPLIED,
                revision, "v" + revision, NOW, null)), NOW.plusSeconds(1));
    }
}
