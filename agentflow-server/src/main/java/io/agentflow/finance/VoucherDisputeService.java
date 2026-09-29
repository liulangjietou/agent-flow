package io.agentflow.finance;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * 明确裁决原凭证争议，权限、历史依据、审计与结算复核共用本地事务。
 * @author owlzhangfq@gmail.com
 */
@Service
public class VoucherDisputeService {
    private final CurrentActor actors;
    private final VoucherAccess access;
    private final PaymentPersonnel personnel;
    private final ApprovedVoucherSources sources;
    private final JdbcVoucherOperationRepository operations;
    private final JdbcVoucherDisputeResolutionRepository resolutions;
    private final ApplicationEventPublisher events;
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    /** 此服务不访问 ERP，所有候选必须先经原操作查询持久化。 */
    public VoucherDisputeService(CurrentActor actors, VoucherAccess access, PaymentPersonnel personnel, ApprovedVoucherSources sources,
            JdbcVoucherOperationRepository operations, JdbcVoucherDisputeResolutionRepository resolutions, ApplicationEventPublisher events, JdbcTemplate jdbc, JsonUtil json) {
        this.actors = actors; this.access = access; this.personnel = personnel; this.sources = sources; this.operations = operations;
        this.resolutions = resolutions; this.events = events; this.jdbc = jdbc; this.json = json;
    }

    /** 幂等回放前也核验原轮次字段权限、当前法人任职及独立财务身份。 */
    public VoucherAccess.Context requireFinance(UUID applicationId, UUID operationId, int roundNo) {
        var context = access.requireFinance(applicationId, roundNo); var command = original(context, operationId).input().command();
        var actor = actors.actor();
        if (!VoucherDisputeResolution.independent(command, actor.userId())) throw new DomainException("FORBIDDEN", "Applicant and original cashier cannot resolve the voucher dispute");
        personnel.requireEligible(actor.tenantId(), actor.userId(), command.legalEntityId()); return context;
    }

    /** 锁后重读当前身份和三个展示版本，旧页面不能裁决已变化的会计事实。 */
    @Transactional
    public Receipt resolve(UUID applicationId, UUID operationId, Input input) {
        var initial = requireFinance(applicationId, operationId, input.roundNo()); sources.lock(initial.source());
        var context = requireFinance(applicationId, operationId, input.roundNo()); var application = context.application();
        var before = original(context, operationId);
        if (application.version() != input.applicationVersion() || context.businessVersion() != input.businessVersion() || before.version() != input.operationVersion()) {
            throw new DomainException("CONCURRENCY_CONFLICT", "Displayed approval, financial or voucher version changed");
        }
        var tenant = application.tenantId(); var actor = actors.actor().userId(); var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var history = operations.disputeEvidence(tenant, operationId);
        var after = before.resolveDispute(input.outcome(), history.originalPosting(), history.postingObserved(), now);
        var decision = new VoucherDisputeResolution(UUID.randomUUID(), tenant, operationId, before.version(), after.version(), after.observation(), actor, now, input.evidenceReference(), input.comment());
        operations.update(after); resolutions.create(decision);
        events.publishEvent(new VoucherOperationChanged(before, after)); events.publishEvent(new VoucherDisputeResolved(after, decision));
        UUID auditId = UUID.randomUUID();
        var payload = Map.of("roundNo", input.roundNo(), "kind", after.input().command().kind().name(), "resolutionId", decision.id(), "actor", actor,
                "authorizedRole", "FINANCE", "previousStatus", before.status().name(), "currentStatus", after.status().name(), "comment", decision.reason());
        jdbc.update("""
                INSERT INTO audit_event(id,tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,application_id,action,actor_id,payload_json,occurred_at)
                VALUES(?,?,?,'Voucher',?,?,?,'VOUCHER_DISPUTE_RESOLVE',?,?,?)
                """, UUID.randomUUID().toString(), tenant, auditId.toString(), operationId.toString(), after.version(), applicationId.toString(), actor, json.write(payload), Timestamp.from(now));
        return new Receipt(applicationId, operationId, input.roundNo(), after.input().command().kind(), decision.id(), after.version(), after.observation().status(), auditId);
    }

