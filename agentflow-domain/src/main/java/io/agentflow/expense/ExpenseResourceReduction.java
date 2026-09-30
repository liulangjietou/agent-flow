package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import io.agentflow.finance.ReservedAmount;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import static io.agentflow.expense.ExpenseSubmissionResources.*;

/**
 * 已核销部分调整的资源差额计划；只计算副本，真实财务证明与原子保存由应用服务负责。
 * @author owlzhangfq@gmail.com
 */
public final class ExpenseResourceReduction {
    /** 当前净核销必须精确匹配已完成金额投影，所有来源校验成功后才返回完整计划。 */
    public Plan plan(ExpenseAdjustmentAmounts.Change change, Resources resources, UUID adjustmentId, Instant at) {
        if (change == null || resources == null || adjustmentId == null || at == null) throw conflict();
        var report = ExpenseReport.restore(change.before().original()); var round = report.requireFrozenRound();
        if (at.isBefore(round.submittedAt()) || round.adjustments().stream().anyMatch(value -> at.isBefore(value.adjustedAt()))) throw conflict();
        var invoices = new ArrayList<InvoiceChange>(); var requests = new ArrayList<PriorChange>(); var advances = new ArrayList<AdvanceChange>();
        var priorValues = new HashMap<UUID, ExpenseRequest>();
        for (int index = 0; index < round.approvedLines().size(); index++) {
            var approved = round.approvedLines().get(index); if (approved.gross().value().signum() == 0) continue;
            var original = round.originalLines().get(index).original(); var before = change.before().lines().get(index); var after = change.after().lines().get(index);
            var use = new ExpenseUse(report.id(), round.roundNo(), approved.lineNo());
            // 已完整取消的旧行可能已经将原件重新用于别的报销，后续调整不能再次触碰它。
            if (before.gross().value().signum() > 0) for (var id : original.invoiceIds()) {
                var invoice = Invoice.restore(require(resources.invoices(), id));
                if (!id.equals(invoice.id()) || invoice.facts() == null || invoice.occupation() != Invoice.Occupation.CONSUMED || !use.equals(invoice.use())) throw conflict();
                owner(report, invoice.tenantId(), invoice.ownerId(), invoice.facts().legalEntityId());
                if (after.gross().value().signum() == 0) {
                    invoice.reverseConsumption(invoice.version(), use, adjustmentId, at);
                    invoices.add(new InvoiceChange(invoice.state(), Operation.REVERSE_CONSUMPTION));
                }
            }
            var reference = original.priorRequest();
            if (reference != null) {
                var request = priorValues.computeIfAbsent(reference.requestId(), id -> ExpenseRequest.restore(require(resources.requests(), id)));
                if (!reference.requestId().equals(request.id())) throw conflict();
                owner(report, request.tenantId(), request.employeeId(), request.legalEntityId());
                consumed(request.balance(reference.lineNo()), use, approved.gross(), before.gross(), at);
                var amount = before.gross().minus(after.gross());
                if (amount.value().signum() > 0) {
                    request.reduceConsumption(request.version(), reference.lineNo(), use, amount, adjustmentId, at);
                    requests.add(new PriorChange(request.state(), Operation.REDUCE_CONSUMPTION));
                }
            }
        }
        var use = new ExpenseUse(report.id(), round.roundNo(), 0);
        for (int index = 0; index < round.advanceOffsets().size(); index++) {
            var original = round.advanceOffsets().get(index); if (original.amount().value().signum() == 0) continue;
            var before = change.before().offsets().get(index); var after = change.after().offsets().get(index);
            var advance = EmployeeAdvance.restore(require(resources.advances(), original.advanceId()));
            if (!original.advanceId().equals(advance.id())) throw conflict();
            owner(report, advance.tenantId(), advance.employeeId(), advance.legalEntityId());
            consumed(advance.balance(), use, original.amount(), before.amount(), at);
            var amount = before.amount().minus(after.amount());
            if (amount.value().signum() > 0) {
                advance.reduceOffset(advance.version(), use, amount, adjustmentId, at);
                advances.add(new AdvanceChange(advance.state(), Operation.REDUCE_CONSUMPTION));
            }
        }
        return new Plan(invoices, requests, advances);
    }

    private static void consumed(ReservedAmount balance, ExpenseUse use, Money original, Money remaining, Instant at) {
        if (!balance.consumedAmountFor(use).equals(original) || !balance.netConsumedAmountFor(use).equals(remaining)
                || balance.reductions().stream().anyMatch(value -> value.use().equals(use) && at.isBefore(value.reducedAt()))
                || balance.reversals().stream().anyMatch(value -> value.use().equals(use) && at.isBefore(value.reversedAt()))) throw conflict();
    }
    private static <T> T require(Map<UUID, T> values, UUID id) { var value = values.get(id); if (value == null) throw conflict(); return value; }
    private static void owner(ExpenseReport report, String tenant, String employee, UUID entity) {
        if (!report.tenantId().equals(tenant) || !report.employeeId().equals(employee) || !report.content().legalEntityId().equals(entity)) throw conflict();
    }
    private static DomainException conflict() {
        return new DomainException("EXPENSE_CONSUMPTION_CHANGED", "Partial adjustment requires original ownership and exact remaining consumption from completed adjustments");
    }
}
