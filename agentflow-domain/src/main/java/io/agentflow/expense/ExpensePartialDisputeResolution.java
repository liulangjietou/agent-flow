package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.BudgetConsumptionReductionObservation;
import io.agentflow.finance.BudgetConsumptionReductionOperation;
import io.agentflow.finance.ExpenseAccrualReductionObservation;
import io.agentflow.finance.ExpenseAccrualReductionOperation;
import java.time.Instant;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 两个独立差额操作的具名裁决只引用实际候选和原调整相邻修订，不承载新的财务命令。
 * @author owlzhangfq@gmail.com
 */
public record ExpensePartialDisputeResolution(UUID id, String tenantId, UUID adjustmentId, ExpensePartialAdjustmentPreparation.Side side,
        UUID operationId, long beforeVersion, long afterVersion, BudgetConsumptionReductionObservation budget,
        ExpenseAccrualReductionObservation accrual, String resolvedBy, Instant resolvedAt, String evidenceReference, String reason) {
    /** 每次只决定一侧，另一侧凭据不可夹带；说明不能替代近期原号终态。 */
    public ExpensePartialDisputeResolution {
        if (id == null || !text(tenantId, 64) || adjustmentId == null || side == null || operationId == null || beforeVersion < 1
                || beforeVersion == Long.MAX_VALUE || afterVersion != beforeVersion + 1 || !text(resolvedBy, 128) || resolvedAt == null
                || !text(evidenceReference, 128) || !text(reason, 2000)) throw invalid();
        if (side == ExpensePartialAdjustmentPreparation.Side.BUDGET) {
            if (budget == null || accrual != null || !operationId.equals(budget.operationId()) || !adjustmentId.equals(budget.adjustmentId())
                    || budget.status() != BudgetConsumptionReductionObservation.Status.APPLIED && budget.status() != BudgetConsumptionReductionObservation.Status.REJECTED
                    || resolvedAt.isBefore(budget.observedAt()) || !resolvedAt.isBefore(budget.observedAt().plus(BudgetConsumptionReductionOperation.DISPUTE_EVIDENCE_LIFETIME))) throw invalid();
        } else if (accrual == null || budget != null || !operationId.equals(accrual.operationId()) || !adjustmentId.equals(accrual.adjustmentId())
                || accrual.status() != ExpenseAccrualReductionObservation.Status.POSTED && accrual.status() != ExpenseAccrualReductionObservation.Status.FAILED
                || resolvedAt.isBefore(accrual.observedAt()) || !resolvedAt.isBefore(accrual.observedAt().plus(ExpenseAccrualReductionOperation.DISPUTE_EVIDENCE_LIFETIME))) throw invalid();
    }

    /** 预算历史来自实际相邻修订，原申请人和出纳不能自行消除结果争议。 */
    public ExpensePartialAdjustment resolve(ExpensePartialAdjustment before, BudgetConsumptionReductionOperation.ResolutionHistory history) {
        requireBefore(before, ExpensePartialAdjustmentPreparation.Side.BUDGET);
        if (before.budget() == null || !budget.equals(before.budget().conflictingObservation())) throw invalid();
        return before.resolveBudget(budget.status(), history, resolvedAt);
    }

    /** ERP 决定保留原命令、受理事实和本地完成，不代替原来源确认。 */
    public ExpensePartialAdjustment resolve(ExpensePartialAdjustment before, ExpenseAccrualReductionOperation.ResolutionHistory history) {
        requireBefore(before, ExpensePartialAdjustmentPreparation.Side.ACCRUAL);
        if (before.accrual() == null || !accrual.equals(before.accrual().conflictingObservation())) throw invalid();
        return before.resolveAccrual(accrual.status(), history, resolvedAt);
    }
    private void requireBefore(ExpensePartialAdjustment before, ExpensePartialAdjustmentPreparation.Side expected) {
        if (side != expected || before.version() != beforeVersion || !adjustmentId.equals(before.id()) || !tenantId.equals(before.input().basis().tenantId())) throw invalid();
        before.input().basis().funding().requireAuthorization(resolvedBy, resolvedAt);
    }
    /** 对外只投影采用的终态，完整凭证继续留在受控历史中。 */
    public Outcome outcome() { return Outcome.valueOf(side == ExpensePartialAdjustmentPreparation.Side.BUDGET ? budget.status().name() : accrual.status().name()); }
    /** 实际观察时间用于持久列校验，不能用人工决定时间替换。 */
    public Instant observedAt() { return side == ExpensePartialAdjustmentPreparation.Side.BUDGET ? budget.observedAt() : accrual.observedAt(); }
    private static boolean text(String value, int max) {
        return StringUtils.isNotBlank(value) && value.length() <= max && value.equals(value.trim()) && value.chars().noneMatch(Character::isISOControl);
    }
    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_PARTIAL_DISPUTE_RESOLUTION", "Partial expense dispute resolution requires one exact terminal candidate, adjacent revisions and an independent named reviewer"); }
    /** 不将金融原件和人工说明写入对象日志。 */
    @Override public String toString() { return "ExpensePartialDisputeResolution[id=" + id + ", adjustmentId=" + adjustmentId + ", side=" + side + "]"; }

    /**
     * 查询未决或查无不能由人工宣布成可释放资源的裁决结果。
     * @author owlzhangfq@gmail.com
     */
    public enum Outcome { APPLIED, REJECTED, POSTED, FAILED }
}
