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
 * 付款成功与零应付关闭共用资源核销计划；只有本轮精确预留能转为永久使用。
 * @author owlzhangfq@gmail.com
 */
public final class ExpenseSettlementResources {
    /** 资金与凭证依据由结算聚合核验，本方法在副本上计算全部资源变化，失败不产生半次核销。 */
    public Plan plan(ExpenseReport report, Resources resources, Instant at) {
        var round = report.requireFrozenRound();
        if (at == null || at.isBefore(round.submittedAt()) || round.adjustments().stream().anyMatch(value -> at.isBefore(value.adjustedAt()))) {
            throw new DomainException("INVALID_EXPENSE_SETTLEMENT", "Settlement cannot precede the frozen financial facts");
        }
        var invoices = new ArrayList<InvoiceChange>(); var requests = new ArrayList<PriorChange>(); var advances = new ArrayList<AdvanceChange>();
        var priorValues = new HashMap<UUID, ExpenseRequest>();
        for (int index = 0; index < round.approvedLines().size(); index++) {
            var approved = round.approvedLines().get(index);
            // 核减至零后已经释放的原件和额度可能归属其他单据，结算不能再次触碰。
            if (approved.gross().value().signum() == 0) continue;
            var original = round.originalLines().get(index).original();
            var use = new ExpenseUse(report.id(), round.roundNo(), approved.lineNo());
            for (var id : original.invoiceIds()) {
                var invoice = Invoice.restore(require(resources.invoices(), id));
                owner(report, invoice.tenantId(), invoice.ownerId());
                if (invoice.facts() == null || !report.content().legalEntityId().equals(invoice.facts().legalEntityId())) throw conflict();
                invoice.consume(invoice.version(), use, at); invoices.add(new InvoiceChange(invoice.state(), Operation.CONSUME));
            }
            if (original.priorRequest() != null) {
                var reference = original.priorRequest();
                var request = priorValues.computeIfAbsent(reference.requestId(), id -> ExpenseRequest.restore(require(resources.requests(), id)));
                owner(report, request.tenantId(), request.employeeId());
                if (!report.content().legalEntityId().equals(request.legalEntityId())) throw conflict();
                reserved(request.balance(reference.lineNo()), use, approved.gross());
                request.consume(request.version(), reference.lineNo(), use); requests.add(new PriorChange(request.state(), Operation.CONSUME));
            }
        }
        var use = new ExpenseUse(report.id(), round.roundNo(), 0);
        for (var offset : round.advanceOffsets()) {
            if (offset.amount().value().signum() == 0) continue;
            var advance = EmployeeAdvance.restore(require(resources.advances(), offset.advanceId()));
            owner(report, advance.tenantId(), advance.employeeId());
            if (!report.content().legalEntityId().equals(advance.legalEntityId())) throw conflict();
            reserved(advance.balance(), use, offset.amount());
            advance.settle(advance.version(), use); advances.add(new AdvanceChange(advance.state(), Operation.CONSUME));
        }
        return new Plan(invoices, requests, advances);
    }

    private static void reserved(ReservedAmount value, ExpenseUse use, Money amount) {
        if (value.consumedFor(use) || !value.reservedFor(use).equals(amount)) throw conflict();
    }
    private static <T> T require(Map<UUID, T> values, UUID id) { var value = values.get(id); if (value == null) throw conflict(); return value; }
    private static void owner(ExpenseReport report, String tenant, String employee) {
        if (!report.tenantId().equals(tenant) || !report.employeeId().equals(employee)) throw conflict();
    }
    private static DomainException conflict() { return new DomainException("EXPENSE_RESERVATION_CHANGED", "Settlement resources must retain the original approved round and exact reservations"); }
}
