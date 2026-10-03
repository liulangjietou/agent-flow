package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import io.agentflow.finance.ReservedAmount;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import io.agentflow.expense.ExpenseSubmissionResources.AdvanceChange;
import io.agentflow.expense.ExpenseSubmissionResources.InvoiceChange;
import io.agentflow.expense.ExpenseSubmissionResources.Operation;
import io.agentflow.expense.ExpenseSubmissionResources.Plan;
import io.agentflow.expense.ExpenseSubmissionResources.PriorChange;
import io.agentflow.expense.ExpenseSubmissionResources.Resources;

/**
 * 同轮核减仅降低原有预留；与重提迁移分开，不能重新占用已释放或他人的资源。
 * @author owlzhangfq@gmail.com
 */
public final class ExpenseReductionResources {
    /** 在不可变资源副本上核对旧账再计算差额，每个中间版本都交给原事务保存。 */
    public Plan plan(ExpenseReport before, ExpenseReport after, Resources resources) {
        var previous = before.requireFrozenRound(); var next = after.requireFrozenRound();
        if (!before.id().equals(after.id()) || !before.tenantId().equals(after.tenantId())
                || !before.content().equals(after.content()) || after.version() != before.version() + 1
                || previous.roundNo() != next.roundNo() || next.adjustments().size() != previous.adjustments().size() + 1) throw conflict();
        var invoices = new ArrayList<InvoiceChange>(); var requests = new ArrayList<PriorChange>(); var advances = new ArrayList<AdvanceChange>();
        var priorValues = new HashMap<UUID, ExpenseRequest>();
        for (int index = 0; index < previous.approvedLines().size(); index++) {
            var old = previous.approvedLines().get(index); var reduced = next.approvedLines().get(index);
            if (reduced.gross().compareTo(old.gross()) > 0) throw conflict();
            // 以前已经归零的行可能被其他报销重新使用；本次核减无权触碰那些资源。
            if (old.gross().value().signum() == 0) continue;
            var original = previous.originalLines().get(index).original();
            var use = new ExpenseUse(before.id(), previous.roundNo(), old.lineNo());
            for (UUID id : original.invoiceIds()) {
                var invoice = Invoice.restore(require(resources.invoices(), id));
                owner(before, invoice.tenantId(), invoice.ownerId());
                if (invoice.occupation() != Invoice.Occupation.OCCUPIED || !use.equals(invoice.use())) throw conflict();
                if (reduced.gross().value().signum() == 0) {
                    invoice.release(invoice.version(), use); invoices.add(new InvoiceChange(invoice.state(), Operation.RELEASE));
                }
            }
            var reference = original.priorRequest();
            if (reference != null) {
                var request = priorValues.computeIfAbsent(reference.requestId(), id -> ExpenseRequest.restore(require(resources.requests(), id)));
                owner(before, request.tenantId(), request.employeeId());
                if (!before.content().legalEntityId().equals(request.legalEntityId())) throw conflict();
                reserved(request.balance(reference.lineNo()), use, old.gross());
                if (!old.gross().equals(reduced.gross())) {
                    request.reserve(request.version(), reference.lineNo(), use, reduced.gross());
                    requests.add(new PriorChange(request.state(), Operation.REDUCE));
                }
            }
        }
        for (int index = 0; index < previous.advanceOffsets().size(); index++) {
            var old = previous.advanceOffsets().get(index); var reduced = next.advanceOffsets().get(index);
            if (!old.advanceId().equals(reduced.advanceId()) || reduced.amount().compareTo(old.amount()) > 0) throw conflict();
            if (old.amount().value().signum() == 0) continue;
            var advance = EmployeeAdvance.restore(require(resources.advances(), old.advanceId()));
            owner(before, advance.tenantId(), advance.employeeId());
            if (!before.content().legalEntityId().equals(advance.legalEntityId())) throw conflict();
            var use = new ExpenseUse(before.id(), previous.roundNo(), 0);
            reserved(advance.balance(), use, old.amount());
            if (!old.amount().equals(reduced.amount())) {
                advance.reserve(advance.version(), use, reduced.amount());
                advances.add(new AdvanceChange(advance.state(), Operation.REDUCE));
            }
        }
        return new Plan(invoices, requests, advances);
    }

    private static void reserved(ReservedAmount balance, ExpenseUse use, Money expected) {
        if (balance.consumedFor(use) || !balance.reservedFor(use).equals(expected)) throw conflict();
    }
    private static <T> T require(Map<UUID, T> values, UUID id) {
        var value = values.get(id); if (value == null) throw conflict(); return value;
    }
    private static void owner(ExpenseReport report, String tenant, String employee) {
        if (!report.tenantId().equals(tenant) || !report.employeeId().equals(employee)) throw conflict();
    }
    private static DomainException conflict() { return new DomainException("EXPENSE_RESERVATION_CHANGED", "Expense resource reservations no longer match the approved amounts"); }
}
