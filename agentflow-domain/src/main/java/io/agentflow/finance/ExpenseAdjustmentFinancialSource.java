package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.expense.CostAllocation;
import io.agentflow.expense.ExpenseAdjustmentAmounts;
import io.agentflow.expense.ExpenseReport;
import io.agentflow.expense.ExpenseSettlement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 部分调整逐项绑定原核销、预算消费及挂账；这里派生差额，不声明银行、ERP 或预算已经完成。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseAdjustmentFinancialSource(ExpenseAdjustmentAmounts.Change change, ExpenseSettlement settlement,
                                               BudgetOperation consumption, VoucherOperation accrual) {
    /** 原资源、预算及挂账必须有已接受事实，当前争议的挂账不能生成新的调整依据。 */
    public ExpenseAdjustmentFinancialSource {
        if (change == null || settlement == null || !settlement.resourcesConsumed()
                || settlement.status() != ExpenseSettlement.Status.SETTLED && settlement.status() != ExpenseSettlement.Status.REVIEW_REQUIRED
                || consumption == null || consumption.status() != BudgetOperation.Status.APPLIED
                || accrual == null || !accrual.usablePosted()) throw changed();
        var report = ExpenseReport.restore(change.before().original()); settlement.requireReport(report);
        var budget = consumption.input().command(); var voucher = accrual.input().command(); var input = settlement.input();
        var source = input.source(); var binding = voucher.binding(); var initial = ExpenseAdjustmentAmounts.from(report);
        if (budget.action() != BudgetCommand.Action.CONSUME || !budget.id().equals(settlement.budgetOperationId()) || !budget.tenantId().equals(report.tenantId())
                || !budget.position().equals(BudgetPrecheckPort.Request.fromCurrent(report, budget.position().accountingDate()))
                || voucher.kind() != VoucherCommand.Kind.EXPENSE_ACCRUAL || !voucher.id().equals(input.voucherOperationId()) || !voucher.digest().equals(input.voucherDigest())
                || !voucher.tenantId().equals(report.tenantId()) || !voucher.legalEntityId().equals(report.content().legalEntityId()) || !voucher.employeeId().equals(report.employeeId())
                || !binding.businessId().equals(report.id()) || !binding.applicationId().equals(report.applicationId()) || binding.roundNo() != source.roundNo()
                || binding.applicationVersion() != source.applicationVersion() || binding.businessVersion() != source.businessVersion()
                || !voucher.accountingDate().equals(budget.position().accountingDate()) || !voucher.totals().gross().equals(initial.gross())
                || !voucher.totals().tax().equals(initial.tax()) || !voucher.totals().offset().equals(initial.offsetTotal())) throw changed();
        var expected = postings(initial);
        for (var line : voucher.lines()) if (!line.amount().equals(expected.remove(PostingKey.from(line)))) throw changed();
        if (!expected.isEmpty()) throw changed();
    }

    /** 原挂账科目与辅助核算不重取映射，只对本次差额翻转方向，并保留原分录编号。 */
    public List<VoucherReversalCommand.Line> voucherLines() {
        var before = postings(change.before()); var after = postings(change.after()); var command = accrual.input().command();
        var result = new ArrayList<VoucherReversalCommand.Line>(); var zero = Money.zero(change.gross().currency());
        for (var line : command.lines()) {
            var key = PostingKey.from(line); var amount = before.getOrDefault(key, zero).minus(after.getOrDefault(key, zero));
            if (amount.value().signum() > 0) result.add(new VoucherReversalCommand.Line(line.lineNo(), command.mapping().account(line.account()),
                    line.side() == VoucherCommand.Side.DEBIT ? VoucherCommand.Side.CREDIT : VoucherCommand.Side.DEBIT,
                    amount, line.sourceLineNo(), line.costCenter(), line.projectCode(), line.advanceId()));
        }
        return List.copyOf(result);
    }

    /** 原预算位置保持完整，包含之前已减至零的位置；前置剩余额只能由真实完成记录提供。 */
    public List<BudgetPrecheckPort.Allocation> budgetBefore() { return budgetPositions(change.before()); }

    /** 调整后的完整剩余分摊继续沿用原行和原位置编号，不把借款抵扣当作预算减少。 */
    public List<BudgetPrecheckPort.Allocation> budgetAfter() { return budgetPositions(change.after()); }

    /** 精确预算释放差额与含税核减额一致，零位置保留便于外部逐项核对。 */
    public List<BudgetPrecheckPort.Allocation> budgetReduction() {
        var before = budgetBefore(); var after = budgetAfter(); var result = new ArrayList<BudgetPrecheckPort.Allocation>();
        for (int index = 0; index < before.size(); index++) {
            var old = before.get(index); var cost = old.cost();
            result.add(new BudgetPrecheckPort.Allocation(old.expenseLineNo(), old.allocationNo(), old.categoryCode(),
                    new CostAllocation(cost.costCenter(), cost.projectCode(), cost.amount().minus(after.get(index).cost().amount()))));
        }
        return List.copyOf(result);
    }

    private static List<BudgetPrecheckPort.Allocation> budgetPositions(ExpenseAdjustmentAmounts amounts) {
        var round = ExpenseReport.restore(amounts.original()).requireFrozenRound(); var result = new ArrayList<BudgetPrecheckPort.Allocation>();
        for (int index = 0; index < amounts.lines().size(); index++) {
            var line = amounts.lines().get(index); var category = round.originalLines().get(index).original().categoryCode();
            for (int position = 0; position < line.allocations().size(); position++) {
                var allocation = line.allocations().get(position);
                result.add(new BudgetPrecheckPort.Allocation(line.lineNo(), position + 1, category,
                        new CostAllocation(allocation.costCenter(), allocation.projectCode(), allocation.gross())));
            }
        }
        return List.copyOf(result);
    }

    private static Map<PostingKey, Money> postings(ExpenseAdjustmentAmounts amounts) {
        var round = ExpenseReport.restore(amounts.original()).requireFrozenRound(); var result = new HashMap<PostingKey, Money>();
        for (int index = 0; index < amounts.lines().size(); index++) {
            var line = amounts.lines().get(index); var category = round.originalLines().get(index).original().categoryCode();
            for (var allocation : line.allocations()) {
                add(result, new PostingKey(new AccountMappingPort.Key(AccountMappingPort.Role.EXPENSE, category), line.lineNo(), allocation.costCenter(), allocation.projectCode(), null), allocation.gross().minus(allocation.tax()));
                add(result, new PostingKey(new AccountMappingPort.Key(AccountMappingPort.Role.DEDUCTIBLE_TAX, ""), line.lineNo(), allocation.costCenter(), allocation.projectCode(), null), allocation.tax());
            }
        }
        for (var offset : amounts.offsets()) add(result, new PostingKey(new AccountMappingPort.Key(AccountMappingPort.Role.EMPLOYEE_RECEIVABLE, ""), 0, null, null, offset.advanceId()), offset.amount());
        add(result, new PostingKey(new AccountMappingPort.Key(AccountMappingPort.Role.EMPLOYEE_PAYABLE, ""), 0, null, null, null), amounts.payable());
        return result;
    }

    private static void add(Map<PostingKey, Money> values, PostingKey key, Money amount) { if (amount.value().signum() > 0) values.put(key, amount); }
    private static DomainException changed() { return new DomainException("EXPENSE_ADJUSTMENT_SOURCE_CHANGED", "Partial adjustment must retain the original settled expense, consumed budget and exact posted accrual positions"); }
    @Override public String toString() { return "ExpenseAdjustmentFinancialSource[reportId=" + change.before().original().id() + "]"; }

    /**
     * 原分录位置由业务用途及辅助核算标识，金额和排序不参与身份。
     * @author owlzhangfq@gmail.com
     */
    private record PostingKey(AccountMappingPort.Key account, int sourceLine, String costCenter, String projectCode, UUID advanceId) {
        private static PostingKey from(VoucherCommand.Line line) { return new PostingKey(line.account(), line.sourceLineNo(), line.costCenter(), line.projectCode(), line.advanceId()); }
    }
}
