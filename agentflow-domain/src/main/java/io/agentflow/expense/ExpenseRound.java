package io.agentflow.expense;

import io.agentflow.finance.EmployeeAccountSnapshot;
import io.agentflow.finance.Money;
import java.time.Instant;
import java.util.List;

/**
 * 财务轮次的原始快照与当前核定结果；核减仅生成新值，永不覆盖原始明细。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseRound(int roundNo, long submittedFinancialVersion, String submittedBy, Instant submittedAt,
                           ExpenseContent content, String baseCurrency, EmployeeAccountSnapshot account,
                           List<FrozenLine> originalLines, List<ApprovedLine> approvedLines,
                           List<AdvanceOffset> advanceOffsets, List<ExpenseAdjustment> adjustments) {
    /** 冻结所有集合，仓储恢复时同样保持不可变。 */
    public ExpenseRound {
        originalLines = List.copyOf(originalLines); approvedLines = List.copyOf(approvedLines);
        advanceOffsets = List.copyOf(advanceOffsets); adjustments = List.copyOf(adjustments);
    }

    /** 汇总当前核定含税额；行金额已在冻结时舍入，不再二次汇兑。 */
    public Money approvedGross() { return approvedLines.stream().map(ApprovedLine::gross).reduce(Money.zero(baseCurrency), Money::plus); }

    /** 汇总当前可抵扣税额。 */
    public Money approvedTax() { return approvedLines.stream().map(ApprovedLine::tax).reduce(Money.zero(baseCurrency), Money::plus); }

    /** 借款冲销始终使用与本轮核定总额一致的本位币。 */
    public Money offsetTotal() { return advanceOffsets.stream().map(AdvanceOffset::amount).reduce(Money.zero(baseCurrency), Money::plus); }

    /** 应付款只从已经核定且平衡的财务数据派生。 */
    public Money payable() { return approvedGross().minus(offsetTotal()); }

    /**
     * 原币行、制度及汇率的初始核定依据。
     * @author owlzhangfq@gmail.com
     */
    public record FrozenLine(ExpenseLine original, ExpenseAssessment assessment, Money claimedBase, Money deductibleTaxBase) { }

    /**
     * 当前核定金额及其本币成本分摊。
     * @author owlzhangfq@gmail.com
     */
    public record ApprovedLine(int lineNo, Money gross, Money tax, List<CostAllocation> allocations) {
        /** 成本分摊是核定事实的一部分，不允许读取后修改。 */
        public ApprovedLine { allocations = List.copyOf(allocations); }
    }
}
