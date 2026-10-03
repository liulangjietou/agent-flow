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
 * 预算恢复只信原操作事实，待定操作和旧财务版本都不能通过已冻结守卫。
 * @author owlzhangfq@gmail.com
 */
class BudgetOperationTest {
    private static final Instant NOW = Instant.parse("2026-09-28T16:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(15);
    private final UUID report = UUID.randomUUID();
    private final UUID entity = UUID.randomUUID();

    @Test
    void timeoutRequiresReadOnlyRecoveryAndTerminalCannotBeOverwritten() {
        var queued = queue(); var occupation = BudgetOccupation.begin(queued.input());
        assertThat(occupation.frozenFor(position(2, "100"))).isFalse();
        var claimed = queued.claim(NOW, LEASE);
        var unknown = claimed.complete(new FinanceResult.Unavailable<>(FinanceResult.Failure.TIMEOUT), NOW.plusSeconds(1));
        assertThat(unknown.status()).isEqualTo(BudgetOperation.Status.UNKNOWN);
        assertThatThrownBy(() -> unknown.claim(NOW.plusSeconds(2), LEASE)).isInstanceOf(DomainException.class);
        var query = unknown.claim(unknown.nextAttemptAt(), LEASE); assertThat(query.status()).isEqualTo(BudgetOperation.Status.QUERYING);
        var applied = query.complete(applied(query, 1), query.updatedAt().plusSeconds(1));
        var frozen = occupation.complete(applied);
        assertThat(frozen.frozenFor(position(2, "100"))).isTrue(); assertThat(frozen.pendingOperationId()).isNull();
        assertThat(applied.version()).isEqualTo(5);
        assertThatThrownBy(() -> applied.complete(applied(applied, 1), NOW.plusSeconds(12))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> frozen.complete(applied)).isInstanceOf(DomainException.class);
    }

    @Test
    void expiredLeaseNeverResendsAndOnlyAuthoritativeNotFoundAllowsSameCommandRetry() {
        var running = queue().claim(NOW, LEASE);
        var expired = running.complete(applied(running, 1), NOW.plusSeconds(15));
        assertThat(expired.failure()).isEqualTo(BudgetOperation.Failure.LEASE_EXPIRED);
        var query = expired.claim(expired.nextAttemptAt(), LEASE);
        var missing = query.complete(observed(query, BudgetObservation.Status.NOT_FOUND, null), query.updatedAt().plusSeconds(1));
        assertThat(missing.status()).isEqualTo(BudgetOperation.Status.QUEUED);
        var again = missing.claim(missing.nextAttemptAt(), LEASE);
        assertThat(again.status()).isEqualTo(BudgetOperation.Status.EXECUTING);
        assertThat(again.input()).isEqualTo(running.input());
        assertThat(again.input().command().digest()).isEqualTo(running.input().command().digest());
        assertThat(again.complete(observed(again, BudgetObservation.Status.NOT_FOUND, null), again.updatedAt().plusSeconds(1)).failure())
                .isEqualTo(BudgetOperation.Failure.INVALID_RESPONSE);
    }

    @Test
    void pendingAndInvalidResponsesStayUnknownWithPersistedBoundedBackoff() {
        var running = queue().claim(NOW, LEASE);
        var pending = running.complete(observed(running, BudgetObservation.Status.PENDING, null), NOW.plusSeconds(1));
        assertThat(pending.status()).isEqualTo(BudgetOperation.Status.UNKNOWN); assertThat(pending.failure()).isNull();
        var current = pending;
        for (int index = 0; index < 10; index++) {
            var claimed = current.claim(current.nextAttemptAt(), LEASE);
            current = claimed.complete(new FinanceResult.Rejected<>(FinanceResult.Reason.BUDGET_INSUFFICIENT), claimed.updatedAt().plusSeconds(1));
            assertThat(current.status()).isEqualTo(BudgetOperation.Status.UNKNOWN);
            assertThat(current.failure()).isEqualTo(BudgetOperation.Failure.INVALID_RESPONSE);
            assertThat(Duration.between(current.updatedAt(), current.nextAttemptAt()).toSeconds()).isBetween(5L, 300L);
        }
        assertThat(Duration.between(current.updatedAt(), current.nextAttemptAt()).toSeconds()).isEqualTo(300);
    }

    @Test
    void adjustmentRejectionKeepsOldReservationButNeverApprovesNewFinancialVersion() {
        var initial = queue(); var frozen = freeze(initial);
        var input = input(BudgetCommand.Action.ADJUST, position(3, "80"), frozen.confirmed().expected());
        var pending = frozen.enqueue(input);
        assertThat(pending.frozenFor(position(2, "100"))).isFalse();
        assertThatThrownBy(() -> pending.enqueue(input)).isInstanceOf(DomainException.class).hasMessageContaining("reconciled");
        var running = BudgetOperation.queue(input, NOW.plusSeconds(2)).claim(NOW.plusSeconds(2), LEASE);
        var failed = running.complete(observed(running, BudgetObservation.Status.REJECTED, BudgetObservation.Rejection.ACCOUNTING_PERIOD_CLOSED), NOW.plusSeconds(3));
        var unchanged = pending.complete(failed);
        assertThat(unchanged.confirmed()).isEqualTo(frozen.confirmed());
        assertThat(unchanged.frozenFor(position(3, "80"))).isFalse();
        var retry = input(BudgetCommand.Action.ADJUST, position(3, "80"), unchanged.confirmed().expected());
        var claim = BudgetOperation.queue(retry, NOW.plusSeconds(4)).claim(NOW.plusSeconds(4), LEASE);
        var adjusted = unchanged.enqueue(retry).complete(claim.complete(applied(claim, 2), NOW.plusSeconds(5)));
        assertThat(adjusted.frozenFor(position(3, "80"))).isTrue();
        assertThat(adjusted.confirmed().revision()).isEqualTo(2);
    }

    @Test
    void rejectedResubmissionDoesNotPreventAThirdRoundUsingTheLastConfirmedLedger() {
        var frozen = freeze(queue()); var second = position(3, "120");
        second = new BudgetPrecheckPort.Request(report, 2, second.financialVersion(), second.employeeId(), entity,
                second.baseCurrency(), second.accountingDate(), second.allocations());
        var input = input(BudgetCommand.Action.ADJUST, second, frozen.confirmed().expected());
        var running = BudgetOperation.queue(input, NOW.plusSeconds(2)).claim(NOW.plusSeconds(2), LEASE);
        var rejected = frozen.enqueue(input).complete(running.complete(observed(running,
                BudgetObservation.Status.REJECTED, BudgetObservation.Rejection.BUDGET_INSUFFICIENT), NOW.plusSeconds(3)));
        var third = new BudgetPrecheckPort.Request(report, 3, 5, second.employeeId(), entity,
                second.baseCurrency(), second.accountingDate(), position(5, "80").allocations());
        var next = input(BudgetCommand.Action.ADJUST, third, rejected.confirmed().expected());
        assertThat(rejected.enqueue(next).pendingOperationId()).isEqualTo(next.command().id());
        assertThat(rejected.confirmed()).isEqualTo(frozen.confirmed());
    }

    @Test
    void releaseAndConsumeRequireExactLastPositionAndPreventLaterChanges() {
        for (var action : List.of(BudgetCommand.Action.RELEASE, BudgetCommand.Action.CONSUME)) {
            var frozen = freeze(queue());
            assertThatThrownBy(() -> frozen.enqueue(input(action, position(3, "80"), frozen.confirmed().expected()))).isInstanceOf(DomainException.class);
            var next = input(action, frozen.confirmed().position(), frozen.confirmed().expected());
            var claimed = BudgetOperation.queue(next, NOW.plusSeconds(2)).claim(NOW.plusSeconds(2), LEASE);
            var result = frozen.enqueue(next).complete(claimed.complete(applied(claimed, 2), NOW.plusSeconds(3)));
            assertThat(result.status().name()).isEqualTo(action == BudgetCommand.Action.RELEASE ? "RELEASED" : "CONSUMED");
            assertThat(result.frozenFor(position(2, "100"))).isFalse();
            assertThatThrownBy(() -> result.enqueue(input(BudgetCommand.Action.ADJUST, position(3, "80"), result.confirmed().expected()))).isInstanceOf(DomainException.class);
        }
    }

    @Test
    void targetOwnerExpectedVersionAndStaleFinancialPositionCannotReplaceLedger() {
        var frozen = freeze(queue()); var next = input(BudgetCommand.Action.ADJUST, position(3, "80"), frozen.confirmed().expected());
        assertThatThrownBy(() -> frozen.enqueue(new BudgetOperation.Input(next.command(), "b".repeat(64)))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> frozen.enqueue(input(BudgetCommand.Action.ADJUST, position(2, "80"), frozen.confirmed().expected()))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> frozen.enqueue(input(BudgetCommand.Action.ADJUST, position(3, "80"), new BudgetCommand.Expected(2, "v2")))).isInstanceOf(DomainException.class);
        var foreign = new BudgetCommand(next.command().id(), "foreign", next.command().action(), next.command().position(), next.command().expected());
        assertThatThrownBy(() -> frozen.enqueue(new BudgetOperation.Input(foreign, next.targetDigest()))).isInstanceOf(DomainException.class);
    }

    private BudgetOperation queue() { return BudgetOperation.queue(input(BudgetCommand.Action.FREEZE, position(2, "100"), null), NOW); }
    private BudgetOccupation freeze(BudgetOperation initial) {
        var claimed = initial.claim(NOW, LEASE);
        return BudgetOccupation.begin(initial.input()).complete(claimed.complete(applied(claimed, 1), NOW.plusSeconds(1)));
    }
    private BudgetOperation.Input input(BudgetCommand.Action action, BudgetPrecheckPort.Request position, BudgetCommand.Expected expected) {
        return new BudgetOperation.Input(new BudgetCommand(UUID.randomUUID(), "tenant", action, position, expected), "a".repeat(64));
    }
    private BudgetPrecheckPort.Request position(long version, String amount) {
        return new BudgetPrecheckPort.Request(report, 1, version, "alice", entity, "CNY", LocalDate.of(2026, 9, 28),
                List.of(new BudgetPrecheckPort.Allocation(1, 1, "TRAVEL", new CostAllocation("IT", null, new Money(new BigDecimal(amount), "CNY")))));
    }
    private FinanceResult<BudgetObservation> applied(BudgetOperation operation, long revision) {
        var command = operation.input().command();
        return new FinanceResult.Success<>(new BudgetObservation(command.id(), command.digest(), BudgetObservation.Status.APPLIED, revision, "v" + revision, NOW, null));
    }
    private FinanceResult<BudgetObservation> observed(BudgetOperation operation, BudgetObservation.Status status, BudgetObservation.Rejection reason) {
        var command = operation.input().command();
        return new FinanceResult.Success<>(new BudgetObservation(command.id(), command.digest(), status, null, null, null, reason));
    }
}
