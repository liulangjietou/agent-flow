package io.agentflow.expense;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.common.DomainException;
import io.agentflow.finance.BudgetCommand;
import io.agentflow.finance.BudgetOccupation;
import io.agentflow.finance.BudgetOperationService;
import io.agentflow.finance.JdbcBudgetOccupationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

/**
 * 终止业务的资源释放；未知外部预算结果继续对账，确认原冻结后再登记释放。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseReleaseService {
    private final ExpenseReportRepository reports;
    private final ExpensePrecheckResources resources;
    private final ExpenseResourceChanges changes;
    private final JdbcBudgetOccupationRepository occupations;
    private final BudgetOperationService budgets;

    /** 本地预留与外部冻结各自保存真实状态，不把释放排队当成已释放。 */
    public ExpenseReleaseService(ExpenseReportRepository reports, ExpensePrecheckResources resources, ExpenseResourceChanges changes,
            JdbcBudgetOccupationRepository occupations, BudgetOperationService budgets) {
        this.reports = reports; this.resources = resources; this.changes = changes; this.occupations = occupations; this.budgets = budgets;
    }

    /** 驳回和作废释放本轮资源，退回和撤回保留供下一轮整体迁移。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(Application application, String actor, Instant now) {
        if (application.businessReference() == null || application.businessReference().type() != BusinessReference.Type.EXPENSE) return;
        if (application.status() != ApplicationStatus.REJECTED && application.status() != ApplicationStatus.CANCELLED
                && application.status() != ApplicationStatus.REVOKED) {
            throw new DomainException("EXPENSE_RELEASE_NOT_ALLOWED", "Only rejected or cancelled expense reports release reservations");
        }
        UUID id = application.businessReference().id(); reports.lock(application.tenantId(), id);
        var report = reports.find(application.tenantId(), id).orElseThrow();
        var previous = resources.loadReserved(report);
        resources.lockReferences(application.tenantId(), ExpensePrecheckResources.versions(previous));
        changes.persist(new ExpenseSubmissionResources().release(report, resources.loadReserved(report), now), actor);
        releaseBudget(application.tenantId(), id, now);
    }

    /** 原操作结果落库后再次进入此处；没有冻结或仍在对账时不构造第二笔命令。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseBudget(String tenant, UUID reportId, Instant now) {
        reports.lock(tenant, reportId);
        var occupation = occupations.find(tenant, reportId).orElse(null);
        if (occupation != null && occupation.pendingOperationId() == null && occupation.status() == BudgetOccupation.Status.FROZEN) {
            budgets.finalizeOccupation(tenant, reportId, BudgetCommand.Action.RELEASE, now);
        }
    }
}
