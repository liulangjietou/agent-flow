package io.agentflow.approval.process;

import io.agentflow.approval.SubprocessExecutionLocks;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.common.DomainException;
import io.agentflow.expense.ExpenseBudgetApprovalPolicy;
import io.agentflow.expense.ExpenseBudgetOutcomeHandler;
import io.agentflow.expense.JdbcExpenseBudgetReviewRepository;
import io.agentflow.finance.BudgetOperationCompleted;
import io.agentflow.finance.JdbcBudgetOperationRepository;
import io.agentflow.notification.ApprovalNotificationService;
import java.time.Instant;
import org.flowable.engine.RuntimeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原结果落库后在申请树锁内恢复本地流程，不在持锁期间调用预算系统。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseBudgetReviewRecovery {
    private static final long RECHECK_SECONDS = 10;
    private final ApplicationRepository applications;
    private final SubmissionRoundRepository rounds;
    private final JdbcExpenseBudgetReviewRepository reviews;
    private final JdbcBudgetOperationRepository operations;
    private final ExpenseBudgetOutcomeHandler outcomes;
    private final ApprovalCompletionService completion;
    private final ExpenseBudgetReviewProgress progress;
    private final RuntimeService runtime;
    private final SubprocessProgressService subprocesses;
    private final ApprovalNotificationService notifications;

    /** 恢复与人工任务共用执行锁、收尾和通知，只增加持久结果到原生节点的本地编排。 */
    public ExpenseBudgetReviewRecovery(ApplicationRepository applications, SubmissionRoundRepository rounds,
            JdbcExpenseBudgetReviewRepository reviews, JdbcBudgetOperationRepository operations, ExpenseBudgetOutcomeHandler outcomes,
            ApprovalCompletionService completion, ExpenseBudgetReviewProgress progress, RuntimeService runtime,
            SubprocessProgressService subprocesses, ApprovalNotificationService notifications) {
        this.applications = applications; this.rounds = rounds; this.reviews = reviews; this.operations = operations;
        this.outcomes = outcomes; this.completion = completion; this.progress = progress; this.runtime = runtime;
        this.subprocesses = subprocesses; this.notifications = notifications;
    }

    /** 锁后重新核对原轮次和引擎状态；暂停、终止及尚未到达节点均不生成审批。 */
    @Transactional
    public void recover(JdbcExpenseBudgetReviewRepository.Candidate candidate) {
        var initial = applications.findById(candidate.tenantId(), candidate.applicationId()).orElse(null);
        if (initial==null) return;
        var path = completion.lockForProgress(initial);
        var application = path.application();
        if (path.ancestors()!=SubprocessExecutionLocks.AncestorState.ACTIVE
                || application.status()!=ApplicationStatus.IN_APPROVAL || application.roundNo()!=candidate.roundNo()) {
            defer(candidate); return;
        }
        var round = rounds.findByRound(candidate.tenantId(), application.id(), candidate.roundNo()).orElseThrow();
        var instance = runtime.createProcessInstanceQuery().processInstanceId(round.processInstanceId()).singleResult();
        if (instance==null || instance.isSuspended()) { defer(candidate); return; }
        if (round.status()!=SubmissionRound.Status.IN_APPROVAL || !application.tenantId().equals(instance.getTenantId())
                || !application.runtimeDefinitionId().equals(instance.getProcessDefinitionId())) {
            throw new DomainException("EXPENSE_BUDGET_TASK_CONTEXT_CHANGED", "Budget recovery no longer matches the original process instance");
        }
        var review = reviews.find(candidate.tenantId(), candidate.reportId(), candidate.roundNo()).orElseThrow();
        var operationId = review.authorizedOperationId()==null ? review.input().originalOperationId() : review.authorizedOperationId();
        // 人工授权可以替换原预算指令；旧扫描不推进或延后新指令，由新候选恢复其真实来源。
        if (candidate.operationId()!=null && !candidate.operationId().equals(operationId)) return;
        var operation = operations.find(candidate.tenantId(), operationId).orElseThrow();
        if (operation.terminal()) outcomes.completed(new BudgetOperationCompleted(operation));
        // 复用结果处理可能已把再次拒绝的轮次退回，不能继续推进内存里的旧申请。
        application = applications.findById(candidate.tenantId(), candidate.applicationId()).orElseThrow();
        if (application.status()==ApplicationStatus.IN_APPROVAL && application.roundNo()==candidate.roundNo()) {
            var before = subprocesses.before(application);
            var taskIds = notifications.pendingTaskIds(application);
            long version = application.version();
            boolean ended = progress.advance(application, round.processInstanceId());
            if (application.version()!=version) {
                completion.persistProgress(application, version, round.processInstanceId(), ended,
                        ExpenseBudgetApprovalPolicy.SYSTEM_ACTOR, "原预算结果已确认，恢复无需例外审批的预算节点。");
                subprocesses.afterAdvance(before, application);
                notifications.processAdvanced(application, ExpenseBudgetApprovalPolicy.SYSTEM_ACTOR, null, null, taskIds);
            }
        }
        defer(candidate);
    }

    /** 推进失败后也可独立延后扫描，单条异常记录不能占满每次十条的恢复窗口。 */
    @Transactional
    public void defer(JdbcExpenseBudgetReviewRepository.Candidate candidate) {
        reviews.reschedule(candidate, Instant.now().plusSeconds(RECHECK_SECONDS));
    }
}
