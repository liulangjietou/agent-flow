package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

/**
 * 已核销报销的独立金额投影；只计算原位置的减少，不改写批准轮次或声明资金、预算已经冲回。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseAdjustmentAmounts(ExpenseReport.State original, List<Line> lines, List<AdvanceOffset> offsets) {
    /** 恢复时仍绑定原批准的成本位置、税额和借款顺序，不能通过快照增加余额。 */
    public ExpenseAdjustmentAmounts {
        if (original == null || lines == null || offsets == null) throw invalid();
        var round = ExpenseReport.restore(original).requireFrozenRound();
        if (round.approvedGross().value().signum() == 0) throw invalid();
        requireDecrease(initialLines(round), lines);
        if (!remainingOffsets(round.advanceOffsets(), totalGross(lines, round.baseCurrency())).equals(offsets)) throw invalid();
        lines = List.copyOf(lines); offsets = List.copyOf(offsets);
    }

    /** 初始投影严格使用已经冻结的本币核定，不重新折算原币或重算提交时的成本分摊。 */
    public static ExpenseAdjustmentAmounts from(ExpenseReport report) {
        var round = report.requireFrozenRound();
        return new ExpenseAdjustmentAmounts(report.state(), initialLines(round), round.advanceOffsets());
    }

    /** 逐行明确填写剩余含税额与税额；全部合法后一次生成独立差额，未指定的行保持原值。 */
    public Change reduce(List<ExpenseReport.Reduction> targets) {
        if (CollectionUtils.isEmpty(targets) || targets.size() > ExpenseContent.MAX_LINES) throw invalid();
        var requested = new HashMap<Integer, ExpenseReport.Reduction>();
        for (var target : targets) {
            if (target == null || requested.put(target.lineNo(), target) != null) throw invalid();
        }
        var reduced = new ArrayList<Line>();
        for (var line : lines) {
            var target = requested.remove(line.lineNo());
            reduced.add(target == null ? line : reduceLine(line, target));
        }
        if (!requested.isEmpty()) throw invalid();
        var next = new ExpenseAdjustmentAmounts(original, reduced, remainingOffsets(offsets, totalGross(reduced, gross().currency())));
        return new Change(this, next);
    }

    public Money gross() { return totalGross(lines, original.rounds().get(original.rounds().size() - 1).baseCurrency()); }
    public Money tax() { return lines.stream().map(Line::tax).reduce(Money.zero(gross().currency()), Money::plus); }
    public Money offsetTotal() { return offsets.stream().map(AdvanceOffset::amount).reduce(Money.zero(gross().currency()), Money::plus); }
    public Money payable() { return gross().minus(offsetTotal()); }

    /** 调试标识不输出原员工账户、票据和费用明细。 */
    @Override public String toString() { return "ExpenseAdjustmentAmounts[reportId=" + original.id() + "]"; }

    private static Line reduceLine(Line before, ExpenseReport.Reduction target) {
        var gross = before.gross(); var tax = before.tax();
        if (target.approvedGross() == null || target.approvedTax() == null || target.approvedGross().compareTo(gross) > 0
                || target.approvedTax().compareTo(tax) > 0 || target.approvedTax().compareTo(target.approvedGross()) > 0) throw invalid();
        var grossDelta = gross.minus(target.approvedGross()); var taxDelta = tax.minus(target.approvedTax());
        // 本功能只取消原费用及进项税；税额重分类导致费用增加需要单独会计处理。
        if (taxDelta.compareTo(grossDelta) > 0) throw invalid();
        var taxCuts = allocateCut(before.allocations(), taxDelta, true);
        var expenseCuts = allocateCut(before.allocations(), grossDelta.minus(taxDelta), false);
        var values = new ArrayList<Allocation>();
        for (int index = 0; index < before.allocations().size(); index++) {
            var allocation = before.allocations().get(index);
            values.add(new Allocation(allocation.costCenter(), allocation.projectCode(),
                    allocation.gross().minus(taxCuts.get(index).plus(expenseCuts.get(index))), allocation.tax().minus(taxCuts.get(index))));
        }
        return new Line(before.lineNo(), values);
    }

    private static List<Money> allocateCut(List<Allocation> current, Money cut, boolean tax) {
        if (cut.value().signum() == 0) return current.stream().map(ignored -> Money.zero(cut.currency())).toList();
        var weights = current.stream().map(value -> new CostAllocation(value.costCenter(), value.projectCode(), tax ? value.tax() : value.gross().minus(value.tax()))).toList();
        // 对差额分配整分再逐项相减，避免重算剩余总额使某个原成本位置反而增加。
        return CostAllocation.apportion(weights, cut).stream().map(CostAllocation::amount).toList();
    }

    private static List<Line> initialLines(ExpenseRound round) {
        var result = new ArrayList<Line>();
        for (var line : round.approvedLines()) {
            var taxes = line.tax().value().signum() == 0 ? line.allocations().stream().map(ignored -> Money.zero(round.baseCurrency())).toList()
                    : CostAllocation.apportion(line.allocations(), line.tax()).stream().map(CostAllocation::amount).toList();
            var allocations = new ArrayList<Allocation>();
            for (int index = 0; index < line.allocations().size(); index++) {
                var value = line.allocations().get(index);
                allocations.add(new Allocation(value.costCenter(), value.projectCode(), value.amount(), taxes.get(index)));
            }
            var value = new Line(line.lineNo(), allocations);
            if (!value.gross().equals(line.gross()) || !value.tax().equals(line.tax())) throw invalid();
            result.add(value);
        }
        return List.copyOf(result);
    }

    private static List<AdvanceOffset> remainingOffsets(List<AdvanceOffset> previous, Money gross) {
        var total = previous.stream().map(AdvanceOffset::amount).reduce(Money.zero(gross.currency()), Money::plus);
        if (total.compareTo(gross) <= 0) return List.copyOf(previous);
        var excess = total.minus(gross); var result = new ArrayList<>(previous);
        for (int index = result.size() - 1; index >= 0 && excess.value().signum() > 0; index--) {
            var value = result.get(index); var restored = value.amount().min(excess);
            result.set(index, new AdvanceOffset(value.advanceId(), value.amount().minus(restored))); excess = excess.minus(restored);
        }
        return List.copyOf(result);
    }

    private static void requireDecrease(List<Line> before, List<Line> after) {
        if (before.size() != after.size()) throw invalid();
        for (int lineIndex = 0; lineIndex < before.size(); lineIndex++) {
            var previous = before.get(lineIndex); var next = after.get(lineIndex);
            if (next == null || previous.lineNo() != next.lineNo() || previous.allocations().size() != next.allocations().size()) throw invalid();
            for (int index = 0; index < previous.allocations().size(); index++) {
                var old = previous.allocations().get(index); var value = next.allocations().get(index);
                if (!old.costCenter().equals(value.costCenter()) || !Objects.equals(old.projectCode(), value.projectCode())
                        || value.tax().compareTo(old.tax()) > 0 || value.gross().minus(value.tax()).compareTo(old.gross().minus(old.tax())) > 0) throw invalid();
            }
        }
    }

    private static Money totalGross(List<Line> lines, String currency) { return lines.stream().map(Line::gross).reduce(Money.zero(currency), Money::plus); }
    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_ADJUSTMENT_AMOUNTS", "Expense adjustment must reduce original cost, tax and offset positions without rewriting the approved report"); }

    /**
     * 税额与不含税成本分别保留在原位置，零位置不能删除后重新编号。
     * @author owlzhangfq@gmail.com
     */
    public record Allocation(String costCenter, String projectCode, Money gross, Money tax) {
        /** 含税额和税额同币种且非负，位置标识沿用成本分摊约束。 */
        public Allocation {
            if (StringUtils.isBlank(costCenter) || costCenter.length() > 128 || projectCode != null && (projectCode.isBlank() || projectCode.length() > 128)
                    || gross == null || tax == null || tax.compareTo(gross) > 0) throw invalid();
        }
    }

    /**
     * 原费用行包含全部成本位置，未指定调整的行保持原样。
     * @author owlzhangfq@gmail.com
     */
    public record Line(int lineNo, List<Allocation> allocations) {
        /** 拒绝重复位置和跨币种，保留原成本中心与项目的顺序。 */
        public Line {
            if (lineNo < 1 || lineNo > ExpenseContent.MAX_LINES || CollectionUtils.isEmpty(allocations) || allocations.size() > ExpenseLine.MAX_ALLOCATIONS) throw invalid();
            var keys = new HashSet<List<String>>(); String currency = null;
            for (var value : allocations) {
                if (value == null || !keys.add(List.of(value.costCenter(), value.projectCode() == null ? "" : value.projectCode()))) throw invalid();
                if (currency == null) currency = value.gross().currency();
                else if (!currency.equals(value.gross().currency())) throw invalid();
            }
            allocations = List.copyOf(allocations);
        }
        public Money gross() { return allocations.stream().map(Allocation::gross).reduce(Money.zero(allocations.get(0).gross().currency()), Money::plus); }
        public Money tax() { return allocations.stream().map(Allocation::tax).reduce(Money.zero(allocations.get(0).gross().currency()), Money::plus); }
    }

    /**
     * 当前与下一金额投影的精确差额，只有后续真实外部及资源证据才能将它登记为完成。
     * @author owlzhangfq@gmail.com
     */
    public record Change(ExpenseAdjustmentAmounts before, ExpenseAdjustmentAmounts after) {
        /** 差额只能减少同一原批准，不允许原币重算、增加某个科目或无影响登记。 */
        public Change {
            if (before == null || after == null || !before.original().equals(after.original()) || after.gross().compareTo(before.gross()) >= 0) throw invalid();
            requireDecrease(before.lines(), after.lines());
            if (!remainingOffsets(before.offsets(), after.gross()).equals(after.offsets())) throw invalid();
        }
        public Money gross() { return before.gross().minus(after.gross()); }
        public Money tax() { return before.tax().minus(after.tax()); }
        /** 所需实际回款与需恢复借款抵扣之和恰好等于含税核减额。 */
        public Money bankReturn() { return before.payable().minus(after.payable()); }
        /** 只列实际减少的借款抵扣，原借款顺序和身份不变。 */
        public List<AdvanceOffset> advanceReversals() {
            var result = new ArrayList<AdvanceOffset>();
            for (int index = 0; index < before.offsets().size(); index++) {
                var old = before.offsets().get(index); var amount = old.amount().minus(after.offsets().get(index).amount());
                if (amount.value().signum() > 0) result.add(new AdvanceOffset(old.advanceId(), amount));
            }
            return List.copyOf(result);
        }
        /** 预算与凭证采用同一逐行差额；只返回有实际减少的行，行内零位置保持。 */
        public List<Line> lines() {
            var result = new ArrayList<Line>();
            for (int lineIndex = 0; lineIndex < before.lines().size(); lineIndex++) {
                var old = before.lines().get(lineIndex); var next = after.lines().get(lineIndex);
                if (old.gross().equals(next.gross())) continue;
                var values = new ArrayList<Allocation>();
                for (int index = 0; index < old.allocations().size(); index++) {
                    var value = old.allocations().get(index); var reduced = next.allocations().get(index);
                    values.add(new Allocation(value.costCenter(), value.projectCode(), value.gross().minus(reduced.gross()), value.tax().minus(reduced.tax())));
                }
                result.add(new Line(old.lineNo(), values));
            }
            return List.copyOf(result);
        }
    }
}
