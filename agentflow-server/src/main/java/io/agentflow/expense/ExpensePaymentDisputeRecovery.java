package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.JdbcBudgetOperationRepository;
import io.agentflow.finance.PaymentCommand;
import io.agentflow.finance.PaymentDisputeResolved;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 人工资金裁决后重新核验原报销及会计依据，独立凭证问题仍保持冻结。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpensePaymentDisputeRecovery {
    private final ExpenseReportRepository reports;
    private final JdbcExpenseSettlementRepository settlements;
    private final ExpenseSettlementSources sources;
    private final JdbcBudgetOperationRepository budgets;
    /** 只读当前原预算操作，不新建资金、预算或资源消费命令。 */
    public ExpensePaymentDisputeRecovery(ExpenseReportRepository reports, JdbcExpenseSettlementRepository settlements, ExpenseSettlementSources sources, JdbcBudgetOperationRepository budgets) {
        this.reports = reports; this.settlements = settlements; this.sources = sources; this.budgets = budgets;
    }
    /** 裁决、恢复及审计共用事务，预算迟到成功在锁内重新读取。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void resolved(PaymentDisputeResolved event) {
        var payment = event.payment(); var command = payment.input().command();
        if (!payment.settleable() || command.purpose() != PaymentCommand.Purpose.EXPENSE_REIMBURSEMENT) return;
        String tenant = command.tenantId(); var id = command.binding().businessId(); reports.lock(tenant, id);
        var current = settlements.find(tenant, id).orElse(null);
        if (current == null || current.status() != ExpenseSettlement.Status.REVIEW_REQUIRED || current.input().payment() == null
                || !current.input().payment().operationId().equals(command.id())) return;
        var report = reports.find(tenant, id).orElseThrow(() -> new DomainException("NOT_FOUND", "Original expense report not found"));
        // 银行裁决不能替其他凭证或批准争议作决定，原问题继续由受控工作区展示。
        try { sources.requireCurrent(current, report); }
        catch (DomainException unavailable) { return; }
        var budget = current.budgetOperationId() == null ? null : budgets.find(tenant, current.budgetOperationId()).orElseThrow(
                () -> new DomainException("EXPENSE_BUDGET_SOURCE_CHANGED", "Original consumption operation not found"));
        settlements.update(current.resolvePaymentReview(budget, event.resolution().resolvedAt()));
    }
}
