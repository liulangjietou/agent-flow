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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * 财务冲销准备与明确授权的应用编排，读证据和实际写命令分开办理。
 * @author owlzhangfq@gmail.com
 */
@Service
public class VoucherReversalPreparationService {
    private static final Duration LEASE = Duration.ofSeconds(90);
    private final CurrentActor actors;
    private final VoucherDisputeService access;
    private final VoucherReversalSources sources;
    private final PaymentPersonnel personnel;
    private final JdbcVoucherReversalPreparationRepository preparations;
    private final JdbcVoucherReversalOperationRepository operations;
    private final JdbcVoucherReversalRecordRepository records;
    private final VoucherReversalExecutionService execution;
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final ApplicationEventPublisher events;
    /** 独立财务、原字段权限和当前任职继续复用原凭证边界。 */
    public VoucherReversalPreparationService(CurrentActor actors, VoucherDisputeService access, VoucherReversalSources sources,
            PaymentPersonnel personnel, JdbcVoucherReversalPreparationRepository preparations, JdbcVoucherReversalOperationRepository operations,
            JdbcVoucherReversalRecordRepository records, VoucherReversalExecutionService execution, JdbcTemplate jdbc, JsonUtil json, ApplicationEventPublisher events) {
        this.actors = actors; this.access = access; this.sources = sources; this.personnel = personnel; this.preparations = preparations;
        this.operations = operations; this.records = records; this.execution = execution; this.jdbc = jdbc; this.json = json; this.events = events;
    }
    /** 幂等回放也必须复核原轮次完整字段权限与当前独立财务身份。 */
    public void authorizeAccess(UUID applicationId, UUID operationId, int round) { access.requireFinance(applicationId, operationId, round); }
    /** 明确日期和原因后只登记读取意图，尚未停用原件或授权 ERP 写入。 */
    @Transactional
    public ActionReceipt prepare(UUID applicationId, UUID operationId, PrepareInput input) {
        var context = locked(applicationId, operationId, input.roundNo()); var source = sources.find(context, operationId);
        requireVersions(context, source, input.applicationVersion(), input.businessVersion(), input.operationVersion()); requireOriginal(source);
        var actor = actors.actor(); var latest = preparations.latest(actor.tenantId(), operationId, actor.userId()).orElse(null);
        if (latest != null && latest.active()) throw new DomainException("VOUCHER_REVERSAL_PENDING", "Current finance actor already has a pending reversal preparation");
        var now = time(Instant.now()); var value = VoucherReversalPreparation.queue(new VoucherReversalPreparation.Input(UUID.randomUUID(), source.originalVersion(), source.current().version(),
                context.application().version(), context.businessVersion(), source.request(), source.current().input().targetDigest(), input.accountingDate(), actor.userId(), input.evidenceReference(), input.comment(), now));
        preparations.create(value); var audit = audit(value, value.version(), "VOUCHER_REVERSAL_PREPARE", input.comment(), now);
        return receipt(value, source.current().version(), null, audit);
    }
    /** 只采纳本人最新且未过期的准备，消费证据、登记命令和停用原件同事务完成。 */
    @Transactional
    public ActionReceipt authorize(UUID applicationId, UUID operationId, AuthorizeInput input) {
        var context = locked(applicationId, operationId, input.roundNo()); var source = sources.find(context, operationId);
        requireVersions(context, source, input.applicationVersion(), input.businessVersion(), input.operationVersion()); requireOriginal(source);
        var actor = actors.actor(); var prepared = preparations.find(actor.tenantId(), input.preparationId()).orElseThrow(VoucherReversalPreparationService::conflict);
        if (prepared.version() != input.preparationVersion() || !prepared.input().requestedBy().equals(actor.userId())
                || !preparations.latest(actor.tenantId(), operationId, actor.userId()).filter(value -> value.input().id().equals(prepared.input().id())).isPresent()) throw conflict();
        requirePreparedSource(prepared, source); var now = time(Instant.now()); var consumed = prepared.authorize(now); persist(prepared, consumed);
        var operation = execution.register(consumed, now); var event = audit(consumed, operation.version(), "VOUCHER_REVERSAL_AUTHORIZE", input.comment(), now);
        return receipt(consumed, source.current().version() + 1, operation, event);
    }
    /** 后续查询可由当前独立财务办理，重发仍要求原授权人明确确认。 */
    @Transactional
    public ActionReceipt act(UUID applicationId, UUID operationId, OperationInput input) {
        var context = locked(applicationId, operationId, input.roundNo()); var source = sources.find(context, operationId);
        requireVersions(context, source, input.applicationVersion(), input.businessVersion(), input.operationVersion());
        var actor = actors.actor(); var original = operations.forOriginal(actor.tenantId(), operationId).orElseThrow(VoucherReversalPreparationService::conflict);
        if (!original.input().command().id().equals(input.reversalId()) || original.version() != input.reversalVersion()
                || input.action() == Action.RESEND_ORIGINAL && !original.input().command().authorizedBy().equals(actor.userId())) throw conflict();
        var now = time(Instant.now()); var next = input.action() == Action.QUERY ? execution.query(actor.tenantId(), input.reversalId(), input.reversalVersion(), now)
                : execution.resend(actor.tenantId(), input.reversalId(), input.reversalVersion(), now);
        var prepared = preparations.find(actor.tenantId(), input.reversalId()).orElseThrow(); var event = audit(prepared, next.version(), "VOUCHER_REVERSAL_" + input.action().name(), input.comment(), now);
        return receipt(prepared, source.current().version(), next, event);
    }
    /** 短事务领取前复核原件与人员，不在锁内读取 ERP。 */
    @Transactional
    public VoucherReversalPreparation claim(String tenant, UUID id, Instant at) {
        var initial = preparations.find(tenant, id).orElse(null); if (initial == null || !initial.active()) return null;
        var source = sources.locked(tenant, initial.input().source().command().id()); var current = preparations.find(tenant, id).orElseThrow(); var now = time(at);
        if (current.expired(now)) { persist(current, current.fail("TIMEOUT", now)); return null; }
        if (current.status() != VoucherReversalPreparation.Status.QUEUED || !available(current, source, now)) return null;
        var claimed = current.claim(now, LEASE); persist(current, claimed); return claimed;
    }
    /** 原件与期间只能形成候选，完成读取不会自动登记冲销命令。 */
    @Transactional
    public void finish(VoucherReversalPreparation claimed, FinanceResult<VoucherObservation> original, FinanceResult<AccountingPeriodPort.OpenPeriod> period, Instant at) {
        var source = sources.locked(claimed.input().source().command().tenantId(), claimed.input().source().command().id()); var current = current(claimed); if (current == null) return; var now = time(at);
        if (!available(current, source, now)) return;
        if (current.expired(now)) { persist(current, current.fail("TIMEOUT", now)); return; }
        if (!(original instanceof FinanceResult.Success<VoucherObservation> posted)) { persist(current, current.fail(failure(original), now)); return; }
        if (!(period instanceof FinanceResult.Success<AccountingPeriodPort.OpenPeriod> opened)) { persist(current, current.fail(failure(period), now)); return; }
        VoucherReversalPreparation next;
        try { next = current.ready(posted.value(), opened.value(), now); }
        catch (DomainException invalid) { next = current.fail("INVALID_RESPONSE", now); }
        persist(current, next);
    }
    /** 失败只保存稳定分类，晚到任务不能覆盖已经消费的授权。 */
    @Transactional
    public void fail(VoucherReversalPreparation claimed, Instant at) {
        sources.locked(claimed.input().source().command().tenantId(), claimed.input().source().command().id()); var current = current(claimed);
        if (current != null) persist(current, current.fail("INTERNAL_ERROR", time(at)));
    }
    /** 页面和写入口共享候选边界；当前权限由各自读取入口先行检查。 */
    public String authorizationIssue(VoucherReversalPreparation prepared, VoucherReversalSources.Source source, Instant now) {
        if (prepared == null || prepared.status() != VoucherReversalPreparation.Status.READY) return "VOUCHER_REVERSAL_NOT_READY";
        try { requirePreparedSource(prepared, source); prepared.command().requireSendAt(now); return null; }
        catch (DomainException issue) { return issue.code(); }
    }
    private VoucherAccess.Context locked(UUID app, UUID operation, int round) {
        authorizeAccess(app, operation, round); sources.locked(actors.actor().tenantId(), operation); return access.requireFinance(app, operation, round);
    }
    private void requireOriginal(VoucherReversalSources.Source source) {
        var original = source.request().command();
        if (!source.current().usablePosted()) throw new DomainException("VOUCHER_REVERSAL_SOURCE_CHANGED", "Original voucher must remain an available posted voucher");
        if (operations.forOriginal(original.tenantId(), original.id()).isPresent()) throw new DomainException("VOUCHER_REVERSAL_OPERATION_EXISTS", "Original voucher already has a reversal command");
        if (records.forOperation(original.tenantId(), original.id()).isPresent()) throw new DomainException("VOUCHER_REVERSAL_ALREADY_RECORDED", "Original voucher already has an independent reversal record");
    }
    private void requirePreparedSource(VoucherReversalPreparation prepared, VoucherReversalSources.Source source) {
        requireOriginal(source); var input = prepared.input(); var original = input.source().command();
        if (input.operationVersion() != source.current().version() || input.originalVersion() != source.originalVersion()
                || !input.source().equals(source.request()) || !input.targetDigest().equals(source.current().input().targetDigest())) throw conflict();
        sources.requireVersions(input); personnel.requireEligible(original.tenantId(), input.requestedBy(), original.legalEntityId());
    }
    private boolean available(VoucherReversalPreparation value, VoucherReversalSources.Source source, Instant now) {
        try { requirePreparedSource(value, source); return true; }
        catch (DomainException changed) { persist(value, value.voidSource(now)); return false; }
    }
    private VoucherReversalPreparation current(VoucherReversalPreparation claimed) {
        return preparations.find(claimed.input().source().command().tenantId(), claimed.input().id()).filter(value -> value.equals(claimed) && value.status() == VoucherReversalPreparation.Status.RUNNING).orElse(null);
    }
    private void requireVersions(VoucherAccess.Context context, VoucherReversalSources.Source source, long application, long business, long operation) {
        if (context.application().version() != application || context.businessVersion() != business || source.current().version() != operation) throw conflict();
    }
    private void persist(VoucherReversalPreparation previous, VoucherReversalPreparation next) {
        preparations.update(next); events.publishEvent(new VoucherReversalPreparationChanged(previous, next));
    }
    private String failure(FinanceResult<?> result) {
        if (result instanceof FinanceResult.Unavailable<?> value) return value.failure().name();
        if (result instanceof FinanceResult.Rejected<?> value) return value.reason().name();
        return "INVALID_RESPONSE";
    }
    private UUID audit(VoucherReversalPreparation value, long version, String action, String comment, Instant now) {
        var input = value.input(); var command = input.source().command(); var event = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO audit_event(id,tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,application_id,action,actor_id,payload_json,occurred_at)
                VALUES(?,?,?,'VoucherReversalExecution',?,?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), command.tenantId(), event.toString(), input.id().toString(), version, command.binding().applicationId().toString(), action,
                actors.actor().userId(), json.write(Map.of("operationId", command.id(), "roundNo", command.binding().roundNo(), "authorizedRole", "FINANCE", "comment", comment)), Timestamp.from(now));
        return event;
    }
    private ActionReceipt receipt(VoucherReversalPreparation value, long originalVersion, VoucherReversalOperation operation, UUID audit) {
        var command = value.input().source().command();
        return new ActionReceipt(command.binding().applicationId(), command.id(), command.binding().roundNo(), value.input().id(), value.version(),
                operation == null ? null : operation.input().command().id(), operation == null ? null : operation.version(), originalVersion, audit);
    }
    private static Instant time(Instant at) { return at.truncatedTo(ChronoUnit.MICROS); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Displayed source or reversal preparation changed"); }
    /**
     * 客户端选择日期和原因，原凭证及会计明细始终由服务端定位。
     * @author owlzhangfq@gmail.com
     */
    public record PrepareInput(@Positive int roundNo, @Positive long applicationVersion, @Positive long businessVersion, @Positive long operationVersion,
            @NotNull LocalDate accountingDate, @NotBlank @Size(max = 128) @Pattern(regexp = "[^\\p{Cntrl}]+") String evidenceReference, @NotBlank @Size(max = 2000) String comment) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown reversal preparation field"); }
    }
    /**
     * 明确采纳已经展示的完整原准备，不能在确认时改变金额、日期或原因。
     * @author owlzhangfq@gmail.com
     */
    public record AuthorizeInput(@Positive int roundNo, @Positive long applicationVersion, @Positive long businessVersion, @Positive long operationVersion,
            @NotNull UUID preparationId, @Positive long preparationVersion, @NotBlank @Size(max = 2000) String comment) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown reversal authorization field"); }
    }
    /**
     * 未知查询与权威查无后的明确重发使用原编号和双重页面版本。
     * @author owlzhangfq@gmail.com
     */
    public record OperationInput(@Positive int roundNo, @Positive long applicationVersion, @Positive long businessVersion, @Positive long operationVersion,
            @NotNull UUID reversalId, @Positive long reversalVersion, @NotNull Action action, @NotBlank @Size(max = 2000) String comment) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown reversal action field"); }
    }
    /**
     * 读取原操作与重发原命令分别呈现，不提供自动改号操作。
     * @author owlzhangfq@gmail.com
     */
    public enum Action { QUERY, RESEND_ORIGINAL }
    /**
     * 回执只关联本地事实；202 不代表 ERP 已过账或冲销已登记。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ActionReceipt(UUID applicationId, UUID operationId, int roundNo, UUID preparationId, long preparationVersion,
            UUID reversalId, Long reversalVersion, long operationVersion, UUID auditEventId) { }
}
