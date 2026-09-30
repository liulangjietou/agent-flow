package io.agentflow.budget;

import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 预检与审批使用同一份授权后投影；拟调整额度不表示外部已经实际生效。
 * @author owlzhangfq@gmail.com
 */
public record BudgetAdjustmentRoundView(int roundNo, long submittedRequestVersion, String submittedBy, Instant submittedAt,
                                        BudgetAdjustmentContent content, FinanceCatalog.LegalEntity legalEntity,
                                        String catalogVersion, String ledgerVersion, Instant observedAt, List<PositionView> positions) {
    /** 返回不可变明细，不携带整个财务目录或原始目的地。 */
    public BudgetAdjustmentRoundView { positions = List.copyOf(positions); }

    /** 调出和调入顺序取自已核对的调整意图，页面不自行计算金额。 */
    public static BudgetAdjustmentRoundView of(BudgetAdjustmentRound round) {
        var positions = round.changes().stream().map(change -> {
            var position = round.ledger().position(change.budgetReference());
            return new PositionView(position.reference(), position.name(), position.version(), position.periodReference(), position.periodStart(),
                    position.periodEnd(), position.periodStatus(), position.limit(), position.committed(), position.consumed(), position.available(), change.afterLimit());
        }).toList();
        return new BudgetAdjustmentRoundView(round.roundNo(), round.submittedRequestVersion(), round.submittedBy(), round.submittedAt(),
                round.content(), round.legalEntity(), round.catalogVersion(), round.ledger().sourceVersion(), round.ledger().observedAt(), positions);
    }

    /**
     * 展示原台账额度、已用占用及拟调整值，仍受完整敏感明细字段权限约束。
     * @author owlzhangfq@gmail.com
     */
    public record PositionView(String budgetReference, String name, String version, String periodReference, LocalDate periodStart,
                               LocalDate periodEnd, BudgetLedgerPort.PeriodStatus periodStatus, Money beforeLimit,
                               Money committed, Money consumed, Money available, Money proposedLimit) { }
}
