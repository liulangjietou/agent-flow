package io.agentflow.expense;

import io.agentflow.approval.SubmissionRoundCompleted;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.common.DomainException;
import io.agentflow.finance.*;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * 原申请锁内编排预算例外、实际任务审计及新预算命令，网络仍由既有执行器负责。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseBudgetReviewService {
    private final JdbcExpenseBudgetReviewRepository reviews;
    private final JdbcBudgetOperationRepository operations;
    private final BudgetOperationService budgets;
    private final ExpenseReportRepository reports;

    /** 只组合既有来源和预算登记，不依赖引擎收尾服务，避免事务编排循环。 */
    public ExpenseBudgetReviewService(JdbcExpenseBudgetReviewRepository reviews, JdbcBudgetOperationRepository operations,
            BudgetOperationService budgets, ExpenseReportRepository reports) {
        this.reviews = reviews; this.operations = operations; this.budgets = budgets; this.reports = reports;
    }

    /** 正式提交成功前固定本轮原操作，任何后续失败共同回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void submitted(ExpenseSubmissionControl control, BudgetOperation original, BudgetExceptionPolicy policy, String nodeId) {
        var input = control.input();
        reviews.create(ExpenseBudgetReview.submitted(new ExpenseBudgetReview.Input(input.tenantId(), input.reportId(), input.applicationId(),
                input.employeeId(), input.roundNo(), input.submittedFinancialVersion(), input.precheckId(), nodeId, policy,
                original.input().command().id(), original.input().targetDigest()), control.submittedAt()));
    }

    /** 只接本轮原提交或原授权操作；核减、释放及其他轮次保留原有处理。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public ExpenseBudgetReview completed(BudgetOperation operation) {
        var command = operation.input().command();
        var current = reviews.find(command.tenantId(), command.position().reportId(), command.position().roundNo()).orElse(null);
        if (current==null || !current.input().originalOperationId().equals(command.id()) && !Objects.equals(current.authorizedOperationId(), command.id())) return null;
        var next = current.observe(operation, operation.updatedAt().isAfter(current.updatedAt()) ? operation.updatedAt() : current.updatedAt());
        if (!next.equals(current)) reviews.update(next);
        return next;
    }

    /** 当前原生预算节点只有明确待人工状态才可同意，正常冻结由系统自动推进。 */
    public String approvalFailure(ExpenseReport report, ExpenseSubmissionControl control, String nodeId) {
        if (control.stage(nodeId)!=ExpenseProcessPolicy.Stage.BUDGET_REVIEW) return null;
        var review = reviews.find(report.tenantId(), report.id(), control.input().roundNo()).orElse(null);
        if (review==null || !nodeId.equals(review.input().budgetNodeId()) || report.version()!=review.input().submittedFinancialVersion()) return "EXPENSE_BUDGET_REVIEW_UNAVAILABLE";
        return switch (review.status()) {
            case REVIEW_REQUIRED -> null;
            case WAITING_BUDGET -> "EXPENSE_BUDGET_RESULT_PENDING";
            case CONFIRMED -> "EXPENSE_BUDGET_AUTOMATIC_PENDING";
            case REJECTED -> "EXPENSE_BUDGET_REJECTED";
            case AUTHORIZED, CLOSED -> "EXPENSE_BUDGET_REVIEW_NOT_ALLOWED";
        };
    }

    /** 通用任务入口已完成当前任务授权；实际同意审计写入后，同事务登记一次原凭据授权。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void approved(Application application, String taskId, String nodeId, String actor, UUID auditId) {
        var report = reports.find(application.tenantId(), application.businessReference().id()).orElseThrow();
        var current = reviews.find(report.tenantId(), report.id(), application.roundNo()).orElseThrow(ExpenseBudgetReviewService::unavailable);
        if (application.status()!=ApplicationStatus.IN_APPROVAL || !application.id().equals(current.input().applicationId())
                || !nodeId.equals(current.input().budgetNodeId()) || report.version()!=current.input().submittedFinancialVersion()) throw unavailable();
        var original = operations.find(report.tenantId(), current.input().originalOperationId()).orElseThrow();
        var approved = current.authorize(original, UUID.randomUUID(), taskId, actor, auditId, now());
        budgets.reserveException(approved, approved.updatedAt()); reviews.update(approved);
    }

    /** 撤回、退回、驳回和父轮次取消共同关闭原授权，不能在下一轮复用。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void closed(SubmissionRoundCompleted event) {
        if (event.status()==SubmissionRound.Status.APPROVED) return;
        var report = reports.findByApplication(event.tenantId(), event.applicationId()).orElse(null); if (report==null) return;
        var current = reviews.find(event.tenantId(), report.id(), event.roundNo()).orElse(null); if (current==null) return;
        var next = current.close(ExpenseBudgetReview.Closure.valueOf(event.status().name()), now());
        if (!next.equals(current)) reviews.update(next);
    }

    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static DomainException unavailable() { return new DomainException("EXPENSE_BUDGET_REVIEW_UNAVAILABLE", "Budget review no longer matches the submitted round"); }
}
