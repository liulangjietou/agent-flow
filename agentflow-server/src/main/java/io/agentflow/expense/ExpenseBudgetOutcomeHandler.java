package io.agentflow.expense;

import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.finance.BudgetCommand;
import io.agentflow.finance.BudgetObservation;
import io.agentflow.finance.BudgetOperation;
import io.agentflow.finance.BudgetOperationCompleted;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预算确认结果回到实际业务轮次：额度不足退回补正，终态的迟到冻结继续释放。
 * @author owlzhangfq@gmail.com
 */
@Component
public class ExpenseBudgetOutcomeHandler {
    private static final String SYSTEM_ACTOR = "system:budget";
    private final ApplicationRepository applications;
    private final ApprovalApplicationFacade lifecycle;
    private final ExpenseReportRepository reports;
    private final JdbcExpenseSubmissionControlRepository controls;
    private final ExpenseReleaseService releases;
    private final ExpenseBudgetReviewService reviews;

    /** 事件同步处理并加入原事务，不能在确认落库后丢失必要的业务动作。 */
    public ExpenseBudgetOutcomeHandler(ApplicationRepository applications, ApprovalApplicationFacade lifecycle,
            ExpenseReportRepository reports, JdbcExpenseSubmissionControlRepository controls, ExpenseReleaseService releases, ExpenseBudgetReviewService reviews) {
        this.applications = applications; this.lifecycle = lifecycle; this.reports = reports; this.controls = controls; this.releases = releases;
        this.reviews = reviews;
    }

    /** 对账恢复和直接结果走同一分支；老轮次结果不能退回已重提的新轮次。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void completed(BudgetOperationCompleted event) {
        var operation = event.operation(); var command = operation.input().command(); var position = command.position();
        var report = reports.find(command.tenantId(), position.reportId()).orElseThrow();
        var application = applications.findById(command.tenantId(), report.applicationId()).orElseThrow();
        var review = reviews.completed(operation);
        if (application.status() == ApplicationStatus.REJECTED || application.status() == ApplicationStatus.CANCELLED) {
            // 释放本身被外部明确拒绝时保留结果供处理，不自动生成无限的新释放命令。
            if (command.action() == BudgetCommand.Action.FREEZE || command.action() == BudgetCommand.Action.ADJUST) {
                releases.releaseBudget(command.tenantId(), report.id(), operation.updatedAt());
            }
            return;
        }
        if (operation.status() == BudgetOperation.Status.REJECTED
                && (operation.observation().rejection() == BudgetObservation.Rejection.BUDGET_INSUFFICIENT
                    || review!=null && review.status()==ExpenseBudgetReview.Status.REJECTED
                        && (review.approval()!=null || operation.observation().rejection()==BudgetObservation.Rejection.BUDGET_EXCEPTION_REQUIRED))
                && application.status() == ApplicationStatus.IN_APPROVAL && application.roundNo() == position.roundNo()
                && report.version() == position.financialVersion()
                && controls.find(command.tenantId(), report.id(), position.roundNo()).isPresent()) {
            lifecycle.returnBusiness(command.tenantId(), application.id(), application.version(),
                    new BusinessReference(BusinessReference.Type.EXPENSE, report.id()), SYSTEM_ACTOR, "预算余额不足，请调整费用后重新预检提交。");
        }
    }
}
