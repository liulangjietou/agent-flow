package io.agentflow.finance;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 财务明确裁决原付款争议；权限、历史资金依据、裁决凭据及下游恢复在一个本地事务内完成。
 * @author owlzhangfq@gmail.com
 */
@Service
public class PaymentDisputeService {
    private final CurrentActor actors;
    private final PaymentAccess access;
    private final ApprovedPaymentSources sources;
    private final JdbcPaymentOperationRepository operations;
    private final JdbcPaymentDisputeResolutionRepository resolutions;
    private final PaymentAudit audit;
    private final ApplicationEventPublisher events;
    /** 本服务不访问外部资金系统，新的对账事实必须先通过原交易查询持久化。 */
    public PaymentDisputeService(CurrentActor actors, PaymentAccess access, ApprovedPaymentSources sources, JdbcPaymentOperationRepository operations,
            JdbcPaymentDisputeResolutionRepository resolutions, PaymentAudit audit, ApplicationEventPublisher events) {
        this.actors = actors; this.access = access; this.sources = sources; this.operations = operations;
        this.resolutions = resolutions; this.audit = audit; this.events = events;
    }

    /** 锁后复核当前财务及完整字段权限，只采用页面刚展示的原付款修订。 */
    @Transactional
    public Receipt resolve(UUID paymentId, Input input) {
        var initial = access.requireFinanceAuthorization(paymentId); sources.lock(initial);
        var authorization = access.requireFinanceAuthorization(paymentId); var tenant = authorization.terms().tenantId(); var actor = actors.actor().userId();
        if (authorization.version() != input.authorizationVersion() || authorization.status() != PaymentAuthorization.Status.EXECUTION_REGISTERED) throw conflict();
        if (!independent(authorization, actor)) throw new DomainException("FORBIDDEN", "Applicant and original cashier cannot resolve the payment dispute");
        var before = operations.find(tenant, paymentId).orElseThrow(PaymentDisputeService::conflict);
        if (before.version() != input.operationVersion()) throw conflict();
        var history = operations.disputeEvidence(tenant, paymentId); var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var after = before.resolveDispute(input.outcome(), history.firstSuccess(), history.fundingObserved(), now);
        var decision = new PaymentDisputeResolution(UUID.randomUUID(), tenant, paymentId, before.version(), after.version(), after.observation(), actor, now, input.evidenceReference(), input.comment());
        operations.update(after); resolutions.create(decision);
        events.publishEvent(new PaymentOperationChanged(before, after));
        events.publishEvent(new PaymentDisputeResolved(after, decision));
        var event = audit.record(authorization, decision.id(), after.version(), "FINANCE", "PAYMENT_DISPUTE_RESOLVE", before.status().name(), after.status().name(), input.comment(), now);
        return new Receipt(authorization.terms().binding().applicationId(), paymentId, decision.id(), after.version(), after.status().name(), event);
    }

    /** 受控财务工作区取得最小对账候选和最后裁决；账号摘要及自由文本不进入投影。 */
    public View view(PaymentAuthorization authorization, PaymentOperation operation, Instant now) {
        var latest = resolutions.latest(authorization.terms().tenantId(), authorization.terms().id()).orElse(null);
        var candidate = operation == null ? null : operation.conflictingObservation();
        if (candidate == null && latest == null) return null;
        PaymentOperation.ResolutionIssue issue = null;
        if (candidate != null) {
            var history = operations.disputeEvidence(authorization.terms().tenantId(), authorization.terms().id());
            issue = operation.resolutionIssue(now, history.firstSuccess(), history.fundingObserved());
        }
        return new View(candidate == null ? null : new Candidate(candidate.status(), candidate.revision(), candidate.observedAt(), candidate.observedAt().plus(PaymentOperation.DISPUTE_EVIDENCE_LIFETIME),
                        candidate.paymentReference(), candidate.receiptReference(), candidate.completedAt(), candidate.failure()),
                issue, candidate != null && issue == null && authorization.status() == PaymentAuthorization.Status.EXECUTION_REGISTERED && independent(authorization, actors.actor().userId()),
                latest == null ? null : new Decision(latest.id(), latest.resolvedVersion(), latest.observation().status(), latest.resolvedBy(), latest.resolvedAt(), latest.evidenceReference()));
    }
    private static boolean independent(PaymentAuthorization authorization, String actor) {
        return authorization.execution() != null && !actor.equals(authorization.terms().payee().employeeId()) && !actor.equals(authorization.execution().command().authorization().executedBy());
    }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Displayed original payment or authorization version changed"); }
    /**
     * 请求只能指定已展示的终态及人工核对凭据，金额、账户和银行回执均不从客户端接收。
     * @author owlzhangfq@gmail.com
     */
    public record Input(@Positive long authorizationVersion, @Positive long operationVersion, @NotNull PaymentObservation.Status outcome,
                        @NotBlank @Size(max = 128) @Pattern(regexp = "[^\\p{Cntrl}]+") String evidenceReference, @NotBlank @Size(max = 2000) String comment) {
        /** 非终态不能被人工宣布为已处理。 */
        public Input { if (outcome != null && outcome != PaymentObservation.Status.SUCCEEDED && outcome != PaymentObservation.Status.FAILED && outcome != PaymentObservation.Status.REVERSED) throw new IllegalArgumentException("A terminal payment outcome is required"); }
        /** 不静默忽略伪造的账户、金额和回单字段。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown payment dispute resolution field"); }
    }
    /**
     * 幂等回执只定位本次裁决，不复制对账材料。
     * @author owlzhangfq@gmail.com
     */
    public record Receipt(UUID applicationId, UUID authorizationId, UUID resolutionId, long operationVersion, String outcome, UUID auditEventId) { }
    /**
     * 历史裁决与当前候选分开，曾裁决成功不代表后来再次出现的争议已经解除。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(Candidate candidate, PaymentOperation.ResolutionIssue issue, boolean canResolve, Decision latest) { }
    /**
     * 只展示最新已存回执及有效窗口，不允许财务编辑银行事实。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Candidate(PaymentObservation.Status outcome, long revision, Instant observedAt, Instant validUntil, String paymentReference, String receiptReference, Instant completedAt, PaymentObservation.Failure failure) { }
    /**
     * 最后裁决的审计定位，不公开原因自由文本。
     * @author owlzhangfq@gmail.com
     */
    public record Decision(UUID id, long operationVersion, PaymentObservation.Status outcome, String resolvedBy, Instant resolvedAt, String evidenceReference) { }
}
