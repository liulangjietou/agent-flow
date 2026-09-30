package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.expense.ExpenseContent;
import io.agentflow.expense.ExpenseLine;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

/**
 * 独立预算减额结果保留原消费及每个位置的前后净额；总额相同不能替代逐项核对。
 * @author owlzhangfq@gmail.com
 */
public record BudgetConsumptionReductionObservation(UUID operationId, UUID adjustmentId, String commandDigest,
        Status status, Instant observedAt, Posting posting, Rejection rejection) {
    /** 仅实际生效携带台账事实；未知、拒绝和权威查无具有各自唯一形状。 */
    public BudgetConsumptionReductionObservation {
        if (operationId == null || adjustmentId == null || !digest(commandDigest) || status == null || observedAt == null
                || (status == Status.APPLIED) != (posting != null) || (status == Status.REJECTED) != (rejection != null)
                || posting != null && posting.appliedAt().isAfter(observedAt)) throw invalid();
    }

    /** 固定原消费、完整前后位置、新版本及明确期间全部相符，才可用于本地完成。 */
    public boolean matches(BudgetConsumptionReductionCommand command, boolean queried, Instant now) {
        if (command == null || now == null || !operationId.equals(command.id()) || !adjustmentId.equals(command.adjustmentId())
                || !commandDigest.equals(command.digest()) || observedAt.isAfter(now) || observedAt.isBefore(command.createdAt())
                || status == Status.NOT_FOUND && !queried) return false;
        if (status != Status.APPLIED) return true;
        return posting.consumptionId().equals(command.source().id()) && posting.consumptionDigest().equals(command.source().digest())
                && posting.consumptionReference().equals(command.consumed().reference())
                && posting.ledgerRevision() == command.expected().revision() + 1
                && !posting.reference().equals(command.expected().reference()) && !posting.reference().equals(command.consumed().reference())
                && posting.before().equals(command.before()) && posting.after().equals(command.after())
                && posting.periodReference().equals(command.period().periodReference())
                && posting.accountingDate().equals(command.period().request().accountingDate())
                && !posting.appliedAt().isBefore(command.createdAt());
    }

    /**
     * 每次结果只保存本次前后位置，不递归携带所有历史命令。
     * @author owlzhangfq@gmail.com
     */
    public record Posting(UUID consumptionId, String consumptionDigest, String consumptionReference, long ledgerRevision,
            String reference, List<BudgetPrecheckPort.Allocation> before, List<BudgetPrecheckPort.Allocation> after,
            String periodReference, LocalDate accountingDate, Instant appliedAt) {
        /** 同一位置只能减少；零位置继续保留，不能借核减合并或更换费用对象。 */
        public Posting {
            if (consumptionId == null || !digest(consumptionDigest) || !text(consumptionReference, 128) || ledgerRevision < 1
                    || !text(reference, 128) || !text(periodReference, 128) || accountingDate == null || appliedAt == null
                    || !validReduction(before, after)) throw invalid();
            before = List.copyOf(before);
            after = List.copyOf(after);
        }
        @Override public String toString() { return "BudgetConsumptionReductionPosting[consumptionId=" + consumptionId + ", ledgerRevision=" + ledgerRevision + "]"; }
    }

    /**
     * 查无只可由原目标的权威只读查询返回，不能解释为业务成功或自动换号。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { APPLIED, REJECTED, PENDING, NOT_FOUND }

    /**
     * 减少实际占用不增加预算需求；冲突要求复核原结果，不能伪装成预算不足。
     * @author owlzhangfq@gmail.com
     */
    public enum Rejection { ACCOUNTING_PERIOD_CLOSED, BUDGET_POLICY_UNAVAILABLE, COST_OBJECT_UNAVAILABLE,
        LEGAL_ENTITY_UNAVAILABLE, EMPLOYEE_UNAVAILABLE, LEDGER_VERSION_CONFLICT, CONSUMPTION_NOT_FOUND, CONSUMPTION_ALREADY_REVERSED }

    static boolean validReduction(List<BudgetPrecheckPort.Allocation> before, List<BudgetPrecheckPort.Allocation> after) {
        if (CollectionUtils.isEmpty(before) || after == null || before.size() != after.size()
                || before.size() > ExpenseContent.MAX_LINES * ExpenseLine.MAX_ALLOCATIONS
                || before.stream().anyMatch(Objects::isNull) || after.stream().anyMatch(Objects::isNull)) return false;
        var currency = before.get(0).cost().amount().currency();
        var positions = new HashSet<String>();
        boolean reduced = false;
        for (int index = 0; index < before.size(); index++) {
            var old = before.get(index);
            var remaining = after.get(index);
            if (!samePosition(old, remaining) || !currency.equals(old.cost().amount().currency())
                    || !positions.add(old.expenseLineNo() + ":" + old.allocationNo())
                    || remaining.cost().amount().compareTo(old.cost().amount()) > 0) return false;
            reduced |= remaining.cost().amount().compareTo(old.cost().amount()) < 0;
        }
        return reduced;
    }

    static boolean samePosition(BudgetPrecheckPort.Allocation left, BudgetPrecheckPort.Allocation right) {
        return left.expenseLineNo() == right.expenseLineNo() && left.allocationNo() == right.allocationNo()
                && left.categoryCode().equals(right.categoryCode()) && left.cost().costCenter().equals(right.cost().costCenter())
                && Objects.equals(left.cost().projectCode(), right.cost().projectCode())
                && left.cost().amount().currency().equals(right.cost().amount().currency());
    }
    static boolean text(String value, int max) { return StringUtils.isNotBlank(value) && value.length() <= max && value.equals(value.trim()) && value.chars().noneMatch(Character::isISOControl); }
    private static boolean digest(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static DomainException invalid() { return new DomainException("INVALID_BUDGET_REDUCTION_OBSERVATION", "Budget consumption reduction requires an exact original and consistent reduced positions"); }
    @Override public String toString() { return "BudgetConsumptionReductionObservation[operationId=" + operationId + ", status=" + status + "]"; }
}
