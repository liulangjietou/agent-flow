package io.agentflow.expense;

import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.process.FlowableTaskAuthorization;
import io.agentflow.approval.process.FlowableApprovalProxyAccess;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.BudgetOccupation;
import io.agentflow.finance.BudgetOperation;
import io.agentflow.finance.BudgetPrecheckPort;
import io.agentflow.finance.JdbcBudgetOccupationRepository;
import io.agentflow.finance.JdbcBudgetOperationRepository;
import io.agentflow.organization.ApprovalProxyUse;
import org.flowable.task.api.DelegationState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 费用详情展示已确认事实与当前可办理动作；返回的按钮状态不代替写入时的实时授权。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseWorkflowQuery {
    private final CurrentActor actors;
    private final ExpenseDraftService drafts;
    private final ExpenseReportRepository reports;
    private final ApprovalApplicationFacade applications;
    private final JdbcExpenseSubmissionControlRepository controls;
    private final JdbcBudgetOccupationRepository budgets;
    private final JdbcBudgetOperationRepository operations;
    private final FlowableTaskAuthorization tasks;
    private final ExpenseLifecycleService lifecycle;
    private final FlowableApprovalProxyAccess proxies;
    private final ExpenseApprovalService approvals;

    /** 沿用完整明细读取权限，隐藏或脱敏审批人不能从控制查询旁路取得财务事实。 */
    public ExpenseWorkflowQuery(CurrentActor actors, ExpenseDraftService drafts, ExpenseReportRepository reports,
            ApprovalApplicationFacade applications, JdbcExpenseSubmissionControlRepository controls, JdbcBudgetOccupationRepository budgets,
            JdbcBudgetOperationRepository operations, FlowableTaskAuthorization tasks, ExpenseLifecycleService lifecycle,
            FlowableApprovalProxyAccess proxies, ExpenseApprovalService approvals) {
        this.actors = actors; this.drafts = drafts; this.reports = reports; this.applications = applications; this.controls = controls;
        this.budgets = budgets; this.operations = operations; this.tasks = tasks; this.lifecycle = lifecycle;
        this.proxies = proxies;
        this.approvals = approvals;
    }

    /** 一个数据库快照内读取纸件、预算与版本；页面在未知结果期间只能刷新，不生成假成功。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View get(UUID id, Map<String, String> parameters) {
        if (!Set.of("taskId").containsAll(parameters.keySet()) || parameters.containsKey("taskId")
                && (parameters.get("taskId").isBlank() || parameters.get("taskId").length() > 128)) {
            throw new DomainException("INVALID_EXPENSE_QUERY", "Only a current task identifier is accepted");
        }
        var actor = actors.actor(); var detail = drafts.read(id, null);
        var report = reports.find(actor.tenantId(), id).orElseThrow(); var application = applications.get(detail.applicationId());
        var control = controls.find(actor.tenantId(), id, application.roundNo()).orElse(null);
        var budget = budgets.find(actor.tenantId(), id).orElse(null); var operation = operations.latest(actor.tenantId(), id).orElse(null);
        boolean confirmed = budget != null && (application.status() == ApplicationStatus.IN_APPROVAL || application.status() == ApplicationStatus.APPROVED)
                && control != null && budget.frozenFor(BudgetPrecheckPort.Request.fromCurrent(report, control.input().accountingDate()));
        boolean owner = actor.userId().equals(report.employeeId()); TaskOptions options = null;
        if (parameters.containsKey("taskId")) {
            var task = tasks.requireReadable(parameters.get("taskId"), actor);
            if (!tasks.application(actor, task).id().equals(application.id()) || application.status() != ApplicationStatus.IN_APPROVAL
                    || control == null || !String.valueOf(application.roundNo()).equals(String.valueOf(task.getProcessVariables().get("roundNo")))) {
                throw new DomainException("NOT_FOUND", "Current expense task not found");
            }
            var stage = control.stage(task.getTaskDefinitionKey()); boolean delegated = task.getDelegationState() == DelegationState.PENDING;
            boolean direct = tasks.canAct(actor, task);
            var proxyOptions = proxies.forActor(actor, Instant.now()).options(task);
            boolean allowed = !delegated && (direct || !proxyOptions.isEmpty());
            boolean receive = allowed && stage == ExpenseProcessPolicy.Stage.RECEIPT && control.input().paperReceiptRequired() && control.receipt() == null;
            boolean reduce = allowed && stage.finance() && control.paperReady() && confirmed;
            String reductionUnavailable = !stage.finance() ? "EXPENSE_FINANCE_TASK_REQUIRED" : delegated ? "TASK_DELEGATION_PENDING"
                    : !control.paperReady() ? "EXPENSE_PAPER_RECEIPT_REQUIRED" : !confirmed ? "EXPENSE_BUDGET_NOT_CONFIRMED" : null;
            String approvalUnavailable = delegated ? "TASK_DELEGATION_PENDING" : !allowed ? "FORBIDDEN" : approvals.approvalFailure(application, task);
            options = new TaskOptions(task.getId(), stage, receive, reduce, reductionUnavailable, direct, proxyOptions,
                    approvalUnavailable == null, approvalUnavailable);
        }
        String issue = operation == null ? null : operation.failure() != null ? operation.failure().name()
                : operation.observation() != null && operation.observation().rejection() != null ? operation.observation().rejection().name() : null;
        return new View(id, application.id(), application.version(), report.version(), application.roundNo(),
                owner && lifecycle.withdrawalAllowed(application), owner && application.editable(),
                control == null ? null : new Paper(control.input().roundNo(), control.input().paperReceiptRequired(), control.receipt() != null,
                        control.receipt() == null ? null : control.receipt().receivedBy(), control.receipt() == null ? null : control.receipt().receivedAt(),
                        control.receipt() == null ? null : control.receipt().proxyUse()),
                new Budget(budget == null ? null : budget.status(), confirmed, operation == null ? null : operation.input().command().id(),
                        operation == null ? null : operation.status(), issue), options);
    }

    /**
     * 控制摘要不包含账户、财务目标、预算分摊或完整外部回执。
     * @author owlzhangfq@gmail.com
     */
    public record View(UUID reportId, UUID applicationId, long applicationVersion, long financialVersion, int roundNo,
                       boolean canWithdraw, boolean canCancel, Paper paper, Budget budget, TaskOptions task) { }
    /**
     * 原件签收绑定显示的审批轮次，不误称下一轮已签收。
     * @author owlzhangfq@gmail.com
     */
    public record Paper(int roundNo, boolean required, boolean received, String receivedBy, Instant receivedAt, ApprovalProxyUse proxyUse) { }
    /**
     * 台账状态与操作状态分别显示，FROZEN 本身不代表当前版本已经确认。
     * @author owlzhangfq@gmail.com
     */
    public record Budget(BudgetOccupation.Status ledgerStatus, boolean confirmedCurrent, UUID operationId, BudgetOperation.Status operationStatus, String issue) { }
    /**
     * 当前节点的实际金融动作入口，不通过名称推断财务权限。
     * @author owlzhangfq@gmail.com
     */
    public record TaskOptions(String taskId, ExpenseProcessPolicy.Stage stage, boolean canReceive, boolean canReduce, String reductionUnavailable,
                              boolean canActDirectly, List<FlowableApprovalProxyAccess.Option> proxyOptions, boolean canApprove, String approvalUnavailable) { }
}
