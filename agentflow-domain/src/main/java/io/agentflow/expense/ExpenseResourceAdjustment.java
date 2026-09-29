package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.BudgetConsumptionReversalObservation;
import io.agentflow.finance.BudgetConsumptionReversalOperation;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 预算实际冲正和本地资源冲回分开确认；原结算、资金和档案不被改写成从未发生。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseResourceAdjustment(Input input, long version, Status status, Long budgetReversalVersion,
        BudgetConsumptionReversalObservation budgetReversal, boolean resourcesReversed, String issue, Instant createdAt, Instant updatedAt) {
    /** 已确认预算证据与资源完成标记不可由重试消除，异常只追加待处理原因。 */
    public ExpenseResourceAdjustment {
        if (input == null || version < 1 || status == null || createdAt == null || updatedAt == null
                || !createdAt.equals(input.budget().command().createdAt()) || updatedAt.isBefore(createdAt)) throw invalid();
        if ((budgetReversal == null) != (budgetReversalVersion == null) || budgetReversalVersion != null && budgetReversalVersion < 3
                || budgetReversal != null && (budgetReversal.status() != BudgetConsumptionReversalObservation.Status.APPLIED
                || !budgetReversal.matches(input.budget().command(), true, updatedAt))) throw invalid();
        if ((status == Status.READY || status == Status.APPLIED || resourcesReversed) && budgetReversal == null
                || status == Status.WAITING_BUDGET && budgetReversal != null || status == Status.APPLIED && !resourcesReversed
                || status == Status.RETIRED && (budgetReversal != null || resourcesReversed)
                || resourcesReversed && status != Status.APPLIED && status != Status.REVIEW_REQUIRED) throw invalid();
        if (status == Status.REVIEW_REQUIRED ? issue == null || !issue.matches("[A-Z][A-Z0-9_]{0,63}") : issue != null) throw invalid();
    }

    /** 调整授权和预算冲正命令同时登记，本地资源保持已核销，直到真正冲正成功。 */
    public static ExpenseResourceAdjustment begin(Input input) {
        return new ExpenseResourceAdjustment(input, 1, Status.WAITING_BUDGET, null, null, false, null,
                input.budget().command().createdAt(), input.budget().command().createdAt());
    }

    /** 只接收本调整原命令的成功回执；外部成功仍不等同于本地资源已经冲回。 */
    public ExpenseResourceAdjustment budgetApplied(BudgetConsumptionReversalOperation operation, Instant at) {
        requireTime(at);
        if (!input.budget().equals(operation.input()) || operation.status() != BudgetConsumptionReversalOperation.Status.APPLIED
                || at.isBefore(operation.updatedAt()) || status != Status.WAITING_BUDGET) throw conflict();
        return new ExpenseResourceAdjustment(input, version + 1, Status.READY, operation.version(), operation.observation(), false, null, createdAt, at);
    }

    /** 本地资源计划与此完成状态必须由应用服务放在同一个短事务中。 */
    public ExpenseResourceAdjustment applied(Instant at) {
        requireTime(at); if (status != Status.READY || resourcesReversed) throw conflict();
        return new ExpenseResourceAdjustment(input, version + 1, Status.APPLIED, budgetReversalVersion, budgetReversal, true, null, createdAt, at);
    }

    /** 资金、会计或资源来源变化时留下独立问题；已发生的预算或资源调整保留。 */
    public ExpenseResourceAdjustment requireReview(String code, Instant at) {
        requireTime(at); if (status == Status.RETIRED) throw conflict();
        if (status == Status.REVIEW_REQUIRED && Objects.equals(issue, code)) return this;
        return new ExpenseResourceAdjustment(input, version + 1, Status.REVIEW_REQUIRED, budgetReversalVersion, budgetReversal, resourcesReversed, code, createdAt, at);
    }

    /** 人工重试必须仍有同一笔有效预算冲正，且此前没有完成本地资源冲回。 */
    public ExpenseResourceAdjustment retryResources(BudgetConsumptionReversalOperation operation, Instant at) {
        requireTime(at);
        if (status != Status.REVIEW_REQUIRED || resourcesReversed || !input.budget().equals(operation.input())
                || operation.status() != BudgetConsumptionReversalOperation.Status.APPLIED || at.isBefore(operation.updatedAt())
                || budgetReversal != null && !sameApplied(budgetReversal, operation.observation())) throw conflict();
        return new ExpenseResourceAdjustment(input, version + 1, Status.READY, budgetReversalVersion == null ? operation.version() : budgetReversalVersion,
                budgetReversal == null ? operation.observation() : budgetReversal, false, null, createdAt, at);
    }

    /** 明确结束不会冲回资源或删除历史，只为后续重新准备释放本调整的办理占用。 */
    public ExpenseResourceAdjustment retire(BudgetConsumptionReversalOperation operation, Instant at) {
        requireTime(at);
        if (status == Status.RETIRED || resourcesReversed || budgetReversal != null || !input.budget().equals(operation.input())
                || !operation.safelyUnexecuted() || operation.status() == BudgetConsumptionReversalOperation.Status.QUEUED
                || at.isBefore(operation.updatedAt())) throw conflict();
        return new ExpenseResourceAdjustment(input, version + 1, Status.RETIRED, null, null, false, null, createdAt, at);
    }

    public UUID id() { return input.budget().command().adjustmentId(); }
    private static boolean sameApplied(BudgetConsumptionReversalObservation original, BudgetConsumptionReversalObservation current) {
        return original.operationId().equals(current.operationId()) && original.commandDigest().equals(current.commandDigest())
                && original.ledgerRevision().equals(current.ledgerRevision()) && original.reference().equals(current.reference())
                && original.periodReference().equals(current.periodReference()) && original.accountingDate().equals(current.accountingDate()) && original.appliedAt().equals(current.appliedAt());
    }
    private void requireTime(Instant at) { if (at == null || at.isBefore(updatedAt)) throw conflict(); }
    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_RESOURCE_ADJUSTMENT", "Adjustment must preserve original sources and separate budget and resource results"); }
    private static DomainException conflict() { return new DomainException("EXPENSE_RESOURCE_ADJUSTMENT_CONFLICT", "Adjustment state or original budget reversal has changed"); }

    /**
     * 当前仅实现完整取消，不用任意金额输入伪装行级或部分财务调整。
     * @author owlzhangfq@gmail.com
     */
    public record Input(ExpenseResourceAdjustmentBasis basis, BudgetConsumptionReversalOperation.Input budget) {
        /** 原来源、原成功版本、目标和新独立授权必须同时匹配。 */
        public Input {
            if (basis == null || budget == null || budget.consumedVersion() != basis.consumption().version()
                    || !budget.command().source().equals(basis.consumption().input().command())
                    || !budget.command().consumed().equals(basis.consumption().observation())
                    || !budget.targetDigest().equals(basis.consumption().input().targetDigest())) throw invalid();
            basis.requireAuthorization(budget.command().authorizedBy(), budget.command().createdAt());
        }
    }

    /**
     * 原结算保留冻结，调整完成状态由独立账本呈现。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { WAITING_BUDGET, READY, APPLIED, REVIEW_REQUIRED, RETIRED }
}
