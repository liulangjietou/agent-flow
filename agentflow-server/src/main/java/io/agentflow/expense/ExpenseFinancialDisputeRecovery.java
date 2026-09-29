package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.JdbcBudgetOperationRepository;
import io.agentflow.finance.PaymentCommand;
import io.agentflow.finance.PaymentDisputeResolved;
import io.agentflow.finance.VoucherCommand;
import io.agentflow.finance.VoucherDisputeResolved;
import io.agentflow.finance.VoucherOperation;
import io.agentflow.finance.VoucherReversalRetired;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 人工资金或挂账裁决后统一复核原报销，独立资金、会计及批准问题仍保持冻结。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseFinancialDisputeRecovery {
    private final ExpenseReportRepository reports;
    private final JdbcExpenseSettlementRepository settlements;
    private final ExpenseSettlementSources sources;
    private final JdbcBudgetOperationRepository budgets;
    /** 只读当前原预算操作，不新建资金、预算或资源消费命令。 */
    public ExpenseFinancialDisputeRecovery(ExpenseReportRepository reports, JdbcExpenseSettlementRepository settlements, ExpenseSettlementSources sources, JdbcBudgetOperationRepository budgets) {
        this.reports = reports; this.settlements = settlements; this.sources = sources; this.budgets = budgets;
    }
    /** 裁决、恢复及审计共用事务，预算迟到成功在锁内重新读取。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void resolved(PaymentDisputeResolved event) {
        var payment = event.payment(); var command = payment.input().command();
        if (!payment.settleable() || command.purpose() != PaymentCommand.Purpose.EXPENSE_REIMBURSEMENT) return;
        recover(command.tenantId(), command.binding().businessId(), event.resolution().resolvedAt(),
                current -> current.input().payment() != null && current.input().payment().operationId().equals(command.id()));
    }
    /** 原挂账裁决为有效过账后，资金与预算仍须共同满足；付款凭证只影响归档。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void resolved(VoucherDisputeResolved event) {
        recoverVoucher(event.voucher(), event.resolution().resolvedAt());
    }
    /** 冲销安全结束后的恢复仍复核资金、预算和批准来源，不重复核销资源。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void retired(VoucherReversalRetired event) {
        recoverVoucher(event.voucher(), event.retirement().retiredAt());
    }
    /** 确认未发生退回只解除本来源疑点；实际退回必须继续独立办理。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void registered(ExpensePaymentReturnRegistered event) {
        if (event.ledger().reviewRequired()) return;
        var command = event.ledger().request().command();
        recover(command.tenantId(), command.binding().businessId(), event.registration().registeredAt(),
                current -> current.input().payment() != null && current.input().payment().operationId().equals(command.id()));
    }
    private void recoverVoucher(VoucherOperation voucher, Instant at) {
        var command = voucher.input().command();
        if (!voucher.usablePosted() || command.kind() != VoucherCommand.Kind.EXPENSE_ACCRUAL) return;
        recover(command.tenantId(), command.binding().businessId(), at,
                current -> command.id().equals(current.input().voucherOperationId()));
    }
    private void recover(String tenant, UUID id, Instant at, Predicate<ExpenseSettlement> matches) {
        reports.lock(tenant, id);
        var current = settlements.find(tenant, id).orElse(null);
        if (current == null || current.status() != ExpenseSettlement.Status.REVIEW_REQUIRED || !matches.test(current)) return;
        var report = reports.find(tenant, id).orElseThrow(() -> new DomainException("NOT_FOUND", "Original expense report not found"));
        // 任一裁决不能替其他资金、凭证或批准争议作决定，完整原来源须重新成立。
        try { sources.requireCurrent(current, report); }
        catch (DomainException unavailable) { return; }
        var budget = current.budgetOperationId() == null ? null : budgets.find(tenant, current.budgetOperationId()).orElseThrow(
                () -> new DomainException("EXPENSE_BUDGET_SOURCE_CHANGED", "Original consumption operation not found"));
        settlements.update(current.resolveFinancialReview(budget, at));
    }
}
