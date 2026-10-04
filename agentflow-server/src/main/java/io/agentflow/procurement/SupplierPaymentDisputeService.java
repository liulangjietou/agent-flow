package io.agentflow.procurement;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.PaymentObservation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 独立财务在原银行查询之后明确裁决，当前权限、原修订、决定与审计共同提交。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierPaymentDisputeService {
    private final CurrentActor actors;
    private final SupplierSettlementAccess access;
    private final SupplierPaymentSources sources;
    private final JdbcSupplierPaymentOperationRepository payments;
    private final SupplierPaymentService execution;
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final ApplicationEventPublisher events;

    /** 裁决与核销共用原轮次读取及独立财务条件，网络查询仍由原银行工作器执行。 */
    public SupplierPaymentDisputeService(CurrentActor actors, SupplierSettlementAccess access, SupplierPaymentSources sources,
            JdbcSupplierPaymentOperationRepository payments, SupplierPaymentService execution, JdbcTemplate jdbc, JsonUtil json, ApplicationEventPublisher events) {
        this.actors = actors; this.access = access; this.sources = sources; this.payments = payments; this.execution = execution; this.jdbc = jdbc; this.json = json; this.events = events;
    }

    /** 所有写入和幂等回放先检查当前原轮次字段、法人任职及与原出纳的分离。 */
    public void requireFinance(UUID paymentId) { access.requireFinance(paymentId); }

    /** 原参与人只能读取必要回单，原始账号、命令摘要和金融资料不出现在投影中。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View read(UUID paymentId, Map<String, String> parameters) {
        if (!parameters.isEmpty()) throw new DomainException("INVALID_SUPPLIER_DISPUTE_QUERY", "Original supplier bank dispute does not accept alternate identities or query parameters");
        var context = access.read(paymentId); var bank = context.payment(); var source = context.authorization().source().reservation().source();
        var candidate = bank == null ? null : bank.conflictingObservation(); var now = Instant.now();
        var issue = candidate == null ? null : bank.resolutionIssue(payments.resolutionHistory(source.tenantId(), paymentId), now);
        var latest = payments.latestResolution(source.tenantId(), paymentId).orElse(null);
        return new View(paymentId, source.requestId(), source.applicationId(), source.round().roundNo(), bank == null ? null : bank.version(), bank == null ? null : bank.status(),
                bank == null ? null : fact(bank.observation()), fact(candidate), issue,
                context.finance() && bank != null && !bank.running() && bank.status() != SupplierPaymentOperation.Status.QUEUED && bank.dispatches() > 0,
                context.finance() && candidate != null && issue == null,
                latest == null ? null : new Decision(latest.id(), latest.resolvedVersion(), latest.observation().status(), latest.resolvedBy(), latest.resolvedAt(), latest.evidenceReference()));
    }

    /** 财务只能登记同一原号的只读查询，不能以核对操作触发重新付款。 */
    @Transactional
    public Receipt query(UUID paymentId, QueryInput input) {
        var context = locked(paymentId); var before = version(context, input.operationVersion()); var now = now();
        var after = execution.query(before.command().tenantId(), paymentId, before.version(), now);
        return receipt(context, after, Action.QUERY, null, audit(before, after, Action.QUERY, input.comment(), now));
    }

    /** 候选来自已经保存的原银行回执，决定、状态修订、幂等回执和审计任一失败全部回滚。 */
    @Transactional
    public Receipt resolve(UUID paymentId, ResolveInput input) {
        var context = locked(paymentId); var before = version(context, input.operationVersion()); var now = now(); var candidate = before.conflictingObservation();
        if (candidate == null || candidate.status() != input.outcome()) throw new DomainException("SUPPLIER_PAYMENT_DISPUTE_UNRESOLVABLE", "Displayed supplier bank terminal evidence changed");
        var decision = new SupplierPaymentDisputeResolution(UUID.randomUUID(), before.command().tenantId(), paymentId, before.version(), Math.incrementExact(before.version()),
                candidate, actors.actor().userId(), now, input.evidenceReference().trim(), input.comment());
        var after = payments.resolve(decision); events.publishEvent(new SupplierPaymentChanged(before, after));
        return receipt(context, after, Action.RESOLVE, decision.id(), audit(before, after, Action.RESOLVE, input.comment(), now));
    }

    private SupplierSettlementAccess.Context locked(UUID paymentId) {
        var initial = access.requireFinance(paymentId); sources.lock(initial.authorization().source().reservation().source().tenantId(), paymentId); return access.requireFinance(paymentId);
    }
    private static SupplierPaymentOperation version(SupplierSettlementAccess.Context context, long expected) {
        var payment = context.payment();
        if (payment == null || payment.version() != expected) throw new DomainException("CONCURRENCY_CONFLICT", "Displayed original supplier bank revision changed");
        return payment;
    }
    private static Receipt receipt(SupplierSettlementAccess.Context context, SupplierPaymentOperation after, Action action, UUID resolutionId, UUID event) {
        var source = context.authorization().source().reservation().source();
        return new Receipt(after.command().id(), source.requestId(), source.applicationId(), source.round().roundNo(), action, after.version(), after.status(), resolutionId, event);
    }
    private UUID audit(SupplierPaymentOperation before, SupplierPaymentOperation after, Action action, String comment, Instant now) {
        var source = before.command().holdCommand().authorization().source().reservation().source(); var event = UUID.randomUUID();
        var payload = Map.of("requestId", source.requestId(), "roundNo", source.round().roundNo(), "paymentId", before.command().id(),
                "authorizedRole", "FINANCE", "previousStatus", before.status().name(), "currentStatus", after.status().name(), "comment", comment.trim());
        jdbc.update("""
                INSERT INTO audit_event(id,tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,application_id,action,actor_id,payload_json,occurred_at)
                VALUES(?,?,?,'SupplierPaymentDispute',?,?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), source.tenantId(), event.toString(), before.command().id().toString(), after.version(), source.applicationId().toString(),
                "SUPPLIER_PAYMENT_DISPUTE_" + action, actors.actor().userId(), json.write(payload), Timestamp.from(now));
        return event;
    }
    private static Fact fact(PaymentObservation value) {
        return value == null ? null : new Fact(value.status(), value.revision(), value.observedAt(), value.observedAt().plus(SupplierPaymentOperation.DISPUTE_EVIDENCE_LIFETIME),
                value.paymentReference(), value.receiptReference(), value.completedAt(), value.failure());
    }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }

    /**
     * 原版本与操作说明用于明确查询，不接受新账户或财务状态。
     * @author owlzhangfq@gmail.com
     */
    public record QueryInput(@Positive long operationVersion, @NotBlank @Size(max = 2000) String comment) {
        /** 拒绝客户端注入回执或其他交易身份。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown supplier dispute query field"); }
    }
    /**
     * 请求只选择已展示的银行终态，不接收金额、账户或回单事实。
     * @author owlzhangfq@gmail.com
     */
    public record ResolveInput(@Positive long operationVersion, @NotNull PaymentObservation.Status outcome,
            @NotBlank @Size(max = 128) @Pattern(regexp = "[^\\p{Cntrl}]+") String evidenceReference, @NotBlank @Size(max = 2000) String comment) {
        /** 非终态不能由人工强制宣布为资金结果。 */
        public ResolveInput {
            if (outcome != null && outcome != PaymentObservation.Status.SUCCEEDED && outcome != PaymentObservation.Status.FAILED && outcome != PaymentObservation.Status.REVERSED)
                throw new IllegalArgumentException("A terminal supplier bank outcome is required");
        }
        /** 自报银行事实不能被静默忽略。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown supplier dispute resolution field"); }
    }
    /**
     * 当前候选与历史决定独立展示，既有决定不隐藏后续争议。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(UUID paymentId, UUID requestId, UUID applicationId, int roundNo, Long operationVersion, SupplierPaymentOperation.Status status,
            Fact observed, Fact candidate, SupplierPaymentOperation.ResolutionIssue issue, boolean canQuery, boolean canResolve, Decision latest) { }
    /**
     * 只展示原交易与回单的必要信息，完整金额和账户仍由银行领域核对。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Fact(PaymentObservation.Status outcome, long revision, Instant observedAt, Instant validUntil, String paymentReference, String receiptReference, Instant completedAt, PaymentObservation.Failure failure) { }
    /**
     * 历史决定可定位原结果修订，办理说明只保留在受控审计中。
     * @author owlzhangfq@gmail.com
     */
    public record Decision(UUID id, long operationVersion, PaymentObservation.Status outcome, String resolvedBy, Instant resolvedAt, String evidenceReference) { }
    /**
     * 202 只确认本地人工动作保存，查询后的实际银行结果需再次读取。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Receipt(UUID paymentId, UUID requestId, UUID applicationId, int roundNo, Action action, long operationVersion,
            SupplierPaymentOperation.Status status, UUID resolutionId, UUID auditEventId) { }

    /**
     * 原银行查询与人工裁决分别留痕，不能混同为重新付款。
     * @author owlzhangfq@gmail.com
     */
    public enum Action { QUERY, RESOLVE }
}