    /** 所有读取仍由原字段投影授权；独立财务和当前任职决定是否展示裁决入口。 */
    public View view(VoucherAccess.Context context, VoucherOperation operation, Instant now) {
        if (operation == null || !context.finance()) return null;
        var command = operation.input().command(); var latest = resolutions.latest(command.tenantId(), command.id()).orElse(null);
        var candidate = operation.conflictingObservation();
        if (candidate == null && latest == null) return null;
        VoucherOperation.ResolutionIssue issue = null;
        if (candidate != null) {
            var history = operations.disputeEvidence(command.tenantId(), command.id()); issue = operation.resolutionIssue(now, history.originalPosting(), history.postingObserved());
        }
        boolean eligible = VoucherDisputeResolution.independent(command, actors.actor().userId()) && personnel.eligible(command.tenantId(), actors.actor().userId(), command.legalEntityId());
        return new View(candidate == null ? null : new Candidate(candidate.status(), candidate.revision(), candidate.observedAt(), candidate.observedAt().plus(VoucherOperation.DISPUTE_EVIDENCE_LIFETIME),
                candidate.postingReference(), candidate.voucherReference(), candidate.postedAt(), candidate.failure()), issue, candidate != null && issue == null && eligible,
                latest == null ? null : new Decision(latest.id(), latest.resolvedVersion(), latest.observation().status(), latest.resolvedBy(), latest.resolvedAt(), latest.evidenceReference()));
    }
    private VoucherOperation original(VoucherAccess.Context context, UUID operationId) {
        var application = context.application(); var value = operations.find(application.tenantId(), operationId).orElseThrow(VoucherDisputeService::notFound);
        var command = value.input().command(); var binding = command.binding();
        if (!binding.applicationId().equals(application.id()) || !binding.businessId().equals(application.businessReference().id()) || binding.roundNo() != context.roundNo()
                || command.kind() != context.kind() && command.kind() != VoucherCommand.Kind.PAYMENT) throw notFound();
        return value;
    }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Voucher for the selected financial round not found"); }
    /**
     * 请求只选择已展示终态与人工核对依据，不能提供新凭证号、金额或账户。
     * @author owlzhangfq@gmail.com
     */
    public record Input(@Positive int roundNo, @Positive long applicationVersion, @Positive long businessVersion, @Positive long operationVersion,
            @NotNull VoucherObservation.Status outcome, @NotBlank @Size(max = 128) @Pattern(regexp = "[^\\p{Cntrl}]+") String evidenceReference, @NotBlank @Size(max = 2000) String comment) {
        /** 查无或处理中不能被人工宣布为终态。 */
        public Input { if (outcome != null && outcome != VoucherObservation.Status.POSTED && outcome != VoucherObservation.Status.FAILED && outcome != VoucherObservation.Status.REVERSED) throw new IllegalArgumentException("A terminal voucher outcome is required"); }
        /** 拒绝伪造回执和客户端财务事实。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown voucher resolution field"); }
    }
    /**
     * 幂等回执仅定位本次持久决定。
     * @author owlzhangfq@gmail.com
     */
    public record Receipt(UUID applicationId, UUID operationId, int roundNo, VoucherCommand.Kind kind, UUID resolutionId, long operationVersion, VoucherObservation.Status outcome, UUID auditEventId) { }
    /**
     * 上次决定与当前争议分开，旧决定不能代表新冲突已解决。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(Candidate candidate, VoucherOperation.ResolutionIssue issue, boolean canResolve, Decision latest) { }
    /**
     * 只展示已存候选和有效窗口，不提供可编辑的会计事实。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Candidate(VoucherObservation.Status outcome, long revision, Instant observedAt, Instant validUntil, String postingReference, String voucherReference, Instant postedAt, VoucherObservation.Failure failure) { }
    /**
     * 最后裁决的最小审计定位。
     * @author owlzhangfq@gmail.com
     */
    public record Decision(UUID id, long operationVersion, VoucherObservation.Status outcome, String resolvedBy, Instant resolvedAt, String evidenceReference) { }
}
