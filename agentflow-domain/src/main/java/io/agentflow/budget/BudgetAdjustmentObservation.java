package io.agentflow.budget;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 一条原子预算指令的外部回执；只应用一端的调拨、错额或改写占用都不能视为成功。
 * @author owlzhangfq@gmail.com
 */
public record BudgetAdjustmentObservation(UUID operationId, String commandDigest, Status status, long revision,
        Instant observedAt, String reference, Instant appliedAt, List<AppliedChange> changes, Rejection rejection) {
    /** 各状态具有封闭形状，传输成功不等于预算额度已经生效。 */
    public BudgetAdjustmentObservation {
        if (operationId == null || commandDigest == null || !commandDigest.matches("[a-f0-9]{64}") || status == null || observedAt == null
                || (status == Status.NOT_FOUND ? revision != 0 : revision < 1) || changes == null || changes.stream().anyMatch(java.util.Objects::isNull)) throw invalid();
        if (status == Status.APPLIED) {
            if (invalidText(reference, 128) || appliedAt == null || appliedAt.isAfter(observedAt) || rejection != null
                    || changes.isEmpty() || changes.size() > BudgetLedgerPort.MAX_POSITIONS
                    || new HashSet<>(changes.stream().map(AppliedChange::budgetReference).toList()).size() != changes.size()) throw invalid();
        } else if (reference != null || appliedAt != null || !changes.isEmpty() || (status == Status.REJECTED) != (rejection != null)) throw invalid();
        changes = changes.stream().sorted(Comparator.comparing(AppliedChange::budgetReference)).toList();
    }

    /** 每个预算都必须确认原版本、原额度与精确变动，已用和占用金额仍是命令中的原值。 */
    public boolean matches(BudgetAdjustmentCommand command, boolean queried, Instant now) {
        if (command == null || now == null || !operationId.equals(command.id()) || !commandDigest.equals(command.digest())
                || observedAt.isBefore(command.authorizedAt()) || observedAt.isAfter(now) || status == Status.NOT_FOUND && !queried) return false;
        if (status != Status.APPLIED) return true;
        if (appliedAt.isBefore(command.authorizedAt()) || changes.size() != command.changes().size()) return false;
        for (var change : changes) {
            var expected = command.changes().stream().filter(value -> value.budgetReference().equals(change.budgetReference())).findFirst();
            if (expected.isEmpty()) return false;
            var position = command.ledger().position(change.budgetReference());
            if (!change.beforeVersion().equals(expected.get().expectedVersion()) || !change.beforeLimit().equals(expected.get().beforeLimit())
                    || !change.afterLimit().equals(expected.get().afterLimit()) || !change.periodReference().equals(position.periodReference())
                    || !change.accountingDate().equals(command.source().round().content().accountingDate())
                    || !change.committed().equals(position.committed()) || !change.consumed().equals(position.consumed())) return false;
        }
        return true;
    }

    /**
     * 两端共用外部操作版本；查无只能由查询返回，拒绝必须保证整条指令没有生效。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { APPLIED, REJECTED, PENDING, NOT_FOUND }
    /**
     * 原子预算写入明确的无副作用拒绝，不能用普通 HTTP 失败代替。
     * @author owlzhangfq@gmail.com
     */
    public enum Rejection { AUTHORIZATION_EXPIRED, BUDGET_PERIOD_CLOSED, BUDGET_INSUFFICIENT, LEDGER_VERSION_CONFLICT,
        BUDGET_POLICY_UNAVAILABLE, BUDGET_POSITION_UNAVAILABLE, LEGAL_ENTITY_UNAVAILABLE, EMPLOYEE_UNAVAILABLE }
    /**
     * 独立预算的生效前后事实；版本是原系统不透明标识，必须确实发生变化。
     * @author owlzhangfq@gmail.com
     */
    public record AppliedChange(String budgetReference, String beforeVersion, String afterVersion, String periodReference,
            LocalDate accountingDate, Money beforeLimit, Money afterLimit, Money committed, Money consumed) {
        /** 未改额度、重复版本或不一致币种都不能构成一次调整。 */
        public AppliedChange {
            if (invalidText(budgetReference, 128) || invalidText(beforeVersion, 128) || invalidText(afterVersion, 128)
                    || beforeVersion.equals(afterVersion) || invalidText(periodReference, 128) || accountingDate == null
                    || beforeLimit == null || afterLimit == null || committed == null || consumed == null || beforeLimit.equals(afterLimit)) throw invalid();
            beforeLimit.sameCurrency(afterLimit); beforeLimit.sameCurrency(committed); beforeLimit.sameCurrency(consumed);
        }
    }
    private static boolean invalidText(String value, int maximum) {
        return StringUtils.isBlank(value) || value.length() > maximum || !value.equals(value.trim()) || value.chars().anyMatch(Character::isISOControl);
    }
    private static DomainException invalid() { return new DomainException("INVALID_BUDGET_ADJUSTMENT_OBSERVATION", "Budget adjustment receipt must preserve atomic identities, versions, periods and exact amounts"); }
    /** 日志不打印额度与占用明细。 */
    @Override public String toString() { return "BudgetAdjustmentObservation[operationId=" + operationId + ", status=" + status + "]"; }
}
