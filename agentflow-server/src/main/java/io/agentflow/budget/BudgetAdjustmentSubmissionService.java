package io.agentflow.budget;

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
 * 正式提交将有效预检、预算调整申请轮次、任职与审批实例一起冻结，不提前修改预算额度。
 * @author owlzhangfq@gmail.com
 */
@Service
public class BudgetAdjustmentSubmissionService {
    private final CurrentActor actors;
    private final BudgetAdjustmentRepository requests;
    private final ApprovalApplicationFacade applications;
    private final DefinitionDraftRepository definitions;
    private final JdbcBudgetAdjustmentCheckRepository checks;
    private final BudgetAdjustmentCheckService validation;

    /** 提交过程只访问本地已确认事实，外部查询由预检执行器负责。 */
    public BudgetAdjustmentSubmissionService(CurrentActor actors, BudgetAdjustmentRepository requests, ApprovalApplicationFacade applications,
            DefinitionDraftRepository definitions, JdbcBudgetAdjustmentCheckRepository checks, BudgetAdjustmentCheckService validation) {
        this.actors = actors; this.requests = requests; this.applications = applications; this.definitions = definitions; this.checks = checks; this.validation = validation;
    }

    /** 前端只选择实际预检结果，不能覆盖冻结金额、预算台账或任职。 */
    @Transactional
    public BudgetAdjustmentService.Receipt submit(UUID id, Input input) {
        var actor = actors.actor();
        requests.find(actor.tenantId(), id).filter(adjustment -> adjustment.employeeId().equals(actor.userId())).orElseThrow(BudgetAdjustmentSubmissionService::notFound);
        requests.lock(actor.tenantId(), id); var adjustment = requests.find(actor.tenantId(), id).orElseThrow();
        var application = applications.requireApplicant(adjustment.applicationId()); application.requireEditable(input.applicationVersion());
        if (adjustment.version() != input.requestVersion()) throw new DomainException("CONCURRENCY_CONFLICT", "Budget adjustment version changed");
        var checked = checks.find(actor.tenantId(), input.precheckId()).filter(check -> check.input().requestId().equals(id)).orElseThrow(BudgetAdjustmentSubmissionService::notFound);
        String failure = validation.readyFailure(checked, adjustment, Instant.now());
        if (failure != null) throw new DomainException(failure, "Refresh budget adjustment facts before submission");
        var definition = definitions.lockPublished(actor.tenantId(), application.processKey(), application.definitionVersion())
                .orElseThrow(() -> new DomainException("PROCESS_DEFINITION_NOT_FOUND", "Published budget adjustment process not found"));
        definition.requireStartEnabled(); BudgetAdjustmentFormContract.requireReview(definition.graph(), application.formSchema());
        var evidence = checked.result().evidence(); Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        adjustment.freeze(input.requestVersion(), application.nextSubmissionRound(), evidence.catalog(), checked.input().targetDigest(),
                evidence.preview().ledger(), checked.input().initiator(), now);
        requests.update(adjustment, input.requestVersion(), actor.userId(), "SUBMIT");
        application = applications.reviseBusiness(application.id(), application.version(), adjustment.content().title(),
                BudgetAdjustmentFormContract.submittedPayload(adjustment.currentRound()), application.businessReference());
        application = applications.submitBusiness(application.id(), application.version(), checked.input().initiator().appointmentId(), application.businessReference());
        if (!evidence.validUntil().isAfter(Instant.now())) throw new DomainException("FACTS_EXPIRED", "Budget adjustment facts expired during submission");
        return BudgetAdjustmentService.receipt(application, adjustment);
    }

    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Budget adjustment or precheck not found"); }

    /**
     * 双版本和服务端预检标识，不允许客户端附带批准结论。
     * @author owlzhangfq@gmail.com
     */
    public record Input(@NotNull @Positive Long applicationVersion, @NotNull @Positive Long requestVersion, @NotNull UUID precheckId) {
        /** 误传的财务字段不能被静默忽略。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown budget adjustment submission field"); }
    }
}
