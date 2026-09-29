package io.agentflow.expense;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.AdvanceRepaymentPort;
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
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 还款查询、人工确认与余额写入分别使用短事务，外部资金读取不进入数据库事务。
 * @author owlzhangfq@gmail.com
 */
@Service
public class AdvanceRepaymentService {
    private static final Duration QUERY_LEASE = Duration.ofSeconds(90);
    private final CurrentActor actors;
    private final AdvanceRepaymentSources sources;
    private final PaymentAccess access;
    private final PaymentPersonnel personnel;
    private final JdbcAdvanceRepaymentCheckRepository checks;
    private final JdbcAdvanceRepaymentRepository repayments;
    private final EmployeeAdvanceRepository balances;
    private final PaymentAudit audit;
    /** 原放款、独立财务与不可变收款事实共同约束确认；不提供手工金额入口。 */
    public AdvanceRepaymentService(CurrentActor actors, AdvanceRepaymentSources sources, PaymentAccess access, PaymentPersonnel personnel,
            JdbcAdvanceRepaymentCheckRepository checks, JdbcAdvanceRepaymentRepository repayments, EmployeeAdvanceRepository balances, PaymentAudit audit) {
        this.actors = actors; this.sources = sources; this.access = access; this.personnel = personnel; this.checks = checks; this.repayments = repayments; this.balances = balances; this.audit = audit;
    }
    /** 幂等回放前复核当前原轮次完整字段、财务身份及法人任职。 */
    public void authorize(UUID advanceId) {
        var source = sources.find(actors.actor().tenantId(), advanceId); access.requireFinanceAuthorization(source.authorization().terms().id());
    }
    /** 财务明确查询只登记意图，原收款编号不能附带替换金额或员工。 */
    @Transactional
    public ActionReceipt queue(UUID advanceId, QueryInput input) {
        var actor = actors.actor(); var source = sources.locked(actor.tenantId(), advanceId); authorize(advanceId); source.requireConfirmedPayment();
        if (source.advance().version() != input.advanceVersion()) throw conflict();
        var latest = checks.latest(actor.tenantId(), advanceId, actor.userId()).orElse(null);
        if (latest != null && latest.active()) throw new DomainException("ADVANCE_REPAYMENT_CHECK_PENDING", "The current finance actor already has a pending repayment check");
        var now = now(); var payment = source.payment();
        var check = AdvanceRepaymentCheck.queue(new AdvanceRepaymentCheck.Input(UUID.randomUUID(), actor.tenantId(), source.authorization().terms().id(), payment.version(),
                source.authorization().terms().targetDigest(), source.request(input.receiptReference().trim()), actor.userId(), now));
        checks.create(check);
        var event = audit.record(source.authorization(), check.input().id(), check.version(), "FINANCE", "ADVANCE_REPAYMENT_QUERY", null, check.status().name(), input.comment(), now);
        return new ActionReceipt(advanceId, check.input().id(), check.version(), null, source.advance().version(), event);
    }
    /** 当前展示余额与查询版本同时一致才确认；凭据、余额、任务消费和审计原子提交。 */
    @Transactional
    public ActionReceipt record(UUID advanceId, RecordInput input) {
        var actor = actors.actor(); var source = sources.locked(actor.tenantId(), advanceId); authorize(advanceId); source.requireConfirmedPayment();
        var check = checks.find(actor.tenantId(), input.checkId()).orElseThrow(AdvanceRepaymentService::conflict);
        if (source.advance().version() != input.advanceVersion() || check.version() != input.checkVersion()
                || !check.input().requestedBy().equals(actor.userId()) || !check.input().request().advanceId().equals(advanceId)
                || !checks.latest(actor.tenantId(), advanceId, actor.userId()).map(value -> value.input().id().equals(check.input().id())).orElse(false)) throw conflict();
        sources.requireCheck(check, source); var now = now(); String issue = confirmationIssue(source, check, now);
        if (issue != null) throw new DomainException(issue, "Repayment confirmation requires fresh original receipt and unreserved balance");
        var repayment = new AdvanceRepayment(UUID.randomUUID(), actor.tenantId(), check.input().id(), check.receipt(), actor.userId(), now, input.comment());
        var advance = source.advance(); long version = advance.version(); advance.repay(version, repayment);
        balances.update(advance, version, actor.userId(), "REPAYMENT_RECORDED"); var recorded = check.record(repayment, now); checks.update(recorded); repayments.create(repayment, advance);
        var event = audit.record(source.authorization(), repayment.id(), advance.version(), "FINANCE", "ADVANCE_REPAYMENT_RECORD", check.status().name(), recorded.status().name(), input.comment(), now);
        return new ActionReceipt(advanceId, check.input().id(), recorded.version(), repayment.id(), advance.version(), event);
    }
    /** 页面与确认共用守卫，另一位财务的新观测能使旧页面立即失效。 */
    public String confirmationIssue(AdvanceRepaymentSources.Source source, AdvanceRepaymentCheck check, Instant now) {
        if (!source.payment().settleable() || source.advance().paymentReviewRequired()) return "ADVANCE_PAYMENT_REVIEW_REQUIRED";
        if (source.advance().repaymentReviewRequired()) return "ADVANCE_REPAYMENT_REVIEW_REQUIRED";
        if (check == null || !check.usable(now)) return "ADVANCE_REPAYMENT_EVIDENCE_UNAVAILABLE";
        var receipt = check.receipt(); var request = check.input().request();
        if (repayments.forReceipt(check.input().tenantId(), request.legalEntityId(), request.receiptReference()).isPresent()) return "ADVANCE_REPAYMENT_ALREADY_RECORDED";
        var history = checks.receiptHistory(check.input().tenantId(), request.advanceId(), request.receiptReference());
        if (history.isEmpty() || !history.get(0).input().id().equals(check.input().id()) || history.stream().anyMatch(value -> value.receipt().revision() > receipt.revision()
                || value.receipt().funding() != null && !receipt.sameSettlement(value.receipt()))) return "ADVANCE_REPAYMENT_EVIDENCE_CHANGED";
        if (receipt.funding().receivedAt().isBefore(source.payment().observation().completedAt())) return "ADVANCE_REPAYMENT_BEFORE_DISBURSEMENT";
        if (receipt.funding().amount().compareTo(source.advance().available()) > 0) return "INSUFFICIENT_FINANCIAL_BALANCE";
        return null;
    }
    /** 领取后释放事务；租约超时不会复用迟到结果。 */
    @Transactional
    public AdvanceRepaymentCheck claim(String tenant, UUID id, Instant at) {
        var initial = checks.find(tenant, id).orElse(null); if (initial == null || !initial.active()) return null;
        var source = sources.locked(tenant, initial.input().request().advanceId()); var current = checks.find(tenant, id).orElseThrow(); var now = time(at);
        if (current.expired(now)) { checks.update(current.fail(AdvanceRepaymentCheck.Issue.TIMEOUT, now)); return null; }
        if (current.status() != AdvanceRepaymentCheck.Status.QUEUED || !available(current, source, now)) return null;
        var claimed = current.claim(now, QUERY_LEASE); checks.update(claimed); return claimed;
    }
    /** 新凭据只进入待确认；已入账原凭据撤销或矛盾则保留原账并单独冻结。 */
    @Transactional
    public void finish(AdvanceRepaymentCheck claimed, FinanceResult<AdvanceRepaymentPort.Receipt> result, Instant at) {
        var source = sources.locked(claimed.input().tenantId(), claimed.input().request().advanceId()); var current = current(claimed); if (current == null) return; var now = time(at);
        if (!available(current, source, now)) return;
        var completed = current.complete(result, now); checks.update(completed);
        if (completed.receipt() == null) return;
        var request = completed.input().request(); var original = repayments.forReceipt(completed.input().tenantId(), request.legalEntityId(), request.receiptReference()).orElse(null);
        if (original == null || !original.receipt().request().advanceId().equals(source.advance().id())) return;
        var receipt = completed.receipt();
        if (receipt.status() != AdvanceRepaymentPort.Status.CONFIRMED || receipt.revision() < original.receipt().revision() || !receipt.sameSettlement(original.receipt())) {
            var advance = source.advance();
            if (!advance.repaymentReviewRequired()) { long version = advance.version(); advance.requireRepaymentReview(version); balances.update(advance, version, "repayment-reconciliation", "REPAYMENT_REVIEW"); }
        }
    }
    /** 非业务异常只记录不可用，不创造未收款结论。 */
    @Transactional
    public void fail(AdvanceRepaymentCheck claimed, Instant at) {
        sources.locked(claimed.input().tenantId(), claimed.input().request().advanceId()); var current = current(claimed);
        if (current != null) checks.update(current.fail(AdvanceRepaymentCheck.Issue.INTERNAL_ERROR, time(at)));
    }
    private boolean available(AdvanceRepaymentCheck check, AdvanceRepaymentSources.Source source, Instant now) {
        try { sources.requireCheck(check, source); personnel.requireEligible(check.input().tenantId(), check.input().requestedBy(), check.input().request().legalEntityId()); return true; }
        catch (DomainException changed) { checks.update(check.voidSource(now)); return false; }
    }
    private AdvanceRepaymentCheck current(AdvanceRepaymentCheck claimed) { return checks.find(claimed.input().tenantId(), claimed.input().id()).filter(value -> value.equals(claimed) && value.status() == AdvanceRepaymentCheck.Status.RUNNING).orElse(null); }
    private static Instant now() { return time(Instant.now()); }
    private static Instant time(Instant at) { return at.truncatedTo(ChronoUnit.MICROS); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Displayed advance balance or repayment check changed"); }
    /**
     * 请求不接收员工、金额、账号或收款结论。
     * @author owlzhangfq@gmail.com
     */
    public record QueryInput(@Positive long advanceVersion, @NotBlank @Size(max = 128) @Pattern(regexp = "[^\\p{Cntrl}]+") String receiptReference,
                             @NotBlank @Size(max = 2000) String comment) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown repayment query field"); }
    }
    /**
     * 确认只引用后端显示的版本，不重传外部事实。
     * @author owlzhangfq@gmail.com
     */
    public record RecordInput(@Positive long advanceVersion, @NotNull UUID checkId, @Positive long checkVersion, @NotBlank @Size(max = 2000) String comment) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown repayment confirmation field"); }
    }
    /**
     * 最小动作回执可恢复原请求，不复制完整收款材料。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ActionReceipt(UUID advanceId, UUID checkId, long checkVersion, UUID repaymentId, long advanceVersion, UUID auditEventId) { }
}
