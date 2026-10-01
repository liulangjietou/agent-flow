package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.expense.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 原调整的每次具名裁决推进独立计数，完成、另一侧和原命令均不能被人工决定替换。
 * @author owlzhangfq@gmail.com
 */
class ExpensePartialDisputeResolutionTest {
    @Test void budgetDecisionKeepsCompletionAndReviewUntilSeparateConfirmation() {
        var completed = completed(); var before = disputedBudget(completed); var at = before.updatedAt().plusSeconds(1);
        var decision = budgetDecision(before, "finance", at);
        var history = new BudgetConsumptionReductionOperation.ResolutionHistory(completed.completion().budget(), true, before.budget().conflictingObservation().observedAt());
        var after = decision.resolve(before, history);
        assertThat(after.resolutionCount()).isEqualTo(1); assertThat(after.version()).isEqualTo(before.version() + 1);
        assertThat(after.completion()).isEqualTo(completed.completion()); assertThat(after.accrual()).isEqualTo(before.accrual());
        assertThat(after.issue()).isEqualTo(before.issue()); assertThat(after.status()).isEqualTo(ExpensePartialAdjustment.Status.REVIEW_REQUIRED);
        assertThatThrownBy(() -> before.withBudget(after.budget(), at)).isInstanceOf(DomainException.class);
        var confirmed = after.confirmCurrent(at.plusSeconds(1));
        assertThat(confirmed.status()).isEqualTo(ExpensePartialAdjustment.Status.APPLIED); assertThat(confirmed.resolutionCount()).isEqualTo(1);
        assertThat(confirmed.withBudget(confirmed.budget().requestQuery(at.plusSeconds(2)), at.plusSeconds(2)).resolutionCount()).isEqualTo(1);
        assertThat(decision.toString()).doesNotContain("finance", "proof", "理由");
    }

    @Test void accrualDecisionPreservesTheActualVoucherAndIndependentBudget() {
        var completed = completed(); var before = disputedAccrual(completed); var at = before.updatedAt().plusSeconds(1);
        var candidate = before.accrual().conflictingObservation();
        var decision = new ExpensePartialDisputeResolution(UUID.randomUUID(), "demo", before.id(), ExpensePartialAdjustmentPreparation.Side.ACCRUAL,
                candidate.operationId(), before.version(), before.version() + 1, null, candidate, "finance", at, "proof", "核对原反向凭证");
        var history = new ExpenseAccrualReductionOperation.ResolutionHistory(completed.completion().accrual(), true, candidate.observedAt());
        var after = decision.resolve(before, history);
        assertThat(after.resolutionCount()).isEqualTo(1); assertThat(after.budget()).isEqualTo(before.budget());
        assertThat(after.completion()).isEqualTo(completed.completion()); assertThat(after.issue()).isEqualTo(before.issue());
        assertThat(after.confirmCurrent(at.plusSeconds(1)).status()).isEqualTo(ExpensePartialAdjustment.Status.APPLIED);
        assertThatThrownBy(() -> before.withAccrual(after.accrual(), at)).isInstanceOf(DomainException.class);
    }

    @Test void decisionRejectsForeignBindingApplicantCashierAndWrongSideHistory() {
        var before = disputedBudget(completed()); var at = before.updatedAt().plusSeconds(1);
        var history = new BudgetConsumptionReductionOperation.ResolutionHistory(before.completion().budget(), true, before.budget().conflictingObservation().observedAt());
        for (var actor : new String[]{"alice", "cashier"}) assertThatThrownBy(() -> budgetDecision(before, actor, at).resolve(before, history)).isInstanceOf(DomainException.class);
        var decision = budgetDecision(before, "finance", at);
        assertThatThrownBy(() -> decision.resolve(disputedBudget(completed()), history)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> decision.resolve(before, new ExpenseAccrualReductionOperation.ResolutionHistory(null, false, null))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new ExpensePartialDisputeResolution(decision.id(), "demo", before.id(), decision.side(), decision.operationId(), before.version(), before.version() + 2,
                decision.budget(), null, "finance", at, "proof", "相邻修订不可省略")).isInstanceOf(DomainException.class);
    }

