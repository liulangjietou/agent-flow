package io.agentflow.expense;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.AdvanceDisbursementReturnPort;
import io.agentflow.finance.FinanceResult;
import io.agentflow.finance.PaymentAccess;
import io.agentflow.finance.PaymentAudit;
import io.agentflow.finance.PaymentOperation;
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
 * 查询只冻结不一致来源，独立财务确认才追加银行退回并减少原借款欠款。
 * @author owlzhangfq@gmail.com
 */
@Service
public class AdvanceDisbursementReturnService {
    private static final Duration QUERY_LEASE = Duration.ofSeconds(90);
    private final CurrentActor actors;
    private final AdvanceDisbursementReturnSources sources;
    private final PaymentAccess access;
    private final PaymentPersonnel personnel;
    private final JdbcDisbursementReturnCheckRepository checks;
    private final JdbcDisbursementResolutionRepository decisions;
    private final EmployeeAdvanceRepository balances;
    private final PaymentAudit audit;
    private final ApplicationEventPublisher events;
    /** 借款状态归领域，权限、短事务和审计归应用编排。 */
    public AdvanceDisbursementReturnService(CurrentActor actors, AdvanceDisbursementReturnSources sources, PaymentAccess access, PaymentPersonnel personnel,
            JdbcDisbursementReturnCheckRepository checks, JdbcDisbursementResolutionRepository decisions, EmployeeAdvanceRepository balances, PaymentAudit audit, ApplicationEventPublisher events) {
        this.actors = actors; this.sources = sources; this.access = access; this.personnel = personnel;
        this.checks = checks; this.decisions = decisions; this.balances = balances; this.audit = audit; this.events = events;
    }
    /** 幂等回放也要核验当前字段、任职和职责，原出纳不能改为财务裁决自己执行的付款。 */
    public void authorize(UUID advanceId) {
        var actor = actors.actor(); var source = sources.find(actor.tenantId(), advanceId);
        access.requireFinanceAuthorization(source.funding().authorization().terms().id());
        if (actor.userId().equals(source.request().command().authorization().executedBy())) throw new DomainException("FORBIDDEN", "Original payment executor cannot resolve its disbursement return");
    }
    /** 原放款与查询目标先持久化，出纳和页面不能替换查询身份。 */
    @Transactional
    public ActionReceipt queue(UUID advanceId, QueryInput input) {
        var actor = actors.actor(); var source = sources.locked(actor.tenantId(), advanceId); authorize(advanceId); var funding = source.funding();
        if (funding.advance().version() != input.advanceVersion()) throw conflict();
        var latest = checks.latest(actor.tenantId(), advanceId, actor.userId()).orElse(null);
        if (latest != null && latest.active()) throw new DomainException("DISBURSEMENT_RETURN_PENDING", "The current finance actor has a pending disbursement query");
        var now = time(Instant.now()); var check = AdvanceDisbursementReturnCheck.queue(new AdvanceDisbursementReturnCheck.Input(UUID.randomUUID(), actor.tenantId(),
                funding.authorization().terms().targetDigest(), source.paymentVersion(), source.request(), actor.userId(), now));
        checks.create(check);
        var event = audit.record(funding.authorization(), check.input().id(), check.version(), "FINANCE", "DISBURSEMENT_RETURN_QUERY", null, check.status().name(), input.comment(), now);
        return new ActionReceipt(advanceId, check.input().id(), check.version(), null, funding.advance().version(), event);
    }
    /** 借款、查询消费、决定、共用防重及审计同时提交，任一步失败全部回滚。 */
    @Transactional
    public ActionReceipt resolve(UUID advanceId, ResolveInput input) {
        var actor = actors.actor(); var source = sources.locked(actor.tenantId(), advanceId); authorize(advanceId); var funding = source.funding();
        var check = checks.find(actor.tenantId(), input.checkId()).orElseThrow(AdvanceDisbursementReturnService::conflict);
        if (funding.advance().version() != input.advanceVersion() || check.version() != input.checkVersion() || !check.input().requestedBy().equals(actor.userId())
                || !checks.latest(actor.tenantId(), advanceId, actor.userId()).map(value -> value.input().id().equals(check.input().id())).orElse(false)) throw conflict();
        requireCheck(check, source); var now = time(Instant.now()); var issue = confirmationIssue(source, check, now);
        if (issue != null) throw new DomainException(issue, "Disbursement return requires current original evidence, independent review and sufficient unallocated debt");
        if (input.outcome() != check.receipt().status()) throw new DomainException("DISBURSEMENT_RETURN_OUTCOME_CHANGED", "Decision must match the displayed disbursement outcome");
        var decision = new AdvanceDisbursementReturn(UUID.randomUUID(), actor.tenantId(), check.input().id(), check.receipt(), actor.userId(), now, input.evidenceReference(), input.comment());
        var advance = funding.advance(); long version = advance.version(); advance.resolveDisbursementReview(version, decision);
        balances.update(advance, version, actor.userId(), "DISBURSEMENT_RETURN_RESOLVED"); var resolved = check.resolve(decision, now); checks.update(resolved); decisions.create(decision, advance);
        events.publishEvent(new AdvanceDisbursementReturnChanged(resolved));
        var event = audit.record(funding.authorization(), decision.id(), advance.version(), "FINANCE", "DISBURSEMENT_RETURN_RESOLVE", check.status().name(), resolved.status().name(), input.comment(), now);
        return new ActionReceipt(advanceId, check.input().id(), resolved.version(), decision.id(), advance.version(), event);
    }
    /** 展示与写入共享守卫，原付款未裁决或已有预留不能被本入口绕过。 */
    public String confirmationIssue(AdvanceDisbursementReturnSources.Source source, AdvanceDisbursementReturnCheck check, Instant now) {
        if (check == null || !check.usable(now)) return "DISBURSEMENT_RETURN_EVIDENCE_UNAVAILABLE";
        var funding = source.funding(); var advance = funding.advance(); var payment = funding.payment(); var receipt = check.receipt();
        if (!advance.paymentReviewRequired()) return "DISBURSEMENT_RETURN_NOT_REQUIRED";
        if (!payment.settleable() && payment.status() != PaymentOperation.Status.REVERSED) return "DISBURSEMENT_RETURN_PAYMENT_UNRESOLVED";
        if (!receipt.samePaymentFacts(payment.observation()) || payment.observation().revision() > receipt.current().revision()
                || payment.observation().observedAt().isAfter(receipt.observedAt()) || evidenceChanged(check)) return "DISBURSEMENT_RETURN_EVIDENCE_CHANGED";
        try {
            var preview = EmployeeAdvance.restore(advance.state());
            preview.resolveDisbursementReview(preview.version(), new AdvanceDisbursementReturn(UUID.randomUUID(), check.input().tenantId(), check.input().id(), receipt,
                    check.input().requestedBy(), now, "preview", "Check current balance capacity"));
            return null;
        } catch (DomainException invalid) { return invalid.code(); }
    }
    private boolean evidenceChanged(AdvanceDisbursementReturnCheck check) {
        var receipt = check.receipt();
        return checks.history(check.input().tenantId(), receipt.request().command().binding().businessId()).stream().anyMatch(value -> {
            var previous = value.receipt();
            return previous.revision() > receipt.revision() || previous.observedAt().isAfter(receipt.observedAt())
                    || previous.current() != null && previous.current().revision() > receipt.current().revision()
                    || !receipt.preservesReturns(previous)
                    || previous.revision() == receipt.revision() && previous.status() != AdvanceDisbursementReturnPort.Status.UNRESOLVED
                        && (previous.status() != receipt.status() || !receipt.sameReturns(previous));
        });
    }
    /** 领取后释放事务；停机残留租约明确超时，旧执行者不能覆盖新版本。 */
    @Transactional
    public AdvanceDisbursementReturnCheck claim(String tenant, UUID id, Instant at) {
        var initial = checks.find(tenant, id).orElse(null); if (initial == null || !initial.active()) return null;
        var source = sources.locked(tenant, initial.input().request().command().binding().businessId()); var current = checks.find(tenant, id).orElseThrow(); var now = time(at);
        if (current.expired(now)) { persistCheck(current.fail(AdvanceDisbursementReturnCheck.Issue.TIMEOUT, now)); return null; }
        if (current.status() != AdvanceDisbursementReturnCheck.Status.QUEUED || !available(current, source, now)) return null;
        var claimed = current.claim(now, QUERY_LEASE); persistCheck(claimed); return claimed;
    }
    /** 新银行退回或原件变化只冻结后续使用，查询结果不会自行减掉债务。 */
    @Transactional
    public void finish(AdvanceDisbursementReturnCheck claimed, FinanceResult<AdvanceDisbursementReturnPort.Receipt> result, Instant at) {
        var source = sources.locked(claimed.input().tenantId(), claimed.input().request().command().binding().businessId()); var current = current(claimed); if (current == null) return; var now = time(at);
        if (!available(current, source, now)) return;
        var completed = current.complete(result, now); persistCheck(completed); if (completed.receipt() == null) return;
        var receipt = completed.receipt(); var advance = source.funding().advance(); var previous = decisions.latest(advance.tenantId(), advance.id()).orElse(null);
        boolean same = receipt.status() != AdvanceDisbursementReturnPort.Status.UNRESOLVED
                && (previous == null ? receipt.status() == AdvanceDisbursementReturnPort.Status.CONFIRMED : receipt.sameReturns(previous.receipt()))
                && receipt.samePaymentFacts(source.funding().payment().observation()) && !evidenceChanged(completed);
        if (!same && !advance.paymentReviewRequired()) {
            long version = advance.version(); advance.requirePaymentReview(version); balances.update(advance, version, "disbursement-return-query", "PAYMENT_REVIEW");
        }
    }
    /** 外部异常不生成回款结论，操作者可以明确建立新的查询。 */
    @Transactional
    public void fail(AdvanceDisbursementReturnCheck claimed, Instant at) {
        sources.locked(claimed.input().tenantId(), claimed.input().request().command().binding().businessId()); var current = current(claimed);
        if (current != null) persistCheck(current.fail(AdvanceDisbursementReturnCheck.Issue.INTERNAL_ERROR, time(at)));
    }
    private boolean available(AdvanceDisbursementReturnCheck check, AdvanceDisbursementReturnSources.Source source, Instant now) {
        try { requireCheck(check, source); personnel.requireEligible(check.input().tenantId(), check.input().requestedBy(), source.funding().advance().legalEntityId()); return true; }
        catch (DomainException changed) { persistCheck(check.voidSource(now)); return false; }
    }
    private void requireCheck(AdvanceDisbursementReturnCheck check, AdvanceDisbursementReturnSources.Source source) {
        if (!check.input().tenantId().equals(source.funding().advance().tenantId()) || check.input().paymentVersion() != source.paymentVersion()
                || !check.input().targetDigest().equals(source.funding().authorization().terms().targetDigest()) || !check.input().request().equals(source.request())) {
            throw new DomainException("DISBURSEMENT_RETURN_SOURCE_CHANGED", "Original disbursement and fixed query source changed");
        }
    }
    private AdvanceDisbursementReturnCheck current(AdvanceDisbursementReturnCheck claimed) { return checks.find(claimed.input().tenantId(), claimed.input().id()).filter(value -> value.equals(claimed) && value.status() == AdvanceDisbursementReturnCheck.Status.RUNNING).orElse(null); }
    private void persistCheck(AdvanceDisbursementReturnCheck value) {
        checks.update(value); events.publishEvent(new AdvanceDisbursementReturnChanged(value));
    }
    private static Instant time(Instant at) { return at.truncatedTo(ChronoUnit.MICROS); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Displayed advance or disbursement query changed"); }
    /**
     * 查询不接受外部目标、员工、账号或金额。
     * @author owlzhangfq@gmail.com
     */
    public record QueryInput(@Positive long advanceVersion, @NotBlank @Size(max = 2000) String comment) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown disbursement query field"); }
    }
    /**
     * 人工只确认展示结论及材料，债务变化严格来自已保存的证据。
     * @author owlzhangfq@gmail.com
     */
    public record ResolveInput(@Positive long advanceVersion, @NotNull UUID checkId, @Positive long checkVersion,
            @NotNull AdvanceDisbursementReturnPort.Status outcome, @NotBlank @Size(max = 128) @Pattern(regexp = "[^\\p{Cntrl}]+") String evidenceReference,
            @NotBlank @Size(max = 2000) String comment) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown disbursement resolution field"); }
    }
    /**
     * 可恢复动作回执不包含敏感原件或账户资料。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ActionReceipt(UUID advanceId, UUID checkId, long checkVersion, UUID resolutionId, long advanceVersion, UUID auditEventId) { }
}
