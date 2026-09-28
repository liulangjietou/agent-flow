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

    /** 通用审批和财务动作共享申请锁、当前任务授权和财务版本。 */
    public ExpenseApprovalService(ExpenseReportRepository reports, JdbcExpenseSubmissionControlRepository controls,
            JdbcBudgetOccupationRepository budgets, FlowableTaskAuthorization authorization, CurrentActor actors,
            ApplicationRepository applications, TaskService tasks) {
        this.reports = reports; this.controls = controls; this.budgets = budgets; this.authorization = authorization;
        this.actors = actors; this.applications = applications; this.tasks = tasks;
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
        if (stage != ExpenseProcessPolicy.Stage.BUSINESS && !context.control().paperReady()) {
            throw new DomainException("EXPENSE_PAPER_RECEIPT_REQUIRED", "Paper originals must be explicitly received for this round");
        }
        if (stage.finance()) {
            var position = BudgetPrecheckPort.Request.fromCurrent(context.report(), context.control().input().accountingDate());
            if (budgets.find(application.tenantId(), context.report().id()).filter(value -> value.frozenFor(position)).isEmpty()) {
                throw new DomainException("EXPENSE_BUDGET_NOT_CONFIRMED", "The current expense amount has no confirmed budget reservation");
            }
        }
    }

    /** 只有当前签收节点的实际可决策人可以确认纸件；受托待归还状态不能代签。 */
    @Transactional
    public Receipt receive(UUID reportId, String taskId, ReceiveInput input) {
        var actor = actors.actor();
        var task = authorization.require(taskId, actor); var application = authorization.application(actor, task);
        if (!structured(application) || !application.businessReference().id().equals(reportId)) throw notFound();
        reports.lock(actor.tenantId(), reportId);
        task = authorization.require(taskId, actor); application = authorization.application(actor, task);
        var context = context(application, task);
        if (context.report().version() != input.financialVersion()) throw new DomainException("CONCURRENCY_CONFLICT", "Financial version changed");
        new TaskDelegation(task.getOwner(), task.getDelegationState() == DelegationState.PENDING).requireAction(TaskAction.APPROVE);
        application.recordTaskAction(input.applicationVersion());
        var received = context.control().receive(taskId, task.getTaskDefinitionKey(), actor.userId(), input.comment(), Instant.now().truncatedTo(ChronoUnit.MICROS));
        if (task.getAssignee() == null) tasks.claim(taskId, actor.userId());
        controls.update(received); applications.update(application, input.applicationVersion());
        return new Receipt(reportId, application.id(), application.version(), context.report().version(), application.roundNo(), received.version());
    }

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
            @NotBlank @Size(max = 2000) String comment) {
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
