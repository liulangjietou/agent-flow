package io.agentflow.expense;

import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.approval.service.TaskAuditPort;
import io.agentflow.common.CurrentActor;
import io.agentflow.finance.BudgetOperationService;
import io.agentflow.finance.Money;
import io.agentflow.notification.ApprovalNotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * 核减事务协调财务事实、资源差额、后续路由、预算命令与通知；不替财务作审批决定。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseReductionService {
    private static final String AUDIT_ACTION = "EXPENSE_REDUCE";
    private final CurrentActor actors;
    private final ExpenseApprovalService approvals;
    private final ExpenseReportRepository reports;
    private final ExpensePrecheckResources resources;
    private final ExpenseResourceChanges changes;
    private final ApprovalApplicationFacade applications;
    private final BudgetOperationService budgets;
    private final ApprovalNotificationService notifications;
    private final TaskAuditPort audit;
    private final JdbcExpensePriorControlRepository priorControls;
    private final ExpenseProjectApprovalBindings projects;

    /** 外部预算只登记持久命令，核减事务内不发 HTTP。 */
    public ExpenseReductionService(CurrentActor actors, ExpenseApprovalService approvals, ExpenseReportRepository reports,
            ExpensePrecheckResources resources, ExpenseResourceChanges changes, ApprovalApplicationFacade applications,
            BudgetOperationService budgets, ApprovalNotificationService notifications, TaskAuditPort audit, JdbcExpensePriorControlRepository priorControls,
            ExpenseProjectApprovalBindings projects) {
        this.actors = actors; this.approvals = approvals; this.reports = reports; this.resources = resources;
        this.changes = changes; this.applications = applications; this.budgets = budgets; this.notifications = notifications; this.audit = audit;
        this.priorControls = priorControls;
        this.projects = projects;
    }

    /** 只减不增由报销实体执行；最后一个环节失败时，全部本地事实与引擎变量一起回滚。 */
    @Transactional
    public Receipt reduce(UUID reportId, String taskId, Input input) {
        var actor = actors.actor();
        var context = approvals.requireReduction(reportId, taskId, input.applicationVersion(), input.financialVersion(), input.proxyId());
        var report = context.report(); var application = context.application(); var before = ExpenseReport.restore(report.state());
        var loaded = resources.loadReserved(report); var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String currency = report.currentRound().baseCurrency();
        var lines = input.lines().stream().map(line -> new ExpenseReport.Reduction(line.lineNo(),
                new Money(line.approvedGross(), currency), new Money(line.approvedTax(), currency))).toList();
        var adjustment = report.reduce(input.financialVersion(), lines, actor.userId(), input.reasonCode().name(), input.comment(), now);
        changes.persist(new ExpenseReductionResources().plan(before, report, loaded), actor.userId());
        reports.update(report, input.financialVersion(), actor.userId(), "REDUCE");
        // 核减后的预算仍需实际确认，期间通用财务批准守卫会阻止放行。
        application = applications.adjustBusiness(application.id(), input.applicationVersion(), application.businessReference(),
                ExpenseFormContract.submittedPayload(report.currentRound(), priorControls.routingFlag(report, application.formSchema()),
                        projects.routingFlag(report, application.formSchema())));
        var operation = budgets.reserve(actor.tenantId(), reportId, report.version(), context.control().input().accountingDate(), context.targetDigest(), now);
        audit.record(new TaskAuditPort.TaskOperation(actor.tenantId(), application.businessNo(), context.taskId(), application.id(), application.version(),
                application.roundNo(), context.processInstanceId(), actor.userId(), AUDIT_ACTION, input.comment(), null,
                context.nodeId(), context.nodeName(), application.status(), application.status(), null, context.proxyUse()));
        notifications.expenseAdjusted(application, actor.userId());
        return new Receipt(reportId, application.id(), application.version(), report.version(), application.roundNo(), adjustment.id(), operation.input().command().id());
    }

    /**
     * 不接受币种、收款人、发票或成本对象，所有金额均为本轮固定本位币。
     * @author owlzhangfq@gmail.com
     */
    public record Input(@NotNull @Positive Long applicationVersion, @NotNull @Positive Long financialVersion,
            @NotEmpty @Size(max = ExpenseContent.MAX_LINES) List<@NotNull @Valid LineInput> lines,
            @NotNull Reason reasonCode, @NotBlank @Size(max = 2000) String comment, UUID proxyId) {
        /** 伪造审批人或金融对象的字段必须显式失败。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown expense reduction request field"); }
    }
    /**
     * 客户端只指定已有行及新的含税、可抵扣税额，领域检查精度与减额边界。
     * @author owlzhangfq@gmail.com
     */
    public record LineInput(@NotNull @Positive Integer lineNo, @NotNull @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = FinanceJsonConfiguration.DecimalAmountDeserializer.class) BigDecimal approvedGross,
            @NotNull @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = FinanceJsonConfiguration.DecimalAmountDeserializer.class) BigDecimal approvedTax) {
        /** 不允许在行内夹带新的票据、分摊或币种。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown expense reduction line field"); }
    }
    /**
     * 核减原因使用有限业务类别，补充文字始终必填；不引入未经配置的比例阈值。
     * @author owlzhangfq@gmail.com
     */
    public enum Reason { INELIGIBLE_COST, OVER_STANDARD_NOT_ACCEPTED, INVALID_INVOICE, TAX_CORRECTION, OTHER }

    /**
     * 幂等结果只保留操作标识与版本，明细差额仍须通过实时财务读取授权。
     * @author owlzhangfq@gmail.com
     */
    public record Receipt(UUID reportId, UUID applicationId, long applicationVersion, long financialVersion, int roundNo,
                          UUID adjustmentId, UUID budgetOperationId) { }
}
