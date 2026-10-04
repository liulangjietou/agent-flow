package io.agentflow.expense;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.model.TaskAction;
import io.agentflow.approval.model.TaskDelegation;
import io.agentflow.approval.process.FlowableTaskAuthorization;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.BudgetPrecheckPort;
import io.agentflow.finance.JdbcBudgetOccupationRepository;
import io.agentflow.organization.ApprovalProxyUse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.flowable.engine.TaskService;
import org.flowable.task.api.DelegationState;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 财务任务的业务守卫和明确签收，不赋予财务职责之外的审批人新增敏感操作权限。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseApprovalService {
    private final ExpenseReportRepository reports;
    private final JdbcExpenseSubmissionControlRepository controls;
    private final JdbcBudgetOccupationRepository budgets;
    private final FlowableTaskAuthorization authorization;
    private final CurrentActor actors;
    private final ApplicationRepository applications;
    private final TaskService tasks;
    private final ExpensePrecheckResources resources;
    private final io.agentflow.approval.process.FlowableApprovalResponsibilities responsibilities;
    private final ExpenseBudgetReviewService budgetReviews;

    /** 通用审批和财务动作共享申请锁、当前任务授权和财务版本。 */
    public ExpenseApprovalService(ExpenseReportRepository reports, JdbcExpenseSubmissionControlRepository controls,
            JdbcBudgetOccupationRepository budgets, FlowableTaskAuthorization authorization, CurrentActor actors,
            ApplicationRepository applications, TaskService tasks, ExpensePrecheckResources resources,
            io.agentflow.approval.process.FlowableApprovalResponsibilities responsibilities, ExpenseBudgetReviewService budgetReviews) {
        this.reports = reports; this.controls = controls; this.budgets = budgets; this.authorization = authorization;
        this.actors = actors; this.applications = applications; this.tasks = tasks;
        this.resources = resources;
        this.responsibilities = responsibilities;
        this.budgetReviews = budgetReviews;
    }

    /** 审批动作先固定财务上下文，再重新读取任务，避免等待锁期间任务已经被撤回。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(Application application) {
        if (structured(application)) reports.lock(application.tenantId(), application.businessReference().id());
    }

    /** 财务同意要求本轮当前金额已实际冻结；纸件确认不能由普通同意动作隐式生成。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireApproval(Application application, Task task) {
        if (!structured(application)) return;
        var context = context(application, task);
        var stage = context.control().stage(task.getTaskDefinitionKey());
        String failure = budgetReviews.approvalFailure(context.report(), context.control(), task.getTaskDefinitionKey());
        if (failure!=null) throw new DomainException(failure, "Budget checkpoint is not awaiting human exception approval");
        if (!stage.businessApproval()) requirePaper(context);
        if (stage.finance()) requireBudget(context);
    }

    /** 实际同意审计已存在后才登记预算授权；普通业务、签收和财务任务不产生例外命令。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void taskApproved(Application application, Task task, String actor, String auditId) {
        if (!structured(application)) return;
        var control = controls.find(application.tenantId(), application.businessReference().id(), application.roundNo()).orElseThrow(ExpenseApprovalService::notFound);
        if (control.stage(task.getTaskDefinitionKey())==ExpenseProcessPolicy.Stage.BUDGET_REVIEW) {
            budgetReviews.approved(application, task.getId(), task.getTaskDefinitionKey(), actor, UUID.fromString(auditId));
        }
    }

    /** 只有当前签收节点的实际可决策人可以确认纸件；受托待归还状态不能代签。 */
    @Transactional
    public Receipt receive(UUID reportId, String taskId, ReceiveInput input) {
        var actor = actors.actor();
        var authorized = authorize(reportId, taskId, input.applicationVersion(), input.financialVersion());
        var task = authorized.task(); var application = authorized.application(); var context = authorized.context();
        var decision = authorization.requireAction(taskId, actor, TaskAction.APPROVE, input.proxyId());
        application.recordTaskAction(input.applicationVersion());
        var received = context.control().receive(taskId, task.getTaskDefinitionKey(), actor.userId(), input.comment(),
                Instant.now().truncatedTo(ChronoUnit.MICROS), decision.proxyUse());
        // 签收不是最终审批；纯代理不能通过领取留下超过授权期限的任务权利。
        if (decision.proxyUse() == null && task.getAssignee() == null) tasks.claim(taskId, actor.userId());
        controls.update(received); applications.update(application, input.applicationVersion());
        responsibilities.recordExpenseReceipt(task, actor.userId());
        return new Receipt(reportId, application.id(), application.version(), context.report().version(), application.roundNo(), received.version());
    }

    /** 核减入口按当前任务重新授权；只有本轮实际财务节点且预算已确认才能改变核定金额。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public FinanceTask requireReduction(UUID reportId, String taskId, long applicationVersion, long financialVersion, UUID proxyId) {
        var authorized = authorize(reportId, taskId, applicationVersion, financialVersion);
        var application = authorized.application(); var task = authorized.task(); var context = authorized.context();
        if (!context.control().stage(task.getTaskDefinitionKey()).finance()) {
            throw new DomainException("EXPENSE_FINANCE_TASK_REQUIRED", "Only a current financial review task may reduce expense amounts");
        }
        requirePaper(context); var budget = requireBudget(context);
        // 先等齐核减会修改的资源锁，再观察代理有效期；旧的读取快照不能授权等待后的金额变更。
        resources.lockReferences(application.tenantId(), ExpensePrecheckResources.versions(resources.loadReserved(context.report())));
        var decision = authorization.requireAction(taskId, actors.actor(), TaskAction.APPROVE, proxyId);
        return new FinanceTask(application, context.report(), context.control(), budget.targetDigest(), task.getId(), task.getTaskDefinitionKey(),
                task.getName(), task.getProcessInstanceId(), decision.proxyUse());
    }

    /**
     * 已在同一事务内完成任务与双版本校验的财务上下文，不允许客户端构造路由或预算目标。
     * @author owlzhangfq@gmail.com
     */
    public record FinanceTask(Application application, ExpenseReport report, ExpenseSubmissionControl control, String targetDigest,
                              String taskId, String nodeId, String nodeName, String processInstanceId, ApprovalProxyUse proxyUse) { }

    private AuthorizedTask authorize(UUID reportId, String taskId, long applicationVersion, long financialVersion) {
        var actor = actors.actor();
        var task = authorization.requireReadable(taskId, actor); var application = authorization.application(actor, task);
        if (!structured(application) || !application.businessReference().id().equals(reportId)) throw notFound();
        reports.lock(actor.tenantId(), reportId);
        task = authorization.requireReadable(taskId, actor); application = authorization.application(actor, task);
        var context = context(application, task);
        if (application.version() != applicationVersion || context.report().version() != financialVersion) {
            throw new DomainException("CONCURRENCY_CONFLICT", "Application or financial version changed");
        }
        new TaskDelegation(task.getOwner(), task.getDelegationState() == DelegationState.PENDING).requireAction(TaskAction.APPROVE);
        return new AuthorizedTask(application, task, context);
    }

    private void requirePaper(Context context) {
        if (!context.control().paperReady()) throw new DomainException("EXPENSE_PAPER_RECEIPT_REQUIRED", "Paper originals must be explicitly received for this round");
    }
    private io.agentflow.finance.BudgetOccupation requireBudget(Context context) {
        var position = BudgetPrecheckPort.Request.fromCurrent(context.report(), context.control().input().accountingDate());
        return budgets.find(context.report().tenantId(), context.report().id()).filter(value -> value.frozenFor(position)).orElseThrow(
                () -> new DomainException("EXPENSE_BUDGET_NOT_CONFIRMED", "The current expense amount has no confirmed budget reservation"));
    }

    /**
     * 签收与核减共享加锁后复核的身份、任务、双版本和委派规则。
     * @author owlzhangfq@gmail.com
     */
    private record AuthorizedTask(Application application, Task task, Context context) { }

    private Context context(Application application, Task task) {
        var report = reports.find(application.tenantId(), application.businessReference().id()).orElseThrow(ExpenseApprovalService::notFound);
        var control = controls.find(application.tenantId(), report.id(), application.roundNo()).orElseThrow(ExpenseApprovalService::notFound);
        if (application.status() != ApplicationStatus.IN_APPROVAL || !report.applicationId().equals(application.id())
                || !control.input().applicationId().equals(application.id()) || !control.input().employeeId().equals(report.employeeId())
                || !String.valueOf(application.roundNo()).equals(String.valueOf(task.getProcessVariables().get("roundNo")))) {
            throw new DomainException("EXPENSE_TASK_CONTEXT_CHANGED", "Expense task no longer belongs to the active round");
        }
        report.requireFrozenRound();
        if (report.currentRound().roundNo() != application.roundNo()
                || report.currentRound().submittedFinancialVersion() + 1 != control.input().submittedFinancialVersion()) {
            throw new DomainException("EXPENSE_TASK_CONTEXT_CHANGED", "Expense financial snapshot no longer matches its submission control");
        }
        return new Context(report, control);
    }
    private static boolean structured(Application application) {
        return application.businessReference() != null && application.businessReference().type() == BusinessReference.Type.EXPENSE;
    }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Expense task or submission control not found"); }

    /**
     * 同一次锁保护下的财务轮次和控制事实。
     * @author owlzhangfq@gmail.com
     */
    private record Context(ExpenseReport report, ExpenseSubmissionControl control) { }
    /**
     * 签收不接受客户端替换人、节点或轮次。
     * @author owlzhangfq@gmail.com
     */
    public record ReceiveInput(@NotNull @Positive Long applicationVersion, @NotNull @Positive Long financialVersion,
            @NotBlank @Size(max = 2000) String comment, UUID proxyId) {
        /** 拒绝无法解释的额外财务字段。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown expense receipt request field"); }
    }
    /**
     * 签收回执仅保存版本，不在幂等记录复制敏感明细。
     * @author owlzhangfq@gmail.com
     */
    public record Receipt(UUID reportId, UUID applicationId, long applicationVersion, long financialVersion, int roundNo, long controlVersion) { }
}
