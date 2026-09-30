package io.agentflow.procurement;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.Money;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原调整争议在当前独立财务权限下明确裁决，决定、连续修订和审计共同提交。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierAdjustmentDisputeService {
    private final CurrentActor actors;
    private final SupplierAdjustmentAccess access;
    private final JdbcSupplierAdjustmentSources sources;
    private final JdbcSupplierPayableAdjustmentRepository adjustments;
    private final JdbcTemplate jdbc;
    private final JsonUtil json;

    /** 原号查询复用既有调整入口；裁决事务不执行银行或 ERP 网络调用。 */
    public SupplierAdjustmentDisputeService(CurrentActor actors, SupplierAdjustmentAccess access, JdbcSupplierAdjustmentSources sources,
            JdbcSupplierPayableAdjustmentRepository adjustments, JdbcTemplate jdbc, JsonUtil json) {
        this.actors = actors; this.access = access; this.sources = sources; this.adjustments = adjustments; this.jdbc = jdbc; this.json = json;
    }

    /** 历史决定与当前候选分别展示，读取仍受原轮次完整财务字段权限约束。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View read(UUID id, Map<String, String> parameters) {
        if (!parameters.isEmpty()) throw new DomainException("INVALID_SUPPLIER_ADJUSTMENT_DISPUTE_QUERY", "Original ERP dispute does not accept alternate identities or query parameters");
        var value = adjustments.find(actors.actor().tenantId(), id).orElseThrow(SupplierAdjustmentDisputeService::notFound);
        var command = value.command(); var context = access.read(command.source().returns().request().command().id()); var source = command.source().returns().request().command().holdCommand().authorization().source().reservation().source();
        var issue = value.conflictingObservation() == null ? null : value.resolutionIssue(adjustments.resolutionHistory(command.tenantId(), id), Instant.now());
        var latest = adjustments.latestResolution(command.tenantId(), id).orElse(null);
        return new View(id, command.source().returns().request().command().id(), source.requestId(), source.applicationId(), source.round().roundNo(), value.version(), value.status(),
                command.source().returns().request().command().amount(), command.source().newReturned(), command.source().returns().totalReturned(), command.source().netPaid(), command.source().recognizesOriginalPayment(), fact(value.observation()), fact(value.conflictingObservation()), issue,
                context.finance() && adjustments.retirement(command.tenantId(), id).isEmpty() && value.conflictingObservation() != null && issue == null,
                latest == null ? null : new Decision(latest.id(), latest.resolvedVersion(), latest.observation().status(), latest.resolvedBy(), latest.resolvedAt(), latest.evidenceReference()));
    }

    /** 候选只能来自原调整修订；成功后由既有工作器另行复核银行并恢复本地完成。 */
    @Transactional
    public Receipt resolve(UUID id, ResolveInput input) {
        var before = access.requireAdjustment(id); var tenant = actors.actor().tenantId(); sources.lock(tenant, before.command().source().returns().request().command().id());
        before = access.requireAdjustment(id);
        if (before.version() != input.adjustmentVersion() || adjustments.retirement(tenant, id).isPresent()) {
            throw new DomainException("CONCURRENCY_CONFLICT", "Displayed original supplier adjustment changed or was safely retired");
        }
        var candidate = before.conflictingObservation();
        if (candidate == null || candidate.status() != input.outcome()) throw new DomainException("SUPPLIER_ADJUSTMENT_DISPUTE_UNRESOLVABLE", "Displayed original ERP terminal evidence changed");
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var decision = new SupplierAdjustmentDisputeResolution(UUID.randomUUID(), tenant, id, before.version(), Math.incrementExact(before.version()), candidate,
                actors.actor().userId(), now, input.evidenceReference().trim(), input.comment());
        var after = adjustments.resolve(decision);
        var event = audit(before, after, decision, now); var source = before.command().source().returns().request().command().holdCommand().authorization().source().reservation().source();
        return new Receipt(id, before.command().source().returns().request().command().id(), source.requestId(), source.applicationId(), source.round().roundNo(), after.version(), after.status(), decision.id(), event);
    }

    private UUID audit(SupplierPayableAdjustmentOperation before, SupplierPayableAdjustmentOperation after, SupplierAdjustmentDisputeResolution decision, Instant now) {
        var source = before.command().source().returns().request().command().holdCommand().authorization().source().reservation().source(); var event = UUID.randomUUID();
        var payload = Map.of("requestId", source.requestId(), "roundNo", source.round().roundNo(), "paymentId", before.command().source().returns().request().command().id(), "resolutionId", decision.id(),
                "authorizedRole", "FINANCE", "previousStatus", before.status().name(), "currentStatus", after.status().name(), "comment", decision.reason());
        jdbc.update("""
                INSERT INTO audit_event(id,tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,application_id,action,actor_id,payload_json,occurred_at)
                VALUES(?,?,?,'SupplierAdjustmentDispute',?,?,?,'SUPPLIER_ADJUSTMENT_DISPUTE_RESOLVE',?,?,?)
                """, UUID.randomUUID().toString(), source.tenantId(), event.toString(), before.command().id().toString(), after.version(), source.applicationId().toString(),
                actors.actor().userId(), json.write(payload), Timestamp.from(now));
        return event;
    }
    private static Fact fact(SupplierPayableAdjustmentObservation value) {
        if (value == null) return null;
        var posting = value.posting();
        return new Fact(value.status(), value.revision(), value.observedAt(), value.observedAt().plus(SupplierPayableAdjustmentOperation.DISPUTE_EVIDENCE_LIFETIME), value.rejection(),
                posting == null ? null : new Posting(posting.adjustmentReference(), posting.recognitionVoucherReference(), posting.returnedAmount(), posting.totalReturned(), posting.netPaid(), posting.payableSettledBefore(), posting.payableSettledAfter(),
                        posting.entries().stream().map(entry -> new SupplierAdjustmentWorkspace.Entry(entry.transactionReference(), entry.amount(), entry.voucherReference(), entry.entryReference())).toList(),
                        posting.periodReference(), posting.accountingDate(), posting.adjustedAt()));
    }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Original supplier ERP adjustment is unavailable in the current scope"); }

    /**
     * 输入只选择服务端候选终态，不接收金额、账务身份、回执或办理人。
     * @author owlzhangfq@gmail.com
     */
    public record ResolveInput(@Positive long adjustmentVersion, @NotNull SupplierPayableAdjustmentObservation.Status outcome,
            @NotBlank @Size(max = 128) @Pattern(regexp = "[^\\p{Cntrl}]+") String evidenceReference, @NotBlank @Size(max = 2000) String comment) {
        /** 未确定的 ERP 结果不能由人工强制宣布为调整终态。 */
        public ResolveInput {
            if (outcome != null && outcome != SupplierPayableAdjustmentObservation.Status.ADJUSTED && outcome != SupplierPayableAdjustmentObservation.Status.REJECTED)
                throw new IllegalArgumentException("A terminal original ERP outcome is required");
        }
        /** 拒绝客户端伪造凭证、余额或其他调整身份。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown supplier ERP dispute resolution field"); }
    }
    /**
     * 当前候选与最近决定并列，旧决定不会隐藏后续争议。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(UUID adjustmentId, UUID paymentId, UUID requestId, UUID applicationId, int roundNo, long adjustmentVersion, SupplierPayableAdjustmentOperation.Status status,
            Money amount, Money returnedAmount, Money totalReturned, Money netPaid, boolean recognizesOriginalPayment, Fact observed, Fact candidate, SupplierPayableAdjustmentOperation.ResolutionIssue issue, boolean canResolve, Decision latest) { }
    /**
     * 必要核对信息不包含原始资金账户、命令摘要或内部台账标识。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Fact(SupplierPayableAdjustmentObservation.Status outcome, long revision, Instant observedAt, Instant validUntil,
            SupplierPayableAdjustmentObservation.Rejection rejection, Posting posting) { }
    /**
     * 原凭证、调整额及累计前后余额用于人工核对，事实始终由服务端绑定原指令。
     * @author owlzhangfq@gmail.com
     */
    public record Posting(String adjustmentReference, String recognitionVoucherReference, Money returnedAmount, Money totalReturned, Money netPaid, Money payableSettledBefore, Money payableSettledAfter,
            java.util.List<SupplierAdjustmentWorkspace.Entry> entries, String periodReference, LocalDate accountingDate, Instant adjustedAt) { }
    /**
     * 历史决定关联原调整的结果修订，说明保留于受控审计。
     * @author owlzhangfq@gmail.com
     */
    public record Decision(UUID id, long adjustmentVersion, SupplierPayableAdjustmentObservation.Status outcome, String resolvedBy, Instant resolvedAt, String evidenceReference) { }
    /**
     * 回执只确认独立裁决保存；银行和本地完成状态需要重新读取。
     * @author owlzhangfq@gmail.com
     */
    public record Receipt(UUID adjustmentId, UUID paymentId, UUID requestId, UUID applicationId, int roundNo, long adjustmentVersion,
            SupplierPayableAdjustmentOperation.Status status, UUID resolutionId, UUID auditEventId) { }
}
