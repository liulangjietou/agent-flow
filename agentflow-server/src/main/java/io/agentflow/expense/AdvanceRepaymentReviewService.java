package io.agentflow.expense;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.AdvanceRepaymentAdjustmentPort;
import io.agentflow.finance.FinanceResult;
import io.agentflow.finance.PaymentAccess;
import io.agentflow.finance.PaymentAudit;
import io.agentflow.finance.PaymentPersonnel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 原还款复核只读取外部事实，明确裁决时才原子追加退回并解除对应冻结。
 * @author owlzhangfq@gmail.com
 */
@Service
public class AdvanceRepaymentReviewService {
    private static final Duration QUERY_LEASE = Duration.ofSeconds(90);
    private final CurrentActor actors;
    private final AdvanceRepaymentSources sources;
    private final PaymentAccess access;
    private final PaymentPersonnel personnel;
    private final JdbcAdvanceRepaymentRepository repayments;
    private final JdbcAdvanceRepaymentCheckRepository originalChecks;
    private final JdbcRepaymentReviewCheckRepository checks;
    private final JdbcRepaymentResolutionRepository decisions;
    private final EmployeeAdvanceRepository balances;
    private final PaymentAudit audit;
    private final ApplicationEventPublisher events;

    /** 独立财务、原收款、固定外部目标与借款余额共同约束复核。 */
    public AdvanceRepaymentReviewService(CurrentActor actors, AdvanceRepaymentSources sources, PaymentAccess access, PaymentPersonnel personnel,
            JdbcAdvanceRepaymentRepository repayments, JdbcAdvanceRepaymentCheckRepository originalChecks, JdbcRepaymentReviewCheckRepository checks,
            JdbcRepaymentResolutionRepository decisions, EmployeeAdvanceRepository balances, PaymentAudit audit, ApplicationEventPublisher events) {
        this.actors = actors; this.sources = sources; this.access = access; this.personnel = personnel; this.repayments = repayments;
        this.originalChecks = originalChecks; this.checks = checks; this.decisions = decisions; this.balances = balances; this.audit = audit; this.events = events;
    }
    /** 幂等回放前重新验证当轮完整字段与当前法人任职，原件不能跨借款访问。 */
    public void authorize(UUID advanceId, UUID repaymentId) {
        var source = sources.find(actors.actor().tenantId(), advanceId); access.requireFinanceAuthorization(source.authorization().terms().id()); original(source, repaymentId);
    }
    /** 复核原还款可以与原放款争议并存，不能借此解除原放款的冻结。 */
    @Transactional
    public ActionReceipt queue(UUID advanceId, UUID repaymentId, QueryInput input) {
        var actor = actors.actor(); var source = sources.locked(actor.tenantId(), advanceId); authorize(advanceId, repaymentId);
        if (source.advance().version() != input.advanceVersion()) throw conflict();
        var latest = checks.latest(actor.tenantId(), repaymentId, actor.userId()).orElse(null);
        if (latest != null && latest.active()) throw new DomainException("REPAYMENT_REVIEW_PENDING", "The current finance actor already has a pending repayment review");
        var now = time(Instant.now()); var check = AdvanceRepaymentReviewCheck.queue(new AdvanceRepaymentReviewCheck.Input(UUID.randomUUID(), actor.tenantId(),
                source.authorization().terms().targetDigest(), request(source, repaymentId), actor.userId(), now));
        checks.create(check);
        var event = audit.record(source.authorization(), check.input().id(), check.version(), "FINANCE", "ADVANCE_REPAYMENT_REVIEW_QUERY", null, check.status().name(), input.comment(), now);
        return new ActionReceipt(advanceId, repaymentId, check.input().id(), check.version(), null, source.advance().version(), event);
    }
    /** 精确展示版本、最新外部证据和独立财务决定同时成立才调整，任一步失败全部回滚。 */
    @Transactional
    public ActionReceipt resolve(UUID advanceId, UUID repaymentId, ResolveInput input) {
        var actor = actors.actor(); var source = sources.locked(actor.tenantId(), advanceId); authorize(advanceId, repaymentId);
        var check = checks.find(actor.tenantId(), input.checkId()).orElseThrow(AdvanceRepaymentReviewService::conflict);
        if (source.advance().version() != input.advanceVersion() || check.version() != input.checkVersion() || !check.input().requestedBy().equals(actor.userId())
                || !check.input().request().repaymentId().equals(repaymentId)
                || !checks.latest(actor.tenantId(), repaymentId, actor.userId()).map(value -> value.input().id().equals(check.input().id())).orElse(false)) throw conflict();
        requireCheck(check, source); var now = time(Instant.now()); var issue = confirmationIssue(source, check, now);
        if (issue != null) throw new DomainException(issue, "Repayment resolution requires fresh consistent original evidence and an independent decision");
        if (input.outcome() != check.receipt().status()) throw new DomainException("REPAYMENT_REVIEW_OUTCOME_CHANGED", "Decision must match the displayed external outcome");
        var decision = new AdvanceRepaymentResolution(UUID.randomUUID(), actor.tenantId(), check.input().id(), check.receipt(), actor.userId(), now, input.evidenceReference(), input.comment());
        var advance = source.advance(); long version = advance.version(); advance.resolveRepaymentReview(version, decision);
        balances.update(advance, version, actor.userId(), "REPAYMENT_REVIEW_RESOLVED"); var resolved = check.resolve(decision, now); checks.update(resolved); decisions.create(decision, advance);
        events.publishEvent(new AdvanceRepaymentReviewChanged(resolved));
        var event = audit.record(source.authorization(), decision.id(), advance.version(), "FINANCE", "ADVANCE_REPAYMENT_REVIEW_RESOLVE", check.status().name(), resolved.status().name(), input.comment(), now);
        return new ActionReceipt(advanceId, repaymentId, check.input().id(), resolved.version(), decision.id(), advance.version(), event);
    }
    /** 页面与写入共享守卫，其他财务的新观察会使旧决定失效。 */
    public String confirmationIssue(AdvanceRepaymentSources.Source source, AdvanceRepaymentReviewCheck check, Instant now) {
        if (check == null || !check.usable(now)) return "REPAYMENT_REVIEW_EVIDENCE_UNAVAILABLE";
        var receipt = check.receipt(); var request = receipt.request(); var tenant = check.input().tenantId();
        if (!source.advance().repaymentReviews().contains(request.repaymentId())) return "REPAYMENT_REVIEW_NOT_REQUIRED";
        if (evidenceChanged(check)) return "REPAYMENT_REVIEW_EVIDENCE_CHANGED";
        var returned = decisions.returned(tenant, request.repaymentId()).orElse(null);
        if (returned != null && !receipt.preservesReturns(returned.receipt())) return "REPAYMENT_REVIEW_RETURN_CHANGED";
        return null;
    }
    private boolean evidenceChanged(AdvanceRepaymentReviewCheck check) {
        var receipt = check.receipt(); var request = receipt.request(); var original = request.original().request(); var tenant = check.input().tenantId();
        var history = checks.history(tenant, request.repaymentId());
        if (history.stream().anyMatch(value -> value.receipt().revision() > receipt.revision() || value.receipt().observedAt().isAfter(receipt.observedAt())
                || value.receipt().current() != null && value.receipt().current().revision() > receipt.current().revision()
                || !value.receipt().returns().isEmpty() && !receipt.preservesReturns(value.receipt())
                || value.receipt().revision() == receipt.revision() && value.receipt().status() != AdvanceRepaymentAdjustmentPort.Status.UNRESOLVED
                    && (value.receipt().status() != receipt.status() || !value.receipt().returns().isEmpty() && !receipt.sameReturn(value.receipt())))) return true;
        return originalChecks.receiptHistory(tenant, original.advanceId(), original.receiptReference()).stream().anyMatch(value ->
                value.receipt().revision() > receipt.current().revision() || value.receipt().observedAt().isAfter(receipt.observedAt()));
    }
    /** 每次领取后释放原申请锁，慢查询不阻塞其他本地业务事务。 */
    @Transactional
    public AdvanceRepaymentReviewCheck claim(String tenant, UUID id, Instant at) {
        var initial = checks.find(tenant, id).orElse(null); if (initial == null || !initial.active()) return null;
        var source = sources.locked(tenant, initial.input().request().original().request().advanceId()); var current = checks.find(tenant, id).orElseThrow(); var now = time(at);
        if (current.expired(now)) { persistCheck(current.fail(AdvanceRepaymentReviewCheck.Issue.TIMEOUT, now)); return null; }
        if (current.status() != AdvanceRepaymentReviewCheck.Status.QUEUED || !available(current, source, now)) return null;
        var claimed = current.claim(now, QUERY_LEASE); persistCheck(claimed); return claimed;
    }
    /** 新退回或未核清来源只冻结该还款，完整证据仍需人工确认才影响欠款。 */
    @Transactional
    public void finish(AdvanceRepaymentReviewCheck claimed, FinanceResult<AdvanceRepaymentAdjustmentPort.Receipt> result, Instant at) {
        var source = sources.locked(claimed.input().tenantId(), claimed.input().request().original().request().advanceId()); var current = current(claimed); if (current == null) return; var now = time(at);
        if (!available(current, source, now)) return;
        var completed = current.complete(result, now); if (completed.receipt() == null) { persistCheck(completed); return; }
        var receipt = completed.receipt(); var repaymentId = receipt.request().repaymentId(); var returned = decisions.returned(completed.input().tenantId(), repaymentId).orElse(null);
        boolean consistent = (returned == null ? receipt.status() == AdvanceRepaymentAdjustmentPort.Status.CONFIRMED : receipt.sameReturn(returned.receipt())) && !evidenceChanged(completed);
        completed = completed.withReviewRequirement(!consistent);
        var advance = source.advance();
        if (!consistent && !advance.repaymentReviews().contains(repaymentId)) {
            long version = advance.version(); advance.requireRepaymentReview(version, repaymentId); balances.update(advance, version, "repayment-reconciliation", "REPAYMENT_REVIEW");
        }
        persistCheck(completed);
    }
    /** 传输异常不虚构资金结论，也不撤销已经确认的财务记录。 */
    @Transactional
    public void fail(AdvanceRepaymentReviewCheck claimed, Instant at) {
        sources.locked(claimed.input().tenantId(), claimed.input().request().original().request().advanceId()); var current = current(claimed);
        if (current != null) persistCheck(current.fail(AdvanceRepaymentReviewCheck.Issue.INTERNAL_ERROR, time(at)));
    }
    private boolean available(AdvanceRepaymentReviewCheck check, AdvanceRepaymentSources.Source source, Instant now) {
        try { requireCheck(check, source); personnel.requireEligible(check.input().tenantId(), check.input().requestedBy(), source.advance().legalEntityId()); return true; }
        catch (DomainException changed) { persistCheck(check.voidSource(now)); return false; }
    }
    private void requireCheck(AdvanceRepaymentReviewCheck check, AdvanceRepaymentSources.Source source) {
        if (!check.input().tenantId().equals(source.advance().tenantId()) || !check.input().targetDigest().equals(source.authorization().terms().targetDigest())
                || !check.input().request().equals(request(source, check.input().request().repaymentId()))) throw changed();
    }
    private AdvanceRepayment original(AdvanceRepaymentSources.Source source, UUID repaymentId) {
        var value = repayments.find(source.advance().tenantId(), repaymentId).orElseThrow(AdvanceRepaymentReviewService::changed);
        if (!value.belongsTo(source.advance())) throw changed(); return value;
    }
    private AdvanceRepaymentAdjustmentPort.Request request(AdvanceRepaymentSources.Source source, UUID repaymentId) { return new AdvanceRepaymentAdjustmentPort.Request(repaymentId, original(source, repaymentId).receipt()); }
    private AdvanceRepaymentReviewCheck current(AdvanceRepaymentReviewCheck claimed) { return checks.find(claimed.input().tenantId(), claimed.input().id()).filter(value -> value.equals(claimed) && value.status() == AdvanceRepaymentReviewCheck.Status.RUNNING).orElse(null); }
    private static Instant time(Instant at) { return at.truncatedTo(ChronoUnit.MICROS); }
    private void persistCheck(AdvanceRepaymentReviewCheck value) {
        checks.update(value); events.publishEvent(new AdvanceRepaymentReviewChanged(value));
    }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Displayed advance balance or repayment review changed"); }
    private static DomainException changed() { return new DomainException("ADVANCE_REPAYMENT_SOURCE_CHANGED", "Original repayment is unavailable in the current advance scope"); }
    /**
     * 页面只提交当前余额版本，原还款身份与金额来自已入账原件。
     * @author owlzhangfq@gmail.com
     */
    public record QueryInput(@Positive long advanceVersion, @NotBlank @Size(max = 2000) String comment) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown repayment review query field"); }
    }
    /**
     * 裁决绑定精确展示版本及核验材料编号，不允许手工填写退款金额。
     * @author owlzhangfq@gmail.com
     */
    public record ResolveInput(@Positive long advanceVersion, @NotNull UUID checkId, @Positive long checkVersion,
            @NotNull AdvanceRepaymentAdjustmentPort.Status outcome, @NotBlank @Size(max = 128) @Pattern(regexp = "[^\\p{Cntrl}]+") String evidenceReference,
            @NotBlank @Size(max = 2000) String comment) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown repayment resolution field"); }
    }
    /**
     * 可恢复的最小动作回执，读取原件仍须通过当前权限校验。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ActionReceipt(UUID advanceId, UUID repaymentId, UUID checkId, long checkVersion, UUID resolutionId, long advanceVersion, UUID auditEventId) { }
}
