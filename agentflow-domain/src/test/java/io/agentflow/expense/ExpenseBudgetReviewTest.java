package io.agentflow.expense;

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
 * 本轮等待、人工授权和外部冻结分离，迟到结果与重复决定不能复活旧轮次。
 * @author owlzhangfq@gmail.com
 */
class ExpenseBudgetReviewTest {
    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private final BudgetExceptionPolicy policy = new BudgetExceptionPolicy("policy-flex-1");
    private final BudgetOperation original = queued();

    @Test
    void flexibleRefusalRequiresHumanAndApprovalStillWaitsForActualExternalConfirmation() {
        var refused = outcome(original, false, true, NOW.plusSeconds(1));
        var waiting = submitted(policy); var required = waiting.observe(refused, NOW.plusSeconds(2));
        assertThat(required.status()).isEqualTo(ExpenseBudgetReview.Status.REVIEW_REQUIRED);
        assertThat(required.approval()).isNull(); assertThat(required.version()).isEqualTo(2);
        assertThat(required.observe(refused, NOW.plusSeconds(3))).isEqualTo(required);
        var authorized = required.authorize(refused, UUID.randomUUID(), "budget-task", "owner", UUID.randomUUID(), NOW.plusSeconds(3));
        assertThat(authorized.status()).isEqualTo(ExpenseBudgetReview.Status.AUTHORIZED);
        var retry = BudgetOperation.queue(new BudgetOperation.Input(authorized.retryCommand(refused), original.input().targetDigest()), NOW.plusSeconds(3));
        assertThat(authorized.observe(retry, NOW.plusSeconds(3))).isEqualTo(authorized);
        assertThatThrownBy(() -> authorized.authorize(refused, UUID.randomUUID(), "other-task", "owner", UUID.randomUUID(), NOW.plusSeconds(4))).isInstanceOf(DomainException.class);
        var applied = outcome(retry, true, false, NOW.plusSeconds(4));
        assertThat(authorized.observe(applied, NOW.plusSeconds(5)).status()).isEqualTo(ExpenseBudgetReview.Status.CONFIRMED);
        assertThat(waiting.status()).isEqualTo(ExpenseBudgetReview.Status.WAITING_BUDGET);
    }

    @Test
    void alreadyFrozenHasAnExplicitAutomaticPassAndNeverNeedsHumanAuthorization() {
        var applied = outcome(original, true, false, NOW.plusSeconds(1));
        var confirmed = submitted(policy).observe(applied, NOW.plusSeconds(2));
        assertThat(confirmed.status()).isEqualTo(ExpenseBudgetReview.Status.CONFIRMED);
        var audit = UUID.randomUUID(); var passed = confirmed.pass("budget-task", audit, NOW.plusSeconds(3));
        assertThat(passed.automaticPass().auditEventId()).isEqualTo(audit);
        assertThatThrownBy(() -> passed.pass("another-task", UUID.randomUUID(), NOW.plusSeconds(4))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> confirmed.authorize(applied, UUID.randomUUID(), "task", "owner", UUID.randomUUID(), NOW.plusSeconds(4))).isInstanceOf(DomainException.class);
    }

    @Test
    void rigidOrChangedPolicyRefusalNeverOpensApprovalAndSecondRefusalCannotLoop() {
        var refused = outcome(original, false, true, NOW.plusSeconds(1));
        assertThat(submitted(null).observe(refused, NOW.plusSeconds(2)).status()).isEqualTo(ExpenseBudgetReview.Status.REJECTED);
        assertThat(submitted(new BudgetExceptionPolicy("changed")).observe(refused, NOW.plusSeconds(2)).status()).isEqualTo(ExpenseBudgetReview.Status.REJECTED);
        var rigid = outcome(original, false, false, NOW.plusSeconds(1));
        assertThat(submitted(policy).observe(rigid, NOW.plusSeconds(2)).status()).isEqualTo(ExpenseBudgetReview.Status.REJECTED);
        var approved = submitted(policy).observe(refused, NOW.plusSeconds(2)).authorize(refused, UUID.randomUUID(), "task", "owner", UUID.randomUUID(), NOW.plusSeconds(3));
        var retry = BudgetOperation.queue(new BudgetOperation.Input(approved.retryCommand(refused), original.input().targetDigest()), NOW.plusSeconds(3));
        var rejected = approved.observe(outcome(retry, false, true, NOW.plusSeconds(4)), NOW.plusSeconds(5));
        assertThat(rejected.status()).isEqualTo(ExpenseBudgetReview.Status.REJECTED);
        assertThatThrownBy(() -> rejected.authorize(refused, UUID.randomUUID(), "task", "owner", UUID.randomUUID(), NOW.plusSeconds(6))).isInstanceOf(DomainException.class);
    }

