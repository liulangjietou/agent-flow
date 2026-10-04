package io.agentflow.expense;

import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionDraftRepository;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 正式提交将有效预检、计划轮次、任职与审批实例一起冻结，不提前产生可核销额度。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpensePlanSubmissionService {
    private final CurrentActor actors;
    private final ExpensePlanRepository plans;
    private final ApprovalApplicationFacade applications;
    private final DefinitionDraftRepository definitions;
    private final JdbcExpensePlanCheckRepository checks;
    private final ExpensePlanCheckService validation;
    private final ExpensePolicyConfiguration policyConfiguration;

    /** 提交过程只访问本地已确认事实，外部查询由预检执行器负责。 */
    public ExpensePlanSubmissionService(CurrentActor actors, ExpensePlanRepository plans, ApprovalApplicationFacade applications,
            DefinitionDraftRepository definitions, JdbcExpensePlanCheckRepository checks, ExpensePlanCheckService validation,
            ExpensePolicyConfiguration policyConfiguration) {
        this.actors = actors; this.plans = plans; this.applications = applications; this.definitions = definitions; this.checks = checks; this.validation = validation;
        this.policyConfiguration = policyConfiguration;
    }

    /** 前端只选择实际预检结果，不能覆盖冻结金额、汇率或任职。 */
    @Transactional
    public ExpensePlanService.Receipt submit(UUID id, Input input) {
        var actor = actors.actor();
        plans.find(actor.tenantId(), id).filter(plan -> plan.employeeId().equals(actor.userId())).orElseThrow(ExpensePlanSubmissionService::notFound);
        plans.lock(actor.tenantId(), id); var plan = plans.find(actor.tenantId(), id).orElseThrow();
        var application = applications.requireApplicant(plan.applicationId()); application.requireEditable(input.applicationVersion());
        if (plan.version() != input.planVersion()) throw new DomainException("CONCURRENCY_CONFLICT", "Expense plan version changed");
        var checked = checks.find(actor.tenantId(), input.precheckId()).filter(check -> check.input().planId().equals(id)).orElseThrow(ExpensePlanSubmissionService::notFound);
        policyConfiguration.lockForSubmission(actor.tenantId());
        String failure = validation.readyFailure(checked, plan, Instant.now());
        if (failure != null) throw new DomainException(failure, "Refresh expense plan facts before submission");
        var definition = definitions.lockPublished(actor.tenantId(), application.processKey(), application.definitionVersion())
                .orElseThrow(() -> new DomainException("PROCESS_DEFINITION_NOT_FOUND", "Published expense plan process not found"));
        definition.requireStartEnabled(); ExpensePlanFormContract.requireReview(definition.graph(), application.formSchema());
        var evidence = checked.result().evidence(); Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var priorControls = evidence.preview().lines().stream().filter(line -> line.priorControl() != null)
                .collect(java.util.stream.Collectors.toMap(line -> line.priorControl().categoryCode(), ExpensePlanRound.FrozenLine::priorControl,
                        (first, second) -> first));
        plan.freeze(input.planVersion(), application.nextSubmissionRound(), evidence.catalog(), evidence.rates(), checked.input().initiator(), now,
                evidence.preview().managedCategoryRevision(), priorControls);
        plans.update(plan, input.planVersion(), actor.userId(), "SUBMIT");
        application = applications.reviseBusiness(application.id(), application.version(), plan.content().title(),
                ExpensePlanFormContract.submittedPayload(plan.currentRound()), application.businessReference());
        application = applications.submitBusiness(application.id(), application.version(), checked.input().initiator().appointmentId(), application.businessReference());
        if (!evidence.validUntil().isAfter(Instant.now())) throw new DomainException("FACTS_EXPIRED", "Expense plan facts expired during submission");
        return ExpensePlanService.receipt(application, plan);
    }

    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Expense plan or precheck not found"); }

    /**
     * 双版本和服务端预检标识，不允许客户端附带批准结论。
     * @author owlzhangfq@gmail.com
     */
    public record Input(@NotNull @Positive Long applicationVersion, @NotNull @Positive Long planVersion, @NotNull UUID precheckId) {
        /** 误传的财务字段不能被静默忽略。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown expense plan submission field"); }
    }
}
