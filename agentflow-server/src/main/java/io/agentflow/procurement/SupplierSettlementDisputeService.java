package io.agentflow.procurement;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.Money;
import io.agentflow.procurement.mapper.SupplierSettlementDisputeServiceMapper;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * 原 ERP 争议在当前独立财务权限下明确裁决，决定、修订、本地完成及审计共同提交。
 *
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierSettlementDisputeService {
    private final CurrentActor actors;
    private final SupplierSettlementAccess access;
    private final SupplierSettlementSources sources;
    private final ApplicationEventPublisher events;
    private final JdbcSupplierPayableSettlementRepository settlements;
    private final SupplierSettlementService execution;
    private final SupplierSettlementDisputeServiceMapper sqlMapper;
    private final JsonUtil json;

    /** 原号查询复用既有结算入口；裁决事务不执行银行或 ERP 网络调用。 */
    public SupplierSettlementDisputeService(
            CurrentActor actors,
            SupplierSettlementAccess access,
            SupplierSettlementSources sources,
            JdbcSupplierPayableSettlementRepository settlements,
            SupplierSettlementService execution,
            SupplierSettlementDisputeServiceMapper sqlMapper,
            JsonUtil json,
            ApplicationEventPublisher events) {
        this.actors = actors;
        this.events = events;
        this.access = access;
        this.sources = sources;
        this.settlements = settlements;
        this.execution = execution;
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 历史决定与当前候选分别展示，读取仍受原轮次完整财务字段权限约束。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View read(UUID id, Map<String, String> parameters) {
        if (!parameters.isEmpty())
            throw new DomainException(
                    "INVALID_SUPPLIER_SETTLEMENT_DISPUTE_QUERY",
                    "Original ERP dispute does not accept alternate identities or query"
                            + " parameters");
        var value =
                settlements
                        .find(actors.actor().tenantId(), id)
                        .orElseThrow(SupplierSettlementDisputeService::notFound);
        var command = value.command();
        var context = access.read(command.payment().id());
        var source =
                command.payment().holdCommand().authorization().source().reservation().source();
        var issue =
                value.conflictingObservation() == null
                        ? null
                        : value.resolutionIssue(
                                settlements.resolutionHistory(command.tenantId(), id),
                                Instant.now());
        var latest = settlements.latestResolution(command.tenantId(), id).orElse(null);
        return new View(
                id,
                command.payment().id(),
                source.requestId(),
                source.applicationId(),
                source.round().roundNo(),
                value.version(),
                value.status(),
                command.payment().amount(),
                fact(value.observation()),
                fact(value.conflictingObservation()),
                issue,
                context.finance()
                        && settlements.retirement(command.tenantId(), id).isEmpty()
                        && value.conflictingObservation() != null
                        && issue == null,
                latest == null
                        ? null
                        : new Decision(
                                latest.id(),
                                latest.resolvedVersion(),
                                latest.observation().status(),
                                latest.resolvedBy(),
                                latest.resolvedAt(),
                                latest.evidenceReference()));
    }

    /** 候选只能来自原核销修订，成功裁决在同一事务内尝试补全原占用且不会重复完成。 */
    @Transactional
    public Receipt resolve(UUID id, ResolveInput input) {
        var before = access.requireSettlement(id); var tenant = actors.actor().tenantId(); sources.lock(tenant, before.command().payment().id());
        before = access.requireSettlement(id);
        if (before.version() != input.settlementVersion() || settlements.retirement(tenant, id).isPresent()) {
            throw new DomainException("CONCURRENCY_CONFLICT", "Displayed original supplier settlement changed or was safely retired");
        }
        var candidate = before.conflictingObservation();
        if (candidate == null || candidate.status() != input.outcome()) throw new DomainException("SUPPLIER_SETTLEMENT_DISPUTE_UNRESOLVABLE", "Displayed original ERP terminal evidence changed");
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var decision = new SupplierSettlementDisputeResolution(UUID.randomUUID(), tenant, id, before.version(), Math.incrementExact(before.version()), candidate,
                actors.actor().userId(), now, input.evidenceReference().trim(), input.comment());
        var after = settlements.resolve(decision); execution.completeLocal(tenant, id, now); events.publishEvent(new SupplierSettlementChanged.Operation(after));
        var event = audit(before, after, decision, now); var source = before.command().payment().holdCommand().authorization().source().reservation().source();
        return new Receipt(id, before.command().payment().id(), source.requestId(), source.applicationId(), source.round().roundNo(), after.version(), after.status(), decision.id(), event);
    }

    private UUID audit(
            SupplierPayableSettlementOperation before,
            SupplierPayableSettlementOperation after,
            SupplierSettlementDisputeResolution decision,
            Instant now) {
        var source =
                before.command()
                        .payment()
                        .holdCommand()
                        .authorization()
                        .source()
                        .reservation()
                        .source();
        var event = UUID.randomUUID();
        var payload =
                Map.of(
                        "requestId",
                        source.requestId(),
                        "roundNo",
                        source.round().roundNo(),
                        "paymentId",
                        before.command().payment().id(),
                        "resolutionId",
                        decision.id(),
                        "authorizedRole",
                        "FINANCE",
                        "previousStatus",
                        before.status().name(),
                        "currentStatus",
                        after.status().name(),
                        "comment",
                        decision.reason());
        sqlMapper.audit(
                UUID.randomUUID().toString(),
                source.tenantId(),
                event.toString(),
                before.command().id().toString(),
                after.version(),
                source.applicationId().toString(),
                actors.actor().userId(),
                json.write(payload),
                Timestamp.from(now));
        return event;
    }

    private static Fact fact(SupplierPayableSettlementObservation value) {
        if (value == null) return null;
        var posting = value.posting();
        return new Fact(value.status(), value.revision(), value.observedAt(), value.observedAt().plus(SupplierPayableSettlementOperation.DISPUTE_EVIDENCE_LIFETIME), value.rejection(),
                posting == null ? null : new Posting(posting.settlementReference(), posting.voucherReference(), posting.settledAmount(), posting.settledBefore(), posting.settledAfter(),
                        posting.periodReference(), posting.accountingDate(), posting.settledAt()));
    }

    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Original supplier ERP settlement is unavailable in the current scope"); }

    /**
     * 输入只选择服务端候选终态，不接收金额、账务身份、回执或办理人。
     *
     * @author owlzhangfq@gmail.com
     */
    public record ResolveInput(
            @Positive long settlementVersion,
            @NotNull SupplierPayableSettlementObservation.Status outcome,
            @NotBlank @Size(max = 128) @Pattern(regexp = "[^\\p{Cntrl}]+") String evidenceReference,
            @NotBlank @Size(max = 2000) String comment) {
        /** 未确定的 ERP 结果不能由人工强制宣布为核销终态。 */
        public ResolveInput {
            if (outcome != null
                    && outcome != SupplierPayableSettlementObservation.Status.SETTLED
                    && outcome != SupplierPayableSettlementObservation.Status.REJECTED)
                throw new IllegalArgumentException("A terminal original ERP outcome is required");
        }

        /** 拒绝客户端伪造凭证、余额或其他核销身份。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown supplier ERP dispute resolution field"); }
    }

    /**
     * 当前候选与最近决定并列，旧决定不会隐藏后续争议。
     *
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(
            UUID settlementId,
            UUID paymentId,
            UUID requestId,
            UUID applicationId,
            int roundNo,
            long settlementVersion,
            SupplierPayableSettlementOperation.Status status,
            Money amount,
            Fact observed,
            Fact candidate,
            SupplierPayableSettlementOperation.ResolutionIssue issue,
            boolean canResolve,
            Decision latest) {}

    /**
     * 必要核对信息不包含原始资金账户、命令摘要或内部台账标识。
     *
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Fact(
            SupplierPayableSettlementObservation.Status outcome,
            long revision,
            Instant observedAt,
            Instant validUntil,
            SupplierPayableSettlementObservation.Rejection rejection,
            Posting posting) {}

    /**
     * 原凭证、核销额及累计前后余额用于人工核对，事实始终由服务端绑定原指令。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Posting(
            String settlementReference,
            String voucherReference,
            Money amount,
            Money settledBefore,
            Money settledAfter,
            String periodReference,
            LocalDate accountingDate,
            Instant settledAt) {}

    /**
     * 历史决定关联原核销的结果修订，说明保留于受控审计。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Decision(
            UUID id,
            long settlementVersion,
            SupplierPayableSettlementObservation.Status outcome,
            String resolvedBy,
            Instant resolvedAt,
            String evidenceReference) {}

    /**
     * 回执只确认独立裁决保存；银行和本地完成状态需要重新读取。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Receipt(
            UUID settlementId,
            UUID paymentId,
            UUID requestId,
            UUID applicationId,
            int roundNo,
            long settlementVersion,
            SupplierPayableSettlementOperation.Status status,
            UUID resolutionId,
            UUID auditEventId) {}
}
