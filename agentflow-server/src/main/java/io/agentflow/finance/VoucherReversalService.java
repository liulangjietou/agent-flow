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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * 应用层编排原申请授权、短事务核验和独立登记；实际反向分录的有效性归领域。
 * @author owlzhangfq@gmail.com
 */
@Service
public class VoucherReversalService {
    private static final Duration QUERY_LEASE = Duration.ofSeconds(90);
    private final CurrentActor actors;
    private final VoucherDisputeService access;
    private final VoucherReversalSources sources;
    private final PaymentPersonnel personnel;
    private final JdbcVoucherReversalCheckRepository checks;
    private final JdbcVoucherReversalRecordRepository records;
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    /** 复用现有独立财务和法人任职检查，不建立另一套敏感字段旁路。 */
    public VoucherReversalService(CurrentActor actors, VoucherDisputeService access, VoucherReversalSources sources, PaymentPersonnel personnel,
            JdbcVoucherReversalCheckRepository checks, JdbcVoucherReversalRecordRepository records, JdbcTemplate jdbc, JsonUtil json) {
        this.actors = actors; this.access = access; this.sources = sources; this.personnel = personnel;
        this.checks = checks; this.records = records; this.jdbc = jdbc; this.json = json;
    }
    /** 幂等回放之前仍必须具有原轮次完整字段权限及当前独立财务身份。 */
    public void authorize(UUID applicationId, UUID operationId, int roundNo) { access.requireFinance(applicationId, operationId, roundNo); }
    /** 原凭证已明确冲销后才查询独立分录，不把查询结果当成新的过账命令。 */
    @Transactional
    public ActionReceipt queue(UUID applicationId, UUID operationId, QueryInput input) {
        authorize(applicationId, operationId, input.roundNo()); sources.locked(actors.actor().tenantId(), operationId);
        var context = access.requireFinance(applicationId, operationId, input.roundNo()); var source = sources.find(context, operationId);
        requireVersions(context, source, input.applicationVersion(), input.businessVersion(), input.operationVersion()); requireRecordableSource(source);
        var actor = actors.actor(); var latest = checks.latest(actor.tenantId(), operationId, actor.userId()).orElse(null);
        if (latest != null && latest.active()) throw new DomainException("VOUCHER_REVERSAL_PENDING", "Current finance actor already has a pending reversal query");
        var now = time(Instant.now()); var check = VoucherReversalCheck.queue(new VoucherReversalCheck.Input(UUID.randomUUID(), actor.tenantId(), source.current().input().targetDigest(),
                source.originalVersion(), source.request(), actor.userId(), now));
        checks.create(check); var event = audit(source, check.input().id(), check.version(), "VOUCHER_REVERSAL_QUERY", input.comment(), now);
        return new ActionReceipt(applicationId, operationId, input.roundNo(), check.input().id(), check.version(), null, source.current().version(), event);
    }
    /** 消费查询、追加独立冲销和审计同事务完成，原凭证与原资金事实保持不变。 */
    @Transactional
    public ActionReceipt record(UUID applicationId, UUID operationId, RecordInput input) {
        authorize(applicationId, operationId, input.roundNo()); sources.locked(actors.actor().tenantId(), operationId);
        var context = access.requireFinance(applicationId, operationId, input.roundNo()); var source = sources.find(context, operationId);
        requireVersions(context, source, input.applicationVersion(), input.businessVersion(), input.operationVersion()); requireRecordableSource(source);
        var actor = actors.actor(); var check = checks.find(actor.tenantId(), input.checkId()).orElseThrow(VoucherReversalService::conflict);
        if (check.version() != input.checkVersion() || !check.input().requestedBy().equals(actor.userId())
                || !checks.latest(actor.tenantId(), operationId, actor.userId()).map(value -> value.input().id().equals(check.input().id())).orElse(false)) throw conflict();
        requireCheck(check, source); var now = time(Instant.now()); var issue = confirmationIssue(source, check, now);
        if (issue != null) throw new DomainException(issue, "Reversal record requires fresh unchanged evidence for the current original voucher");
        var record = new VoucherReversalRecord(UUID.randomUUID(), actor.tenantId(), check.input().id(), source.current().version(), check.receipt(), actor.userId(), now, input.evidenceReference(), input.comment());
        var consumed = check.record(record, now); checks.update(consumed); records.create(record);
        var event = audit(source, record.id(), consumed.version(), "VOUCHER_REVERSAL_RECORD", input.comment(), now);
        return new ActionReceipt(applicationId, operationId, input.roundNo(), check.input().id(), consumed.version(), record.id(), source.current().version(), event);
    }
    /** 展示和提交共享守卫，任何财务已见的较新或矛盾证据都会阻止旧记录。 */
    public String confirmationIssue(VoucherReversalSources.Source source, VoucherReversalCheck check, Instant now) {
        if (records.forOperation(source.request().command().tenantId(), source.request().command().id()).isPresent()) return "VOUCHER_REVERSAL_ALREADY_RECORDED";
        if (source.current().status() != VoucherOperation.Status.REVERSED) return "VOUCHER_REVERSAL_ORIGINAL_UNRESOLVED";
        if (check == null || !check.usable(now)) return "VOUCHER_REVERSAL_EVIDENCE_UNAVAILABLE";
        if (!check.receipt().matchesCurrent(source.current().observation()) || evidenceChanged(check)) return "VOUCHER_REVERSAL_EVIDENCE_CHANGED";
        return null;
    }
    private boolean evidenceChanged(VoucherReversalCheck check) {
        var receipt = check.receipt();
        return checks.history(check.input().tenantId(), receipt.request().command().id()).stream().anyMatch(value -> {
            var previous = value.receipt();
            return previous.revision() > receipt.revision() || previous.observedAt().isAfter(receipt.observedAt())
                    || previous.current() != null && previous.current().revision() > receipt.current().revision()
                    || !receipt.preservesReversal(previous)
                    || previous.revision() == receipt.revision() && previous.status() != VoucherReversalPort.Status.UNRESOLVED && previous.status() != receipt.status();
        });
    }
    /** 领取只持有短事务，过期租约不能重新发送或被迟到结果覆盖。 */
    @Transactional
    public VoucherReversalCheck claim(String tenant, UUID id, Instant at) {
        var initial = checks.find(tenant, id).orElse(null); if (initial == null || !initial.active()) return null;
        var source = sources.locked(tenant, initial.input().request().command().id()); var current = checks.find(tenant, id).orElseThrow(); var now = time(at);
        if (current.expired(now)) { checks.update(current.fail(VoucherReversalCheck.Issue.TIMEOUT, now)); return null; }
        if (current.status() != VoucherReversalCheck.Status.QUEUED || !available(current, source, now)) return null;
        var claimed = current.claim(now, QUERY_LEASE); checks.update(claimed); return claimed;
    }
    /** 保存外部读取结果只形成待确认候选，不改动任何原会计或资金资源。 */
    @Transactional
    public void finish(VoucherReversalCheck claimed, FinanceResult<VoucherReversalPort.Receipt> result, Instant at) {
        var source = sources.locked(claimed.input().tenantId(), claimed.input().request().command().id()); var current = current(claimed); if (current == null) return; var now = time(at);
        if (available(current, source, now)) checks.update(current.complete(result, now));
    }
    /** 外部失败不形成财务结论，新查询需由财务明确发起。 */
    @Transactional
    public void fail(VoucherReversalCheck claimed, Instant at) {
        sources.locked(claimed.input().tenantId(), claimed.input().request().command().id()); var current = current(claimed);
        if (current != null) checks.update(current.fail(VoucherReversalCheck.Issue.INTERNAL_ERROR, time(at)));
    }
    private boolean available(VoucherReversalCheck check, VoucherReversalSources.Source source, Instant now) {
        try {
            requireRecordableSource(source); requireCheck(check, source);
            personnel.requireEligible(check.input().tenantId(), check.input().requestedBy(), source.request().command().legalEntityId()); return true;
        } catch (DomainException changed) { checks.update(check.voidSource(now)); return false; }
    }
    private void requireRecordableSource(VoucherReversalSources.Source source) {
        if (records.forOperation(source.request().command().tenantId(), source.request().command().id()).isPresent()) throw new DomainException("VOUCHER_REVERSAL_ALREADY_RECORDED", "Original voucher already has an independent reversal record");
        if (source.current().status() != VoucherOperation.Status.REVERSED) throw new DomainException("VOUCHER_REVERSAL_ORIGINAL_UNRESOLVED", "Resolve the original voucher as reversed before verifying its independent reverse posting");
    }
    private void requireCheck(VoucherReversalCheck check, VoucherReversalSources.Source source) {
        if (check.input().originalVersion() != source.originalVersion() || !check.input().targetDigest().equals(source.current().input().targetDigest()) || !check.input().request().equals(source.request())) {
            throw new DomainException("VOUCHER_REVERSAL_SOURCE_CHANGED", "Original accepted posting or fixed reversal query source changed");
        }
    }
    private void requireVersions(VoucherAccess.Context context, VoucherReversalSources.Source source, long application, long business, long operation) {
        if (context.application().version() != application || context.businessVersion() != business || source.current().version() != operation) throw conflict();
    }
    private VoucherReversalCheck current(VoucherReversalCheck claimed) { return checks.find(claimed.input().tenantId(), claimed.input().id()).filter(value -> value.equals(claimed) && value.status() == VoucherReversalCheck.Status.RUNNING).orElse(null); }
    private UUID audit(VoucherReversalSources.Source source, UUID aggregateId, long version, String action, String comment, Instant at) {
        var command = source.request().command(); UUID event = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO audit_event(id,tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,application_id,action,actor_id,payload_json,occurred_at)
                VALUES(?,?,?,'VoucherReversal',?,?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), command.tenantId(), event.toString(), aggregateId.toString(), version, command.binding().applicationId().toString(), action, actors.actor().userId(),
                json.write(Map.of("operationId", command.id(), "roundNo", command.binding().roundNo(), "authorizedRole", "FINANCE", "comment", comment)), Timestamp.from(at));
        return event;
    }
    private static Instant time(Instant at) { return at.truncatedTo(ChronoUnit.MICROS); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Displayed application, business, voucher or reversal query changed"); }
    /**
     * 页面只提交看到的版本，不接受 ERP 目标、科目或金额。
     * @author owlzhangfq@gmail.com
     */
    public record QueryInput(@Positive int roundNo, @Positive long applicationVersion, @Positive long businessVersion, @Positive long operationVersion, @NotBlank @Size(max = 2000) String comment) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown voucher reversal query field"); }
    }
    /**
     * 人工登记只采纳已经保存的完整反向凭证，不能手工修改会计事实。
     * @author owlzhangfq@gmail.com
     */
    public record RecordInput(@Positive int roundNo, @Positive long applicationVersion, @Positive long businessVersion, @Positive long operationVersion,
            @NotNull UUID checkId, @Positive long checkVersion, @NotBlank @Size(max = 128) @Pattern(regexp = "[^\\p{Cntrl}]+") String evidenceReference, @NotBlank @Size(max = 2000) String comment) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown voucher reversal record field"); }
    }
    /**
     * 幂等动作回执只定位查询与登记，不包含敏感命令正文。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ActionReceipt(UUID applicationId, UUID operationId, int roundNo, UUID checkId, long checkVersion, UUID recordId, long operationVersion, UUID auditEventId) { }
}
