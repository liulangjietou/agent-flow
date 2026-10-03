package io.agentflow.expense;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 提交和释放计划的原子落库，保留每个中间资源版本与操作原因。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseResourceChanges {
    private final InvoiceRepository invoices;
    private final ExpenseRequestRepository requests;
    private final EmployeeAdvanceRepository advances;

    /** 资源仍经各自仓储约束票号互斥与金额守恒。 */
    public ExpenseResourceChanges(InvoiceRepository invoices, ExpenseRequestRepository requests, EmployeeAdvanceRepository advances) {
        this.invoices = invoices; this.requests = requests; this.advances = advances;
    }

    /** 计划与实际审批、财务版本必须在同一个事务提交。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void persist(ExpenseSubmissionResources.Plan plan, String actor) {
        for (var change : plan.invoices()) invoices.update(Invoice.restore(change.after()), change.after().version() - 1, actor, change.operation().name());
        for (var change : plan.requests()) requests.update(ExpenseRequest.restore(change.after()), change.after().version() - 1, actor, change.operation().name());
        for (var change : plan.advances()) advances.update(EmployeeAdvance.restore(change.after()), change.after().version() - 1, actor, change.operation().name());
    }
}
