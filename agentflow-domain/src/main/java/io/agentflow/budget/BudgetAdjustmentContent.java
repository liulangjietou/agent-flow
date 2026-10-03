package io.agentflow.budget;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 申请人只提出预算追加、调减或同期间调拨的意图，原额度与占用全部由台账提供。
 * @author owlzhangfq@gmail.com
 */
public record BudgetAdjustmentContent(UUID legalEntityId, String title, String purpose, Type type, LocalDate accountingDate,
                                      String sourceBudgetReference, String targetBudgetReference, Money amount) {
    /** 追加只填调入预算，调减只填调出预算；调拨两端必须不同，禁止负额反转业务含义。 */
    public BudgetAdjustmentContent {
        if (legalEntityId == null || invalidText(title, 256) || invalidText(purpose, 2000) || type == null || accountingDate == null
                || amount == null || amount.value().signum() <= 0) throw invalid();
        boolean sourceRequired = type != Type.INCREASE;
        boolean targetRequired = type != Type.DECREASE;
        if (sourceRequired ? invalidText(sourceBudgetReference, 128) : sourceBudgetReference != null) throw invalid();
        if (targetRequired ? invalidText(targetBudgetReference, 128) : targetBudgetReference != null) throw invalid();
        if (sourceRequired && targetRequired && sourceBudgetReference.equals(targetBudgetReference)) throw invalid();
    }

    /** 原台账按实际申请人授权，不能由客户端选择另一个查询主体。 */
    public BudgetLedgerPort.Request ledgerRequest(String employeeId) {
        List<String> references = switch (type) {
            case INCREASE -> List.of(targetBudgetReference);
            case DECREASE -> List.of(sourceBudgetReference);
            case TRANSFER -> List.of(sourceBudgetReference, targetBudgetReference);
        };
        return new BudgetLedgerPort.Request(legalEntityId, employeeId, accountingDate, references);
    }

    /** 从可信快照计算本次变动；调拨不改变合计额度，也不侵占任何已占用或已使用金额。 */
    public List<Change> changes(BudgetLedgerPort.Snapshot ledger) {
        if (ledger == null || !ledger.request().equals(ledgerRequest(ledger.request().employeeId()))) throw invalid();
        for (var position : ledger.positions()) {
            amount.sameCurrency(position.limit());
            if (position.periodStatus() != BudgetLedgerPort.PeriodStatus.OPEN) {
                throw new DomainException("BUDGET_PERIOD_CLOSED", "Budget adjustments require an open period");
            }
        }
        return switch (type) {
            case INCREASE -> List.of(increase(ledger.position(targetBudgetReference)));
            case DECREASE -> List.of(decrease(ledger.position(sourceBudgetReference)));
            case TRANSFER -> {
                var source = ledger.position(sourceBudgetReference);
                var target = ledger.position(targetBudgetReference);
                if (!source.periodReference().equals(target.periodReference()) || !source.periodStart().equals(target.periodStart())
                        || !source.periodEnd().equals(target.periodEnd())) {
                    throw new DomainException("BUDGET_TRANSFER_PERIOD_MISMATCH", "Budget transfers must stay within the same period");
                }
                yield List.of(decrease(source), increase(target));
            }
        };
    }

    private Change increase(BudgetLedgerPort.Position position) {
        return new Change(position.reference(), position.version(), position.limit(), position.limit().plus(amount));
    }

    private Change decrease(BudgetLedgerPort.Position position) {
        if (amount.compareTo(position.available()) > 0) {
            throw new DomainException("BUDGET_ADJUSTMENT_INSUFFICIENT", "Budget reduction exceeds the uncommitted and unconsumed balance");
        }
        return new Change(position.reference(), position.version(), position.limit(), position.limit().minus(amount));
    }

    /** 不能通过草稿字段伪造余额、原台账版本或外部执行成功。 */
    @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown budget adjustment content field"); }

    private static boolean invalidText(String value, int maximum) {
        return StringUtils.isBlank(value) || value.length() > maximum || !value.equals(value.trim()) || value.chars().anyMatch(Character::isISOControl);
    }

    private static DomainException invalid() {
        return new DomainException("INVALID_BUDGET_ADJUSTMENT", "Budget adjustment requires a consistent type, period, budget references and positive amount");
    }

    /**
     * 调整类型决定额度方向，不以符号金额或报销冻结命令代替。
     * @author owlzhangfq@gmail.com
     */
    public enum Type { INCREASE, DECREASE, TRANSFER }

    /**
     * 固定原额度版本及变动前后精确金额，供审批展示和后续独立执行使用。
     * @author owlzhangfq@gmail.com
     */
    public record Change(String budgetReference, String expectedVersion, Money beforeLimit, Money afterLimit) {
        /** 每条变动必须确实改变同币种额度。 */
        public Change {
            if (invalidText(budgetReference, 128) || invalidText(expectedVersion, 128) || beforeLimit == null || afterLimit == null
                    || beforeLimit.compareTo(afterLimit) == 0) throw invalid();
        }
    }
}
