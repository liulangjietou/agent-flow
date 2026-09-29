package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 预算冲正的独立外部事实，普通冻结释放或原消费回执不能冒充冲正完成。
 * @author owlzhangfq@gmail.com
 */
public record BudgetConsumptionReversalObservation(UUID operationId, String commandDigest, Status status, Instant observedAt,
        Long ledgerRevision, String reference, String periodReference, LocalDate accountingDate, Instant appliedAt, Rejection rejection) {
    /** 未知、明确拒绝及真正生效各有唯一形状，不从传输成功推断业务成功。 */
    public BudgetConsumptionReversalObservation {
        if (operationId == null || commandDigest == null || !commandDigest.matches("[a-f0-9]{64}") || status == null || observedAt == null) throw invalid();
        if (status == Status.APPLIED) {
            if (ledgerRevision == null || ledgerRevision < 1 || !reference(reference) || !reference(periodReference) || accountingDate == null
                    || appliedAt == null || appliedAt.isAfter(observedAt) || rejection != null) throw invalid();
        } else if (ledgerRevision != null || reference != null || periodReference != null || accountingDate != null || appliedAt != null
                || (status == Status.REJECTED) != (rejection != null)) throw invalid();
    }

    /** 预算版本仅递增一次，生效时间、原件编号及指定期间必须对应这次独立授权。 */
    public boolean matches(BudgetConsumptionReversalCommand command, boolean queried, Instant now) {
        if (command == null || now == null || !operationId.equals(command.id()) || !commandDigest.equals(command.digest())
                || observedAt.isAfter(now) || observedAt.isBefore(command.createdAt()) || status == Status.NOT_FOUND && !queried) return false;
        return status != Status.APPLIED || ledgerRevision == command.consumed().ledgerRevision() + 1
                && !reference.equals(command.consumed().reference()) && periodReference.equals(command.period().periodReference())
                && accountingDate.equals(command.period().request().accountingDate()) && !appliedAt.isBefore(command.createdAt());
    }

    /**
     * NOT_FOUND 仅可来自按原操作编号执行的权威查询。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { APPLIED, REJECTED, PENDING, NOT_FOUND }
    /**
     * 冲正不增加预算需求，预算不足不能作为此操作的明确拒绝理由。
     * @author owlzhangfq@gmail.com
     */
    public enum Rejection { ACCOUNTING_PERIOD_CLOSED, BUDGET_POLICY_UNAVAILABLE, COST_OBJECT_UNAVAILABLE, LEGAL_ENTITY_UNAVAILABLE,
        EMPLOYEE_UNAVAILABLE, LEDGER_VERSION_CONFLICT, CONSUMPTION_NOT_FOUND, CONSUMPTION_ALREADY_REVERSED }
    private static boolean reference(String value) { return StringUtils.isNotBlank(value) && value.equals(value.trim()) && value.length() <= 128 && value.chars().noneMatch(Character::isISOControl); }
    private static DomainException invalid() { return new DomainException("INVALID_BUDGET_REVERSAL_OBSERVATION", "Budget consumption reversal observation is inconsistent"); }
}
