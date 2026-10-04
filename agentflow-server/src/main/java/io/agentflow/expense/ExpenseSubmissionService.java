package io.agentflow.expense;

import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionDraftRepository;
import io.agentflow.finance.BudgetOperationService;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 正式提交的本地事务：重验预检、冻结财务轮次、资源占用、审批启动与预算命令同时成功或回滚。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseSubmissionService {
    private final CurrentActor actors;
    private final ExpenseReportRepository reports;
    private final ApprovalApplicationFacade applications;
    private final DefinitionDraftRepository definitions;
    private final JdbcExpensePrecheckRepository prechecks;
    private final ExpensePrecheckService validation;
    private final ExpensePrecheckResources resources;
    private final ExpenseResourceChanges changes;
    private final JdbcExpenseSubmissionControlRepository controls;
    private final BudgetOperationService budgets;
    private final ExpensePolicyConfiguration policyConfiguration;
    private final ExpenseSplitRoutingService splitRouting;
    private final JdbcExpensePriorControlRepository priorControls;
    private final ExpenseBudgetReviewService budgetReviews;
    private final JdbcExpenseProjectApprovalRepository projects;

    /** 外部调用由持久执行器承担，提交事务只使用仍然有效的已确认事实。 */
    public ExpenseSubmissionService(CurrentActor actors, ExpenseReportRepository reports, ApprovalApplicationFacade applications,
            DefinitionDraftRepository definitions, JdbcExpensePrecheckRepository prechecks, ExpensePrecheckService validation,
            ExpensePrecheckResources resources, ExpenseResourceChanges changes, JdbcExpenseSubmissionControlRepository controls,
            BudgetOperationService budgets, ExpensePolicyConfiguration policyConfiguration, ExpenseSplitRoutingService splitRouting,
            JdbcExpensePriorControlRepository priorControls, ExpenseBudgetReviewService budgetReviews, JdbcExpenseProjectApprovalRepository projects) {
        this.actors = actors; this.reports = reports; this.applications = applications; this.definitions = definitions;
        this.prechecks = prechecks; this.validation = validation; this.resources = resources; this.changes = changes;
        this.controls = controls; this.budgets = budgets;
        this.policyConfiguration = policyConfiguration;
        this.splitRouting = splitRouting;
        this.priorControls = priorControls;
        this.budgetReviews = budgetReviews;
        this.projects = projects;
    }

    /** 申请人只提交双版本与预检编号；金额、任职、纸件要求和预算输入全部从服务端事实派生。 */
    @Transactional
    public Receipt submit(UUID id, Input input) {
        var actor = actors.actor(); reports.lock(actor.tenantId(), id);
        var report = reports.find(actor.tenantId(), id).filter(value -> value.employeeId().equals(actor.userId()))
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Expense report not found"));
        var application = applications.requireApplicant(report.applicationId());
        application.requireEditable(input.applicationVersion());
        if (report.version() != input.financialVersion()) throw new DomainException("CONCURRENCY_CONFLICT", "Financial version changed");
        var checked = prechecks.find(actor.tenantId(), input.precheckId()).filter(value -> value.input().reportId().equals(id))
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Expense precheck not found"));
        if (checked.status() != ExpensePrecheckJob.Status.READY) throw new DomainException("PRECHECK_NOT_READY", "Expense precheck is not ready");
        var evidence = checked.result().evidence(); resources.lockReferences(actor.tenantId(), evidence.resources());
        policyConfiguration.lockForSubmission(actor.tenantId());
        validation.requireReady(checked, report, Instant.now());
        var definition = definitions.lockPublished(actor.tenantId(), application.processKey(), application.definitionVersion())
                .orElseThrow(() -> new DomainException("PROCESS_DEFINITION_NOT_FOUND", "Published expense process not found"));
        definition.requireStartEnabled();
        var stages = ExpenseProcessPolicy.requireSubmittable(definition.graph(), application.formSchema(), evidence.legalEntity().paperReceiptRequired());
        String budgetNode = ExpenseBudgetApprovalPolicy.require(definition.graph(), application.formSchema(), evidence.budget().exceptionPolicy()!=null);
        boolean hasProjects = report.content().lines().stream().flatMap(line -> line.allocations().stream()).anyMatch(allocation -> allocation.projectCode() != null);
        String projectNode = ExpenseProjectApprovalPolicy.require(definition.graph(), application.formSchema(), hasProjects);
        if (evidence.projectOwners() == null && (hasProjects || ExpenseFormContract.hasProjectControl(application.formSchema()))) {
            throw new DomainException("EXPENSE_PROJECT_PRECHECK_REQUIRED", "Refresh the precheck to capture the original project owner evidence before submission");
        }
        var loaded = resources.load(report); Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var preview = evidence.preview();
        var assessments = preview.originalLines().stream().collect(Collectors.toMap(value -> value.original().lineNo(), ExpenseRound.FrozenLine::assessment));
        report.freeze(input.financialVersion(), application.nextSubmissionRound(), preview.baseCurrency(), preview.account(), assessments, actor.userId(), now);
        var plan = new ExpenseSubmissionResources().plan(report, loaded, now);
        if (evidence.priorControls() == null ? plan.priorControls().stream().anyMatch(value -> value.source().control() != null)
                : !evidence.priorControls().equals(plan.priorControls())) {
            throw new DomainException("RESOURCES_CHANGED", "Prior control evidence changed after precheck");
        }
        boolean overTolerance = plan.priorControls().stream().anyMatch(ExpensePriorControlAssessment::requiresApproval);
        ExpensePriorApprovalPolicy.require(definition.graph(), application.formSchema(), overTolerance);
        resources.requireClaimsAvailable(actor.tenantId(), plan); changes.persist(plan, actor.userId());
        reports.update(report, input.financialVersion(), actor.userId(), "SUBMIT");
        application = applications.reviseBusiness(application.id(), application.version(), report.content().title(),
                ExpenseFormContract.submittedPayload(report.currentRound(), ExpenseFormContract.hasPriorControl(application.formSchema()) ? overTolerance : null,
                        ExpenseFormContract.hasProjectControl(application.formSchema()) ? hasProjects : null), application.businessReference());
        priorControls.save(new ExpensePriorControlSnapshot(actor.tenantId(), id, application.id(), application.version(), report.currentRound().roundNo(),
                report.version(), definition.id(), definition.version(), now, plan.priorControls()), plan);
        if (evidence.projectOwners() != null) projects.save(ExpenseProjectApprovalSnapshot.capture(report, application.version(), definition.id(),
                definition.key(), definition.version(), projectNode, checked));
        splitRouting.prepare(report, application, definition, now);
        application = applications.submitBusiness(application.id(), application.version(), checked.input().initiator().appointmentId(), application.businessReference());
        var control = ExpenseSubmissionControl.submitted(new ExpenseSubmissionControl.Input(actor.tenantId(), id, application.id(), actor.userId(),
                application.roundNo(), report.version(), input.precheckId(), checked.input().accountingDate(), evidence.legalEntity().paperReceiptRequired(), stages), now);
        controls.create(control);
        var operation = budgets.reserve(actor.tenantId(), id, report.version(), checked.input().accountingDate(), checked.input().targetDigest(), now);
        budgetReviews.submitted(control, operation, evidence.budget().exceptionPolicy(), budgetNode);
        if (!evidence.validUntil().isAfter(Instant.now())) throw new DomainException("FACTS_EXPIRED", "Expense precheck expired during submission");
        return new Receipt(id, application.id(), application.version(), report.version(), application.roundNo(), operation.input().command().id());
    }

    /**
     * 不接受前端指定金额、任职或预算通过标志。
     * @author owlzhangfq@gmail.com
     */
    public record Input(@NotNull @Positive Long applicationVersion, @NotNull @Positive Long financialVersion, @NotNull UUID precheckId) {
        /** 严格拒绝未声明的财务输入。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown expense submission request field"); }
    }
    /**
     * 幂等回执只含标识与版本，预算排队成功不表示外部已经冻结。
     * @author owlzhangfq@gmail.com
     */
    public record Receipt(UUID reportId, UUID applicationId, long applicationVersion, long financialVersion, int roundNo, UUID budgetOperationId) { }
}
