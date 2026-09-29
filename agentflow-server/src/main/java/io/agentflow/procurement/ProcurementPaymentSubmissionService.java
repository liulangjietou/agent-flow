package io.agentflow.procurement;

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
 * 正式提交将有效预检、采购付款申请轮次、任职与审批实例一起冻结，不提前产生银行付款或应付核销。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ProcurementPaymentSubmissionService {
    private final CurrentActor actors;
    private final ProcurementPaymentRepository requests;
    private final ApprovalApplicationFacade applications;
    private final DefinitionDraftRepository definitions;
    private final JdbcProcurementPaymentCheckRepository checks;
    private final ProcurementPaymentCheckService validation;
    private final ProcurementPayableReservations reservations;

    /** 提交过程只访问本地已确认事实，外部查询由预检执行器负责。 */
    public ProcurementPaymentSubmissionService(CurrentActor actors, ProcurementPaymentRepository requests, ApprovalApplicationFacade applications,
            DefinitionDraftRepository definitions, JdbcProcurementPaymentCheckRepository checks, ProcurementPaymentCheckService validation,
            ProcurementPayableReservations reservations) {
        this.actors = actors; this.requests = requests; this.applications = applications; this.definitions = definitions; this.checks = checks; this.validation = validation;
        this.reservations = reservations;
    }

    /** 前端只选择实际预检结果，不能覆盖冻结金额、收款账户或任职。 */
    @Transactional
    public ProcurementPaymentService.Receipt submit(UUID id, Input input) {
        var actor = actors.actor();
        requests.find(actor.tenantId(), id).filter(payment -> payment.employeeId().equals(actor.userId())).orElseThrow(ProcurementPaymentSubmissionService::notFound);
        requests.lock(actor.tenantId(), id); var payment = requests.find(actor.tenantId(), id).orElseThrow();
        var application = applications.requireApplicant(payment.applicationId()); application.requireEditable(input.applicationVersion());
        if (payment.version() != input.requestVersion()) throw new DomainException("CONCURRENCY_CONFLICT", "Procurement payment version changed");
        var checked = checks.find(actor.tenantId(), input.precheckId()).filter(check -> check.input().requestId().equals(id)).orElseThrow(ProcurementPaymentSubmissionService::notFound);
        String failure = validation.readyFailure(checked, payment, Instant.now());
        if (failure != null) throw new DomainException(failure, "Refresh procurement payment facts before submission");
        var definition = definitions.lockPublished(actor.tenantId(), application.processKey(), application.definitionVersion())
                .orElseThrow(() -> new DomainException("PROCESS_DEFINITION_NOT_FOUND", "Published procurement payment process not found"));
        definition.requireStartEnabled(); ProcurementPaymentFormContract.requireReview(definition.graph(), application.formSchema());
        var evidence = checked.result().evidence(); Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        payment.freeze(input.requestVersion(), application.nextSubmissionRound(), evidence.catalog(), checked.input().targetDigest(),
                evidence.preview().payable(), checked.input().initiator(), now);
        requests.update(payment, input.requestVersion(), actor.userId(), "SUBMIT");
        reservations.reserve(application, payment, actor.userId(), now);
        application = applications.reviseBusiness(application.id(), application.version(), payment.content().title(),
                ProcurementPaymentFormContract.submittedPayload(payment.currentRound()), application.businessReference());
        application = applications.submitBusiness(application.id(), application.version(), checked.input().initiator().appointmentId(), application.businessReference());
        if (!evidence.validUntil().isAfter(Instant.now())) throw new DomainException("FACTS_EXPIRED", "Procurement payment facts expired during submission");
        return ProcurementPaymentService.receipt(application, payment);
    }

    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Procurement payment or precheck not found"); }

    /**
     * 双版本和服务端预检标识，不允许客户端附带批准结论。
     * @author owlzhangfq@gmail.com
     */
    public record Input(@NotNull @Positive Long applicationVersion, @NotNull @Positive Long requestVersion, @NotNull UUID precheckId) {
        /** 误传的财务字段不能被静默忽略。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown procurement payment submission field"); }
    }
}
