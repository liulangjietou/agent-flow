package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 一条报销明细使用事前批准行的累计依据；同一来源行共享本轮合计及全部其他占用。
 * @author owlzhangfq@gmail.com
 */
public record ExpensePriorControlAssessment(int lineNo, UUID requestId, long requestVersion,
        ExpenseRequest.ApprovedLine source, Money threshold, Money consumed, Money otherReserved, Money roundReserved,
        Money lineAmount, Money totalExposure, Money exceeded) {
    /** 派生总额和超出额必须可由原批准及真实用量复算，不能由调用方直接宣称通过。 */
    public ExpensePriorControlAssessment {
        if (lineNo < 1 || lineNo > ExpenseContent.MAX_LINES || requestId == null || requestVersion < 1 || source == null
                || threshold == null || consumed == null || otherReserved == null || roundReserved == null || lineAmount == null
                || totalExposure == null || exceeded == null || lineAmount.value().signum() <= 0 || !source.limit().equals(threshold)
                || lineAmount.compareTo(roundReserved) > 0 || !consumed.plus(otherReserved).plus(roundReserved).equals(totalExposure)
                || !excess(totalExposure, threshold).equals(exceeded) || source.hardLimit() && exceeded.value().signum() > 0) throw invalid();
    }

    /** NONE 超出参考额仍只记录事实；只有明确容差模式触发独立例外审批。 */
    public boolean requiresApproval() {
        return source.control() != null && source.control().control().mode() == ExpensePriorControl.Mode.TOLERANCE && exceeded.value().signum() > 0;
    }

    /** 预检、提交及历史恢复共用同一纯计算，输入为完整资源计划执行前后的不可变版本。 */
    public static List<ExpensePriorControlAssessment> evaluate(UUID reportId, ExpenseRound round,
            Map<UUID, ExpenseRequest.State> before, Map<UUID, ExpenseRequest.State> after) {
        var results = new ArrayList<ExpensePriorControlAssessment>();
        for (int index = 0; index < round.originalLines().size(); index++) {
            var original = round.originalLines().get(index).original(); var amount = round.approvedLines().get(index).gross();
            if (original.priorRequest() == null || amount.value().signum() == 0) continue;
            var reference = original.priorRequest();
            var previous = before.get(reference.requestId()); var next = after.get(reference.requestId());
            if (previous == null || next == null || !previous.approvedLines().equals(next.approvedLines())) throw invalid();
            var source = previous.approvedLines().stream().filter(line -> line.lineNo() == reference.lineNo()).findFirst().orElseThrow(ExpensePriorControlAssessment::invalid);
            if (source.control() != null && !source.control().categoryCode().equals(original.categoryCode())) {
                throw new DomainException("PRIOR_REQUEST_CATEGORY_MISMATCH", "Expense category must match the frozen prior request category");
            }
            var balance = next.balances().get(reference.lineNo());
            var use = new ExpenseUse(reportId, round.roundNo(), original.lineNo());
            if (balance == null || !balance.reservedFor(use).equals(amount)) throw invalid();
            var reserved = balance.reservations().stream().filter(item -> item.use().reportId().equals(reportId) && item.use().roundNo() == round.roundNo())
                    .map(io.agentflow.finance.ReservedAmount.Reservation::amount).reduce(Money.zero(amount.currency()), Money::plus);
            var total = balance.consumed().plus(balance.reserved());
            var assessment = new ExpensePriorControlAssessment(original.lineNo(), reference.requestId(), previous.version(), source, balance.limit(),
                    balance.consumed(), balance.reserved().minus(reserved), reserved, amount, total, balance.exceeded());
            if (assessment.requiresApproval() && StringUtils.isBlank(original.exceptionReason())) {
                throw new DomainException("PRIOR_REQUEST_EXCEPTION_REASON_REQUIRED", "Each expense line exceeding cumulative prior tolerance requires an explanation");
            }
            results.add(assessment);
        }
        return List.copyOf(results);
    }

    private static Money excess(Money total, Money threshold) { return total.compareTo(threshold) > 0 ? total.minus(threshold) : Money.zero(threshold.currency()); }
    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_PRIOR_ASSESSMENT", "Prior request exposure must match the original line and complete reservation plan"); }
}
