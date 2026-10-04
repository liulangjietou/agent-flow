package io.agentflow.expense;

import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.common.DomainException;
import io.agentflow.finance.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 固定期限、原命令结果及终态分别验证，排队不能冒充实际释放。
 * @author owlzhangfq@gmail.com
 */
class ExpenseBudgetRetentionTest {
    private static final Instant STOPPED = Instant.parse("2026-11-01T05:30:00Z");
    private final UUID report = UUID.randomUUID();
    private final UUID application = UUID.randomUUID();

    @Test void deadlineUsesElapsedDaysAcrossDstAndIncludesTheExactBoundary() {
        var retained = retained();
        assertThat(retained.expiresAt()).isEqualTo(STOPPED.plusSeconds(3 * 86400));
        assertThatThrownBy(() -> retained.reconcile(retained.expiresAt().minusNanos(1))).isInstanceOf(DomainException.class);
        var reconciling = retained.reconcile(retained.expiresAt());
        assertThat(reconciling.status()).isEqualTo(ExpenseBudgetRetention.Status.RECONCILING);
        assertThat(reconciling.reconcile(retained.expiresAt().plusSeconds(10))).isSameAs(reconciling);
        assertThat(reconciling.policy()).isEqualTo(retained.policy());
        assertThat(reconciling.expiresAt()).isEqualTo(retained.expiresAt());
    }

    @Test void queuedUnknownAndAppliedAreDistinctAndKeepTheOriginalReleaseIdentity() {
        var retained = retained(); var operation = release(report, 1); var queued = retained.queue(operation, retained.expiresAt());
        assertThat(queued.terminal()).isFalse();
        var running = operation.claim(retained.expiresAt(), Duration.ofSeconds(30));
        var unknown = running.unavailable(BudgetOperation.Failure.TIMEOUT, retained.expiresAt().plusSeconds(1));
        assertThat(queued.complete(unknown, unknown.updatedAt())).isSameAs(queued);
        var query = unknown.claim(unknown.nextAttemptAt(), Duration.ofSeconds(30));
        var command = query.input().command();
        var done = query.complete(new FinanceResult.Success<>(new BudgetObservation(command.id(), command.digest(),
                BudgetObservation.Status.APPLIED, 2L, "release-v2", query.updatedAt(), null)), query.updatedAt());
        var released = queued.complete(done, done.updatedAt());
        assertThat(released.status()).isEqualTo(ExpenseBudgetRetention.Status.RELEASED);
        assertThat(released.releaseOperationId()).isEqualTo(command.id());
        assertThat(released.terminal()).isTrue();
        assertThatThrownBy(() -> released.queue(release(report, 1), done.updatedAt())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> queued.supersede(done.updatedAt())).isInstanceOf(DomainException.class);
    }

    @Test void rejectedReleaseStopsInsteadOfAutomaticallyIssuingAnotherCommand() {
        var retained = retained(); var operation = release(report, 1); var queued = retained.queue(operation, retained.expiresAt());
        var command = operation.input().command();
        var done = operation.claim(retained.expiresAt(), Duration.ofSeconds(30)).complete(new FinanceResult.Success<>(
                new BudgetObservation(command.id(), command.digest(), BudgetObservation.Status.REJECTED,
                        null, null, null, BudgetObservation.Rejection.LEDGER_VERSION_CONFLICT)), retained.expiresAt());
        var rejected = queued.complete(done, done.updatedAt());
        assertThat(rejected.issue()).isEqualTo("LEDGER_VERSION_CONFLICT");
        assertThat(rejected.terminal()).isTrue();
        assertThatThrownBy(() -> rejected.reconcile(done.updatedAt())).isInstanceOf(DomainException.class);
    }

    @Test void anotherReportFutureRoundAndUnrelatedReleaseCannotBeAttached() {
        var retained = retained();
        assertThatThrownBy(() -> retained.queue(release(UUID.randomUUID(), 1), retained.expiresAt())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> retained.queue(release(report, 3), retained.expiresAt())).isInstanceOf(DomainException.class);
        var queued = retained.queue(release(report, 1), retained.expiresAt());
        assertThatThrownBy(() -> queued.complete(release(report, 1), retained.expiresAt())).isInstanceOf(DomainException.class);
    }

    @Test void supersededAndUnfundedRecordsNeverCreateAReleaseReceipt() {
        var retained = retained();
        for (var done : List.of(retained.supersede(STOPPED.plusSeconds(1)), retained.unfrozen(retained.expiresAt()))) {
            assertThat(done.releaseOperationId()).isNull(); assertThat(done.terminal()).isTrue();
            assertThatThrownBy(() -> done.queue(release(report, 1), retained.expiresAt())).isInstanceOf(DomainException.class);
        }
    }

    @Test void invalidPolicyAndNonStoppedRoundFailAtCreation() {
        for (int days : new int[]{-1, 0, 3661}) assertThatThrownBy(() -> new ExpenseBudgetRetention.Policy(days)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> ExpenseBudgetRetention.retain("demo", report, application, 1, SubmissionRound.Status.APPROVED,
                STOPPED, new ExpenseBudgetRetention.Policy(3))).isInstanceOf(DomainException.class);
        var current = retained();
        assertThatThrownBy(() -> new ExpenseBudgetRetention(current.tenantId(), report, application, 2, current.stoppedStatus(),
                STOPPED, current.policy(), current.expiresAt().plusSeconds(1), current.status(), null, null, 1, STOPPED))
                .isInstanceOf(DomainException.class);
    }

    private ExpenseBudgetRetention retained() {
        return ExpenseBudgetRetention.retain("demo", report, application, 2, SubmissionRound.Status.WITHDRAWN, STOPPED, new ExpenseBudgetRetention.Policy(3));
    }
    private BudgetOperation release(UUID reportId, int round) {
        var money = new Money(new BigDecimal("100.00"), "CNY");
        var position = new BudgetPrecheckPort.Request(reportId, round, 2, "alice", UUID.randomUUID(), "CNY", LocalDate.of(2026, 11, 1),
                List.of(new BudgetPrecheckPort.Allocation(1, 1, "DAILY", new CostAllocation("IT", null, money))));
        var command = new BudgetCommand(UUID.randomUUID(), "demo", BudgetCommand.Action.RELEASE, position, new BudgetCommand.Expected(1, "freeze-v1"));
        return BudgetOperation.queue(new BudgetOperation.Input(command, "a".repeat(64)), retained().expiresAt());
    }
}
