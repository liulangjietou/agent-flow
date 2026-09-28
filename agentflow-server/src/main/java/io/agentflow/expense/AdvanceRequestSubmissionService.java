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
 * 正式提交将有效预检、借款申请轮次、任职与审批实例一起冻结，不提前产生到账余额。
 * @author owlzhangfq@gmail.com
 */
@Service
public class AdvanceRequestSubmissionService {
    private final CurrentActor actors;
    private final AdvanceRequestRepository requests;
    private final ApprovalApplicationFacade applications;
    private final DefinitionDraftRepository definitions;
    private final JdbcAdvanceRequestCheckRepository checks;
    private final AdvanceRequestCheckService validation;

    /** 提交过程只访问本地已确认事实，外部查询由预检执行器负责。 */
    public AdvanceRequestSubmissionService(CurrentActor actors, AdvanceRequestRepository requests, ApprovalApplicationFacade applications,
            DefinitionDraftRepository definitions, JdbcAdvanceRequestCheckRepository checks, AdvanceRequestCheckService validation) {
        this.actors = actors; this.requests = requests; this.applications = applications; this.definitions = definitions; this.checks = checks; this.validation = validation;
    }

    /** 前端只选择实际预检结果，不能覆盖冻结金额、收款账户或任职。 */
    @Transactional
    public AdvanceRequestService.Receipt submit(UUID id, Input input) {
        var actor = actors.actor();
        requests.find(actor.tenantId(), id).filter(advance -> advance.employeeId().equals(actor.userId())).orElseThrow(AdvanceRequestSubmissionService::notFound);
        requests.lock(actor.tenantId(), id); var advance = requests.find(actor.tenantId(), id).orElseThrow();
        var application = applications.requireApplicant(advance.applicationId()); application.requireEditable(input.applicationVersion());
        if (advance.version() != input.requestVersion()) throw new DomainException("CONCURRENCY_CONFLICT", "Advance request version changed");
        var checked = checks.find(actor.tenantId(), input.precheckId()).filter(check -> check.input().requestId().equals(id)).orElseThrow(AdvanceRequestSubmissionService::notFound);
        String failure = validation.readyFailure(checked, advance, Instant.now());
        if (failure != null) throw new DomainException(failure, "Refresh advance request facts before submission");
        var definition = definitions.lockPublished(actor.tenantId(), application.processKey(), application.definitionVersion())
                .orElseThrow(() -> new DomainException("PROCESS_DEFINITION_NOT_FOUND", "Published advance request process not found"));
        definition.requireStartEnabled(); AdvanceRequestFormContract.requireReview(definition.graph(), application.formSchema());
        var evidence = checked.result().evidence(); Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        advance.freeze(input.requestVersion(), application.nextSubmissionRound(), evidence.catalog(), evidence.account(), checked.input().initiator(), now);
        requests.update(advance, input.requestVersion(), actor.userId(), "SUBMIT");
        application = applications.reviseBusiness(application.id(), application.version(), advance.content().title(),
                AdvanceRequestFormContract.submittedPayload(advance.currentRound()), application.businessReference());
        application = applications.submitBusiness(application.id(), application.version(), checked.input().initiator().appointmentId(), application.businessReference());
        if (!evidence.validUntil().isAfter(Instant.now())) throw new DomainException("FACTS_EXPIRED", "Advance request facts expired during submission");
        return AdvanceRequestService.receipt(application, advance);
    }

    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Advance request or precheck not found"); }

    /**
     * 双版本和服务端预检标识，不允许客户端附带批准结论。
     * @author owlzhangfq@gmail.com
     */
    public record Input(@NotNull @Positive Long applicationVersion, @NotNull @Positive Long requestVersion, @NotNull UUID precheckId) {
        /** 误传的财务字段不能被静默忽略。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown advance request submission field"); }
    }
}