    @Test void decisionRequiresSingleFreshActualTerminalCandidate() {
        var before = disputedBudget(completed()); var at = before.updatedAt().plusSeconds(1); var decision = budgetDecision(before, "finance", at);
        assertThatThrownBy(() -> budgetDecision(before, "finance", before.budget().conflictingObservation().observedAt().plusSeconds(300))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new ExpensePartialDisputeResolution(decision.id(), "demo", before.id(), decision.side(), decision.operationId(), before.version(), before.version() + 1,
                decision.budget(), before.accrual().observation(), "finance", at, "proof", "不允许夹带另一侧")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new ExpensePartialDisputeResolution(decision.id(), "demo", before.id(), decision.side(), UUID.randomUUID(), before.version(), before.version() + 1,
                decision.budget(), null, "finance", at, "proof", "不可替换原操作")).isInstanceOf(DomainException.class);
    }

    private ExpensePartialAdjustment completed() {
        var initial = ExpensePartialAdjustmentTest.adjustment(); return ExpensePartialAdjustmentTest.complete(initial, initial.updatedAt());
    }
    private ExpensePartialDisputeResolution budgetDecision(ExpensePartialAdjustment before, String actor, Instant at) {
        var candidate = before.budget().conflictingObservation();
        return new ExpensePartialDisputeResolution(UUID.randomUUID(), "demo", before.id(), ExpensePartialAdjustmentPreparation.Side.BUDGET,
                candidate.operationId(), before.version(), before.version() + 1, candidate, null, actor, at, "proof", "裁决理由");
    }
    private ExpensePartialAdjustment disputedBudget(ExpensePartialAdjustment completed) {
        var at = completed.updatedAt().plusSeconds(1); var original = completed.budget().observation();
        var query = budgetQuery(completed, at);
        var rejected = new BudgetConsumptionReductionObservation(original.operationId(), original.adjustmentId(), original.commandDigest(),
                BudgetConsumptionReductionObservation.Status.REJECTED, at, null, BudgetConsumptionReductionObservation.Rejection.LEDGER_VERSION_CONFLICT);
        var disputed = query.withBudget(query.budget().complete(new FinanceResult.Success<>(rejected), at), at);
        var checked = budgetQuery(disputed, at.plusSeconds(1));
        var candidate = new BudgetConsumptionReductionObservation(original.operationId(), original.adjustmentId(), original.commandDigest(), original.status(), at.plusSeconds(1), original.posting(), null);
        return checked.withBudget(checked.budget().complete(new FinanceResult.Success<>(candidate), at.plusSeconds(1)), at.plusSeconds(1));
    }
    private ExpensePartialAdjustment disputedAccrual(ExpensePartialAdjustment completed) {
        var at = completed.updatedAt().plusSeconds(1); var original = completed.accrual().observation();
        var query = accrualQuery(completed, at);
        var rejected = new ExpenseAccrualReductionObservation(original.operationId(), original.adjustmentId(), original.commandDigest(), ExpenseAccrualReductionObservation.Status.FAILED,
                original.revision() + 1, at, original.acceptanceReference(), null, ExpenseAccrualReductionObservation.Rejection.ORIGINAL_CHANGED);
        var disputed = query.withAccrual(query.accrual().complete(new FinanceResult.Success<>(rejected), at), at);
        var checked = accrualQuery(disputed, at.plusSeconds(1));
        var candidate = new ExpenseAccrualReductionObservation(original.operationId(), original.adjustmentId(), original.commandDigest(), original.status(), original.revision() + 2,
                at.plusSeconds(1), original.acceptanceReference(), original.posting(), null);
        return checked.withAccrual(checked.accrual().complete(new FinanceResult.Success<>(candidate), at.plusSeconds(1)), at.plusSeconds(1));
    }
    private ExpensePartialAdjustment budgetQuery(ExpensePartialAdjustment value, Instant at) {
        var requested = value.withBudget(value.budget().requestQuery(at), at);
        return requested.withBudget(requested.budget().claim(at, Duration.ofSeconds(30)), at);
    }
    private ExpensePartialAdjustment accrualQuery(ExpensePartialAdjustment value, Instant at) {
        var requested = value.withAccrual(value.accrual().requestQuery(at), at);
        return requested.withAccrual(requested.accrual().claim(at, Duration.ofSeconds(30)), at);
    }
}