    @Test
    void restoredConclusionMustHaveItsOwnOriginalOperationAndDestination() {
        var waiting = submitted(policy);
        var forged = new ExpenseBudgetReview(waiting.input(), 2, ExpenseBudgetReview.Status.CONFIRMED, NOW, NOW.plusSeconds(1), null, null, null, null);
        assertThatThrownBy(() -> forged.requireSources(original, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> waiting.observe(queued(), NOW.plusSeconds(1))).isInstanceOf(DomainException.class);
        var wrongTarget = BudgetOperation.queue(new BudgetOperation.Input(original.input().command(), "b".repeat(64)), NOW);
        assertThatThrownBy(() -> waiting.observe(wrongTarget, NOW.plusSeconds(1))).isInstanceOf(DomainException.class);
        var late = BudgetOperation.queue(original.input(), NOW.plusSeconds(1));
        assertThatThrownBy(() -> waiting.observe(late, NOW.plusSeconds(2))).isInstanceOf(DomainException.class);
    }

    @Test
    void waitingUnknownAndClosedRoundsCannotBeAuthorizedOrAutomaticallyPassed() {
        var waiting = submitted(policy);
        assertThat(waiting.observe(original, NOW.plusSeconds(1))).isEqualTo(waiting);
        assertThatThrownBy(() -> waiting.pass("task", UUID.randomUUID(), NOW.plusSeconds(1))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> waiting.authorize(original, UUID.randomUUID(), "task", "owner", UUID.randomUUID(), NOW.plusSeconds(1))).isInstanceOf(DomainException.class);
        var closed = waiting.close(ExpenseBudgetReview.Closure.WITHDRAWN, NOW.plusSeconds(1));
        assertThat(closed.status()).isEqualTo(ExpenseBudgetReview.Status.CLOSED);
        assertThat(closed.observe(outcome(original, true, false, NOW.plusSeconds(2)), NOW.plusSeconds(3))).isEqualTo(closed);
    }

    private ExpenseBudgetReview submitted(BudgetExceptionPolicy source) {
        var command = original.input().command(); var position = command.position();
        return ExpenseBudgetReview.submitted(new ExpenseBudgetReview.Input(command.tenantId(), position.reportId(), UUID.randomUUID(), position.employeeId(),
                position.roundNo(), position.financialVersion(), UUID.randomUUID(), "budget", source, command.id(), original.input().targetDigest()), NOW);
    }
    private BudgetOperation queued() {
        var position = new BudgetPrecheckPort.Request(UUID.randomUUID(), 1, 2, "alice", UUID.randomUUID(), "CNY", LocalDate.of(2026, 10, 4),
                List.of(new BudgetPrecheckPort.Allocation(1, 1, "TRAVEL", new CostAllocation("IT", null, new Money(new BigDecimal("100"), "CNY")))));
        return BudgetOperation.queue(new BudgetOperation.Input(new BudgetCommand(UUID.randomUUID(), "tenant", BudgetCommand.Action.FREEZE, position, null), "a".repeat(64)), NOW);
    }
    private BudgetOperation outcome(BudgetOperation queued, boolean applied, boolean flexible, Instant at) {
        var command = queued.input().command();
        var observation = applied ? new BudgetObservation(command.id(), command.digest(), BudgetObservation.Status.APPLIED, 1L, "ledger-1", at, null)
                : new BudgetObservation(command.id(), command.digest(), BudgetObservation.Status.REJECTED, null, null, null,
                        flexible ? BudgetObservation.Rejection.BUDGET_EXCEPTION_REQUIRED : BudgetObservation.Rejection.BUDGET_INSUFFICIENT,
                        flexible ? new BudgetExceptionOffer(policy.reference(), "offer-1") : null);
        return queued.claim(queued.createdAt(), Duration.ofSeconds(15)).complete(new FinanceResult.Success<>(observation), at);
    }
}
