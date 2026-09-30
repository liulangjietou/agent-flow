package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.*;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 部分调整的跨聚合来源核对；前次证明仅由真实完成状态提取，仓储还须核对其持久修订。
 * @author owlzhangfq@gmail.com
 */
public record ExpensePartialAdjustmentBasis(ExpenseAdjustmentFundingSource funding, Previous previous) {
    /** 首次从原核定开始，后继逐项承接剩余额和实际已使用入款，不能只比较总额。 */
    public ExpensePartialAdjustmentBasis {
        if (funding == null) throw changed();
        var financial = funding.financial(); var before = financial.change().before();
        if (previous == null) {
            if (!before.equals(ExpenseAdjustmentAmounts.from(ExpenseReport.restore(before.original()))) || !funding.previousReturns().isEmpty()) throw changed();
        } else {
            if (!previous.remaining().equals(before) || !previous.usedReturns().equals(funding.previousReturns())
                    || !previous.budget().posting().consumptionId().equals(financial.consumption().input().command().id())
                    || !previous.budget().posting().consumptionDigest().equals(financial.consumption().input().command().digest())
                    || !previous.budget().posting().consumptionReference().equals(financial.consumption().observation().reference())
                    || !sameOriginal(financial.accrual(), previous.accrual().posting().original())) throw changed();
        }
    }

    /** 调用方必须传数据库中的真实前次完成，准备、外部成功未落资源或争议状态均不能作为后继来源。 */
    public static ExpensePartialAdjustmentBasis from(ExpenseAdjustmentFundingSource funding, ExpensePartialAdjustment previous) {
        if (previous == null) return new ExpensePartialAdjustmentBasis(funding, null);
        if (previous.status() != ExpensePartialAdjustment.Status.APPLIED) throw changed();
        var completed = previous.completion();
        return new ExpensePartialAdjustmentBasis(funding, new Previous(previous.id(), previous.version(), previous.input().basis().funding().financial().change().after(),
                previous.input().basis().usedReturns(), completed.budget(), completed.accrual(), completed.at()));
    }

    /** 只归集以前已完成和本次明确选取的原件，其余累计回款保持未使用。 */
    public List<ExpensePaymentReturns.Entry> usedReturns() {
        if (funding.returns() == null) return List.of();
        return funding.returns().entries().stream().filter(value -> funding.previousReturns().contains(value) || funding.selectedReturns().contains(value)).toList();
    }

    /** 每次预算授权独立绑定相同调整和逐项差额，允许使用后续新开放期间。 */
    public void requireBudget(UUID adjustmentId, BudgetConsumptionReductionOperation.Input input) {
        if (input == null) throw changed();
        var financial = funding.financial(); var original = financial.consumption(); var command = input.command();
        if (!command.adjustmentId().equals(adjustmentId) || input.consumedVersion() < original.version()
                || !input.targetDigest().equals(original.input().targetDigest()) || !command.source().equals(original.input().command())
                || !command.consumed().equals(original.observation()) || !command.before().equals(financial.budgetBefore())
                || !command.after().equals(financial.budgetAfter()) || !Objects.equals(command.previous(), previous == null ? null : previous.budget())) throw changed();
        funding.requireAuthorization(command.authorizedBy(), command.createdAt());
    }

    /** 新挂账授权保留原科目和差额，只允许同一原凭证的较新有效观察，不延长旧命令。 */
    public void requireAccrual(UUID adjustmentId, ExpenseAccrualReductionOperation.Input input) {
        if (input == null) throw changed();
        var financial = funding.financial(); var original = financial.accrual(); var command = input.command();
        if (!command.adjustmentId().equals(adjustmentId) || input.originalVersion() < original.version()
                || !input.targetDigest().equals(original.input().targetDigest()) || !command.source().command().equals(original.input().command())
                || !new VoucherReversalPort.Request(original.input().command(), original.observation()).matchesOriginal(command.source().original())
                || !command.before().equals(financial.voucherBefore()) || !command.after().equals(financial.voucherAfter())
                || !Objects.equals(command.previous(), previous == null ? null : previous.accrual())) throw changed();
        funding.requireAuthorization(command.authorizedBy(), command.createdAt());
    }

    public String tenantId() { return funding.financial().change().before().original().tenantId(); }
    public UUID reportId() { return funding.financial().change().before().original().id(); }

    private static boolean sameOriginal(VoucherOperation source, VoucherObservation previous) {
        var command = source.input().command(); var current = source.observation();
        return new VoucherReversalPort.Request(command, current).matchesOriginal(previous)
                || new VoucherReversalPort.Request(command, previous).matchesOriginal(current);
    }

    /**
     * 后继只携带已完成净额和接受证明，不递归复制之前全部调整；规范化引用由数据库验证。
     * @author owlzhangfq@gmail.com
     */
    public record Previous(UUID id, long version, ExpenseAdjustmentAmounts remaining, List<ExpensePaymentReturns.Entry> usedReturns,
            BudgetConsumptionReductionObservation budget, ExpenseAccrualReductionObservation accrual, Instant completedAt) {
        /** 前次两侧成功和资源完成时刻同时存在，原件列表保留首次登记身份。 */
        public Previous {
            if (id == null || version < 2 || remaining == null || usedReturns == null || usedReturns.stream().anyMatch(Objects::isNull)
                    || usedReturns.stream().distinct().count() != usedReturns.size() || budget == null || accrual == null || completedAt == null
                    || budget.status() != BudgetConsumptionReductionObservation.Status.APPLIED || accrual.status() != ExpenseAccrualReductionObservation.Status.POSTED
                    || !budget.adjustmentId().equals(id) || !accrual.adjustmentId().equals(id) || budget.operationId().equals(accrual.operationId())
                    || budget.observedAt().isAfter(completedAt) || accrual.observedAt().isAfter(completedAt)) throw changed();
            usedReturns = List.copyOf(usedReturns);
        }
        @Override public String toString() { return "PreviousExpensePartialAdjustment[id=" + id + ", version=" + version + "]"; }
    }
    private static DomainException changed() { return new DomainException("EXPENSE_PARTIAL_ADJUSTMENT_SOURCE_CHANGED", "Partial adjustment must use the exact completed predecessor, original finance facts and registered bank entries"); }
    @Override public String toString() { return "ExpensePartialAdjustmentBasis[reportId=" + reportId() + "]"; }
}
