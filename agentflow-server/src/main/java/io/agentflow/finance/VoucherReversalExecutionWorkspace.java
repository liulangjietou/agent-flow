package io.agentflow.finance;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 冲销准备与执行投影只包含当前已授权的原件、日期和分录，完整历史命令及财务目标不外泄。
 * @author owlzhangfq@gmail.com
 */
@Service
public class VoucherReversalExecutionWorkspace {
    private final CurrentActor actors;
    private final VoucherAccess access;
    private final VoucherReversalSources sources;
    private final PaymentPersonnel personnel;
    private final JdbcVoucherReversalPreparationRepository preparations;
    private final JdbcVoucherReversalOperationRepository operations;
    private final JdbcVoucherReversalRecordRepository records;
    private final VoucherReversalPreparationService preparing;
    private final VoucherReversalExecutionService execution;
    /** 准备候选只供原财务读取，已授权操作沿原轮次完整字段权限投影。 */
    public VoucherReversalExecutionWorkspace(CurrentActor actors, VoucherAccess access, VoucherReversalSources sources, PaymentPersonnel personnel,
            JdbcVoucherReversalPreparationRepository preparations, JdbcVoucherReversalOperationRepository operations, JdbcVoucherReversalRecordRepository records,
            VoucherReversalPreparationService preparing, VoucherReversalExecutionService execution) {
        this.actors = actors; this.access = access; this.sources = sources; this.personnel = personnel; this.preparations = preparations;
        this.operations = operations; this.records = records; this.preparing = preparing; this.execution = execution;
    }
    /** 读取不排队、不延长准备时效，也不自动授权发送。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View read(UUID applicationId, UUID operationId, Map<String, String> parameters) {
        if (!Set.of("roundNo").containsAll(parameters.keySet())) throw invalid();
        Integer round = null;
        if (parameters.containsKey("roundNo")) { var raw = parameters.get("roundNo"); if (!raw.matches("[1-9][0-9]{0,8}")) throw invalid(); round = Integer.valueOf(raw); }
        var context = access.read(applicationId, round); var source = sources.find(context, operationId); var original = source.request().original(); var command = source.request().command(); var actor = actors.actor();
        boolean finance = context.finance() && VoucherDisputeResolution.independent(command, actor.userId()) && personnel.eligible(actor.tenantId(), actor.userId(), command.legalEntityId());
        var prepared = finance ? preparations.latest(actor.tenantId(), operationId, actor.userId()).orElse(null) : null;
        var operation = operations.forOriginal(actor.tenantId(), operationId).orElse(null);
        boolean recorded = records.forOperation(actor.tenantId(), operationId).isPresent();
        return new View(applicationId, operationId, context.roundNo(), context.application().version(), context.businessVersion(), source.current().version(), command.kind(),
                source.current().status(), source.current().reversalId() != null, new VoucherReversalWorkspace.Original(original.postingReference(), original.voucherReference(), original.periodReference(), original.accountingDate(), original.debitTotal(), original.postedAt()),
                finance && source.current().usablePosted() && operation == null && !recorded && (prepared == null || !prepared.active()),
                prepared == null ? null : preparation(prepared, source), operation == null ? null : operation(operation, source, finance));
    }
    private Preparation preparation(VoucherReversalPreparation value, VoucherReversalSources.Source source) {
        var input = value.input(); var command = value.command(); var issue = preparing.authorizationIssue(value, source, Instant.now());
        return new Preparation(input.id(), value.version(), value.status(), input.requestedAt(), value.updatedAt(), value.issue(), input.accountingDate(), input.evidenceReference(), input.reason(),
                command == null ? null : new Candidate(command.createdAt(), command.expiresAt(), command.verifiedOriginal().revision(), command.verifiedOriginal().observedAt(),
                        command.period().periodReference(), command.period().sourceVersion(), command.lines()), issue == null, issue);
    }
    private Execution operation(VoucherReversalOperation value, VoucherReversalSources.Source source, boolean finance) {
        var command = value.input().command();
        boolean resend = finance && actors.actor().userId().equals(command.authorizedBy()) && value.status() == VoucherReversalOperation.Status.NOT_FOUND
                && execution.resendIssue(value, source, Instant.now()) == null;
        return new Execution(command.id(), value.version(), value.status(), value.attempts(), value.highestRevision(), value.failure() == null ? null : value.failure().name(),
                value.createdAt(), command.expiresAt(), value.updatedAt(), value.nextAttemptAt(), command.authorizedBy(), command.period().request().accountingDate(), command.evidenceReference(), command.reason(), command.lines(),
                observation(value.observation()), observation(value.conflictingObservation()), finance && !value.running() && value.status() != VoucherReversalOperation.Status.QUEUED, resend);
    }
    private Observation observation(VoucherReversalObservation value) {
        return value == null ? null : new Observation(value.status(), value.revision(), value.observedAt(), value.acceptanceReference(), value.posting() == null ? null : value.posting().reversal(), value.rejection() == null ? null : value.rejection().name());
    }
    private static DomainException invalid() { return new DomainException("INVALID_VOUCHER_REVERSAL_QUERY", "Only a positive roundNo is accepted for reversal execution status"); }
    /**
     * 原件是否停用与 ERP 原状态分别展示，避免过账标签被误当成仍可继续付款。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(UUID applicationId, UUID operationId, int roundNo, long applicationVersion, long businessVersion, long operationVersion, VoucherCommand.Kind kind,
            VoucherOperation.Status originalStatus, boolean originalHeld, VoucherReversalWorkspace.Original original, boolean canPrepare, Preparation latestPreparation, Execution operation) { }
    /**
     * 本人最新准备及当前实际可授权条件，候选不含完整原支付命令。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Preparation(UUID id, long version, VoucherReversalPreparation.Status status, Instant requestedAt, Instant updatedAt, String issue,
            LocalDate accountingDate, String evidenceReference, String reason, Candidate candidate, boolean canAuthorize, String authorizationIssue) { }
    /**
     * 所有拟发送分录、期间版本和时效一起展示供财务审阅。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(Instant createdAt, Instant expiresAt, long originalRevision, Instant originalObservedAt, String periodReference, String periodSourceVersion, List<VoucherReversalCommand.Line> lines) { }
    /**
     * 已授权独立操作及已接受、矛盾两类回执分别保存，未知结果不会被展示为失败未执行。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Execution(UUID id, long version, VoucherReversalOperation.Status status, int attempts, long highestRevision, String failure,
            Instant createdAt, Instant sendExpiresAt, Instant updatedAt, Instant nextAttemptAt, String authorizedBy, LocalDate accountingDate, String evidenceReference, String reason,
            List<VoucherReversalCommand.Line> lines, Observation observation, Observation conflictingObservation, boolean canQuery, boolean canResendOriginal) { }
    /**
     * ERP 回执只投影执行状态和实际独立分录，不回显原命令及账户。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Observation(VoucherReversalObservation.Status status, long revision, Instant observedAt, String acceptanceReference, VoucherReversalPort.Posting posting, String rejection) { }
}
