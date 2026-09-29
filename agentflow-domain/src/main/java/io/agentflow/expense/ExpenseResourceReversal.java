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
 * 独立取消已核销报销时计算资源反向计划；原核销事实保留，外部资金及会计依据由调整服务编排。
 * @author owlzhangfq@gmail.com
 */
public final class ExpenseResourceReversal {
    /** 全部资源按原冻结轮次和精确金额冲回；失败不改变输入，也不触碰已核减至零的原引用。 */
    public Plan plan(ExpenseReport report, Resources resources, UUID adjustmentId, Instant at) {
        var round = report.requireFrozenRound();
        if (adjustmentId == null || at == null || at.isBefore(round.submittedAt()) || round.approvedGross().value().signum() == 0
                || round.adjustments().stream().anyMatch(value -> at.isBefore(value.adjustedAt()))) throw conflict();
        var invoices = new ArrayList<InvoiceChange>(); var requests = new ArrayList<PriorChange>(); var advances = new ArrayList<AdvanceChange>();
        var priorValues = new HashMap<UUID, ExpenseRequest>();
        for (int index = 0; index < round.approvedLines().size(); index++) {
            var approved = round.approvedLines().get(index);
            if (approved.gross().value().signum() == 0) continue;
            var original = round.originalLines().get(index).original();
            var use = new ExpenseUse(report.id(), round.roundNo(), approved.lineNo());
            for (var id : original.invoiceIds()) {
                var invoice = Invoice.restore(require(resources.invoices(), id));
                if (invoice.facts() == null) throw conflict();
                owner(report, invoice.tenantId(), invoice.ownerId(), invoice.facts().legalEntityId());
                invoice.reverseConsumption(invoice.version(), use, adjustmentId, at);
                invoices.add(new InvoiceChange(invoice.state(), Operation.REVERSE_CONSUMPTION));
            }
            if (original.priorRequest() != null) {
                var reference = original.priorRequest();
                var request = priorValues.computeIfAbsent(reference.requestId(), id -> ExpenseRequest.restore(require(resources.requests(), id)));
                owner(report, request.tenantId(), request.employeeId(), request.legalEntityId());
                consumed(request.balance(reference.lineNo()), use, approved.gross());
                request.reverseConsumption(request.version(), reference.lineNo(), use, adjustmentId, at);
                requests.add(new PriorChange(request.state(), Operation.REVERSE_CONSUMPTION));
            }
        }
        var use = new ExpenseUse(report.id(), round.roundNo(), 0);
        for (var offset : round.advanceOffsets()) {
            if (offset.amount().value().signum() == 0) continue;
            var advance = EmployeeAdvance.restore(require(resources.advances(), offset.advanceId()));
            owner(report, advance.tenantId(), advance.employeeId(), advance.legalEntityId());
            consumed(advance.balance(), use, offset.amount());
            advance.reverseOffset(advance.version(), use, adjustmentId, at);
            advances.add(new AdvanceChange(advance.state(), Operation.REVERSE_CONSUMPTION));
        }
        return new Plan(invoices, requests, advances);
    }

    private static void consumed(ReservedAmount balance, ExpenseUse use, Money amount) {
        if (!balance.consumedAmountFor(use).equals(amount) || balance.reversals().stream().anyMatch(value -> value.use().equals(use))) throw conflict();
    }
    private static <T> T require(Map<UUID, T> values, UUID id) { var value = values.get(id); if (value == null) throw conflict(); return value; }
    private static void owner(ExpenseReport report, String tenant, String employee, UUID entity) {
        if (!report.tenantId().equals(tenant) || !report.employeeId().equals(employee) || !report.content().legalEntityId().equals(entity)) throw conflict();
    }
    private static DomainException conflict() {
        return new DomainException("EXPENSE_CONSUMPTION_CHANGED", "Adjustment requires the original expense round and exact unreversed consumptions");
    }
}
