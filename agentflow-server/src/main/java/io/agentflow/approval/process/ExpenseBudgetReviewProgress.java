package io.agentflow.approval.process;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.service.TaskAuditPort;
import io.agentflow.common.DomainException;
import io.agentflow.expense.*;
import io.agentflow.finance.BudgetPrecheckPort;
import io.agentflow.finance.JdbcBudgetOccupationRepository;
import io.agentflow.finance.JdbcBudgetOperationRepository;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.DelegationState;
import org.flowable.task.service.delegate.DelegateTask;
import org.flowable.task.service.delegate.TaskListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * 原预算确已冻结时推进独立节点并保留审计，不把系统动作伪装成某位候选人的批准。
 * @author owlzhangfq@gmail.com
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ExpenseBudgetReviewProgress {
    private static final String AUTOMATIC_MARKER = "agentflowBudgetAutomaticPass";
    private final JdbcExpenseBudgetReviewRepository reviews;
    private final ExpenseReportRepository reports;
    private final JdbcBudgetOperationRepository operations;
    private final JdbcBudgetOccupationRepository occupations;
    private final TaskService tasks;
    private final RuntimeService runtime;
    private final TaskAuditPort audit;

    /** 从已经确认的原操作取事实，沿用原生任务和同一审计端口。 */
    public ExpenseBudgetReviewProgress(JdbcExpenseBudgetReviewRepository reviews, ExpenseReportRepository reports,
            JdbcBudgetOperationRepository operations, JdbcBudgetOccupationRepository occupations, TaskService tasks,
            RuntimeService runtime, TaskAuditPort audit) {
        this.reviews = reviews; this.reports = reports; this.operations = operations; this.occupations = occupations;
        this.tasks = tasks; this.runtime = runtime; this.audit = audit;
    }

    /** 调用方已持有原申请及祖先执行锁；返回实际引擎结束状态，领域版本标识是否发生推进。 */
    public boolean advance(Application application, String instanceId) {
        if (application.businessReference()==null || application.businessReference().type()!=BusinessReference.Type.EXPENSE
                || application.status()!=ApplicationStatus.IN_APPROVAL) return false;
        var review = reviews.find(application.tenantId(), application.businessReference().id(), application.roundNo()).orElse(null);
        if (review==null || review.input().budgetNodeId()==null || review.status()!=ExpenseBudgetReview.Status.CONFIRMED
                || review.approval()!=null || review.automaticPass()!=null) return false;
        var report = reports.find(application.tenantId(), review.input().reportId()).orElseThrow();
        var original = operations.find(application.tenantId(), review.input().originalOperationId()).orElseThrow();
        if (!application.id().equals(review.input().applicationId()) || report.version()!=review.input().submittedFinancialVersion()
                || !occupations.find(application.tenantId(), report.id()).filter(value -> value.frozenFor(
                    BudgetPrecheckPort.Request.fromCurrent(report, original.input().command().position().accountingDate()))).isPresent()) return false;
        var candidates = tasks.createTaskQuery().processInstanceId(instanceId).taskDefinitionKey(review.input().budgetNodeId())
                .active().includeProcessVariables().list();
        if (candidates.isEmpty()) return false;
        if (candidates.size()!=1) throw invalid();
        var task = candidates.get(0);
        if (task.getDelegationState()==DelegationState.PENDING) return false;
        if (!application.tenantId().equals(task.getProcessVariables().get("tenantId"))
                || !application.id().toString().equals(task.getProcessVariables().get("applicationId"))
                || !String.valueOf(application.roundNo()).equals(String.valueOf(task.getProcessVariables().get("roundNo")))) throw invalid();
        application.recordTaskAction(application.version());
        // 任务局部标记只由本推进器设置，完成后即消失，不污染后续人工任务的职责判断。
        tasks.setVariableLocal(task.getId(), AUTOMATIC_MARKER, true);
        tasks.complete(task.getId(), Map.of("lastAction", ExpenseBudgetApprovalPolicy.AUTOMATIC_ACTION));
        String auditId = audit.record(new TaskAuditPort.TaskOperation(application.tenantId(), application.businessNo(), task.getId(), application.id(), application.version(),
                application.roundNo(), instanceId, ExpenseBudgetApprovalPolicy.SYSTEM_ACTOR, ExpenseBudgetApprovalPolicy.AUTOMATIC_ACTION,
                "本轮原预算操作已实际确认冻结，无需例外审批。", null, task.getTaskDefinitionKey(), task.getName(), application.status(), application.status(),
                null, null, null, new TaskAuditPort.BudgetConfirmation(original.input().command().id(), original.input().command().digest())));
        reviews.update(review.pass(task.getId(), UUID.fromString(auditId), Instant.now().truncatedTo(ChronoUnit.MICROS)));
        return runtime.createProcessInstanceQuery().processInstanceId(instanceId).singleResult()==null;
    }

    /** 两个完成监听共用可信局部标记；系统无需例外推进不产生实际人工决策记录。 */
    public static boolean automatic(DelegateTask task) {
        return TaskListener.EVENTNAME_COMPLETE.equals(task.getEventName())
                && Boolean.TRUE.equals(task.getVariableLocal(AUTOMATIC_MARKER))
                && ExpenseBudgetApprovalPolicy.AUTOMATIC_ACTION.equals(task.getVariable("lastAction"))
                && task.getTenantId()!=null && task.getTenantId().equals(task.getVariable("tenantId"));
    }
    private static DomainException invalid() { return new DomainException("EXPENSE_BUDGET_TASK_CONTEXT_CHANGED", "Budget checkpoint no longer matches its original native task"); }
}
