package io.agentflow.expense;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原报销退回的只读查询及独立登记编排，资金、资源与原命令不会被查询改写。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpensePaymentReturnService {
    private static final Duration QUERY_LEASE = Duration.ofSeconds(90);
    private final CurrentActor actors;
    private final ExpensePaymentReturnSources sources;
    private final PaymentAccess access;
    private final PaymentPersonnel personnel;
    private final JdbcExpensePaymentReturnCheckRepository checks;
    private final JdbcExpensePaymentReturnsRepository ledgers;
    private final JdbcExpensePaymentReturnRepository registrations;
    private final JdbcExpenseSettlementRepository settlements;
    private final PaymentAudit audit;
    private final ApplicationEventPublisher events;
    public ExpensePaymentReturnService(CurrentActor actors, ExpensePaymentReturnSources sources, PaymentAccess access, PaymentPersonnel personnel,
            JdbcExpensePaymentReturnCheckRepository checks, JdbcExpensePaymentReturnsRepository ledgers, JdbcExpensePaymentReturnRepository registrations,
            JdbcExpenseSettlementRepository settlements, PaymentAudit audit, ApplicationEventPublisher events) {
        this.actors = actors; this.sources = sources; this.access = access; this.personnel = personnel; this.checks = checks; this.ledgers = ledgers;
        this.registrations = registrations; this.settlements = settlements; this.audit = audit; this.events = events;
    }
    /** 幂等回放也复核当前岗位与完整原轮次字段，申请人及原出纳不能确认。 */
    public void authorize(UUID reportId) {
        var actor = actors.actor(); var source = sources.find(actor.tenantId(), reportId); var command = source.request().command();
        access.requireFinanceAuthorization(command.id());
        if (actor.userId().equals(command.payee().employeeId()) || actor.userId().equals(command.authorization().executedBy())) {
            throw new DomainException("FORBIDDEN", "Applicant and original payment executor cannot register expense returns");
        }
    }
    /** 原身份、目标与首次成功修订先保存，事务提交后才允许外部查询。 */
    @Transactional
    public ActionReceipt queue(UUID reportId, QueryInput input) {
        var actor = actors.actor(); var source = sources.locked(actor.tenantId(), reportId); authorize(reportId);
        var ledger = ledgers.find(actor.tenantId(), reportId).orElse(null);
        if (source.settlement().version() != input.settlementVersion() || (ledger == null ? 0 : ledger.version()) != input.returnVersion()) throw conflict();
        var latest = checks.latest(actor.tenantId(), reportId, actor.userId()).orElse(null);
        if (latest != null && latest.active()) throw new DomainException("EXPENSE_PAYMENT_RETURN_PENDING", "Current finance actor has a pending expense payment return query");
        var now = time(Instant.now());
        if (ledger == null) { ledger = ExpensePaymentReturns.open(source.request(), now); ledgers.create(ledger); }
        else if (!ledger.request().equals(source.request())) throw conflict();
        var check = ExpensePaymentReturnCheck.queue(new ExpensePaymentReturnCheck.Input(UUID.randomUUID(), actor.tenantId(), source.authorization().terms().targetDigest(),
                source.paymentVersion(), source.request(), actor.userId(), now));
        checks.create(check);
        var event = audit.record(source.authorization(), check.input().id(), check.version(), "FINANCE", "EXPENSE_PAYMENT_RETURN_QUERY", null, check.status().name(), input.comment(), now);
        return new ActionReceipt(reportId, check.input().id(), check.version(), null, ledger.version(), source.settlement().version(), event);
    }
    /** 消费查询、登记、防重、独立账本及结算冻结使用同一报销事务。 */
    @Transactional
    public ActionReceipt register(UUID reportId, RegisterInput input) {
        var actor = actors.actor(); var source = sources.locked(actor.tenantId(), reportId); authorize(reportId);
        var ledger = ledgers.find(actor.tenantId(), reportId).orElseThrow(ExpensePaymentReturnService::conflict);
        var check = checks.find(actor.tenantId(), input.checkId()).orElseThrow(ExpensePaymentReturnService::conflict);
        if (source.settlement().version() != input.settlementVersion() || ledger.version() != input.returnVersion() || check.version() != input.checkVersion()
                || !check.input().requestedBy().equals(actor.userId())
                || !checks.latest(actor.tenantId(), reportId, actor.userId()).map(value -> value.input().id().equals(check.input().id())).orElse(false)) throw conflict();
        sources.requireCheck(check, source); var now = time(Instant.now()); var issue = registrationIssue(source, ledger, check, now);
        if (issue != null) throw new DomainException(issue, "Expense return registration requires fresh cumulative evidence and an independent finance decision");
        if (input.outcome() != check.receipt().status()) throw new DomainException("EXPENSE_PAYMENT_RETURN_OUTCOME_CHANGED", "Registration must match the displayed expense return outcome");
        var decision = new ExpensePaymentReturn(UUID.randomUUID(), actor.tenantId(), check.input().id(), check.receipt(), actor.userId(), now, input.evidenceReference(), input.comment());
        var next = ledger.register(decision); ledgers.update(next);
        var settlement = decision.applyTo(source.settlement()); if (!settlement.equals(source.settlement())) settlements.update(settlement);
        var resolved = check.resolve(decision, now); checks.update(resolved); registrations.create(decision, next);
        events.publishEvent(new ExpensePaymentReturnRegistered(decision, next));
        var event = audit.record(source.authorization(), decision.id(), next.version(), "FINANCE", "EXPENSE_PAYMENT_RETURN_REGISTER", check.status().name(), resolved.status().name(), input.comment(), now);
        return new ActionReceipt(reportId, check.input().id(), resolved.version(), decision.id(), next.version(), settlements.find(actor.tenantId(), reportId).orElseThrow().version(), event);
    }
    /** 页面和写入口共享原件守卫，各财务看到的最高版本共同约束登记。 */
    public String registrationIssue(ExpensePaymentReturnSources.Source source, ExpensePaymentReturns ledger, ExpensePaymentReturnCheck check, Instant now) {
        if (check == null || !check.usable(now)) return "EXPENSE_PAYMENT_RETURN_EVIDENCE_UNAVAILABLE";
        if (ledger == null || !ledger.reviewRequired()) return "EXPENSE_PAYMENT_RETURN_NOT_REQUIRED";
        var receipt = check.receipt(); var payment = source.payment();
        if (!payment.settleable() && payment.status() != PaymentOperation.Status.REVERSED) return "EXPENSE_PAYMENT_RETURN_PAYMENT_UNRESOLVED";
        if (!receipt.samePaymentFacts(payment.observation()) || payment.observation().revision() > receipt.current().revision()
                || payment.observation().observedAt().isAfter(receipt.observedAt()) || evidenceChanged(check)) return "EXPENSE_PAYMENT_RETURN_EVIDENCE_CHANGED";
        try {
            var preview = new ExpensePaymentReturn(UUID.randomUUID(), check.input().tenantId(), check.input().id(), receipt, check.input().requestedBy(), now, "preview", "Check original expense return evidence");
            ledger.register(preview); preview.requireSettlement(source.settlement()); return null;
        } catch (DomainException invalid) { return invalid.code(); }
    }
    private boolean evidenceChanged(ExpensePaymentReturnCheck check) {
        var receipt = check.receipt();
        return checks.history(check.input().tenantId(), receipt.request().command().binding().businessId()).stream().anyMatch(value -> {
            var previous = value.receipt();
            return previous.revision() > receipt.revision() || previous.observedAt().isAfter(receipt.observedAt()) || !receipt.preservesReturns(previous)
                    || previous.current() != null && (previous.current().revision() > receipt.current().revision()
                        || previous.current().revision().equals(receipt.current().revision()) && !receipt.samePaymentFacts(previous.current()))
                    || previous.revision() == receipt.revision() && previous.status() != ExpensePaymentReturnPort.Status.UNRESOLVED
                        && (previous.status() != receipt.status() || !receipt.sameReturns(previous));
        });
    }
    /** 只读租约在短事务领取；过期任务明确失败，旧执行者不能覆盖新状态。 */
    @Transactional
    public ExpensePaymentReturnCheck claim(String tenant, UUID id, Instant at) {
        var initial = checks.find(tenant, id).orElse(null); if (initial == null || !initial.active()) return null;
        var source = sources.locked(tenant, initial.input().request().command().binding().businessId()); var current = checks.find(tenant, id).orElseThrow(); var now = time(at);
        if (current.expired(now)) { checks.update(current.fail(ExpensePaymentReturnCheck.Issue.TIMEOUT, now)); return null; }
        if (current.status() != ExpensePaymentReturnCheck.Status.QUEUED || !available(current, source, now)) return null;
        var claimed = current.claim(now, QUERY_LEASE); checks.update(claimed); return claimed;
    }
    /** 新入款或不一致结果先冻结；只有独立财务登记才能追加账本或解除无退回疑点。 */
    @Transactional
    public void finish(ExpensePaymentReturnCheck claimed, FinanceResult<ExpensePaymentReturnPort.Receipt> result, Instant at) {
        var input = claimed.input(); var reportId = input.request().command().binding().businessId();
        var source = sources.locked(input.tenantId(), reportId); var current = current(claimed); if (current == null) return; var now = time(at);
        if (!available(current, source, now)) return;
        var completed = current.complete(result, now); checks.update(completed); if (completed.receipt() == null) return;
        var receipt = completed.receipt(); var ledger = ledgers.find(input.tenantId(), reportId).orElseThrow(ExpensePaymentReturnService::conflict);
        var proofs = ledger.entries().stream().map(ExpensePaymentReturns.Entry::proof).toList();
        boolean same = receipt.status() != ExpensePaymentReturnPort.Status.UNRESOLVED && receipt.returns().size() == proofs.size() && receipt.returns().containsAll(proofs)
                && receipt.samePaymentFacts(source.payment().observation()) && !evidenceChanged(completed);
        if (!same) {
            var frozen = ledger.requireReview(now); if (!frozen.equals(ledger)) ledgers.update(frozen);
            var settlement = source.settlement().requireReview("EXPENSE_PAYMENT_RETURN_REVIEW_REQUIRED", now);
            if (!settlement.equals(source.settlement())) settlements.update(settlement);
        }
    }
    /** 外部异常不产生财务结论，已留存资金和冻结保持。 */
    @Transactional
    public void fail(ExpensePaymentReturnCheck claimed, Instant at) {
        sources.locked(claimed.input().tenantId(), claimed.input().request().command().binding().businessId()); var current = current(claimed);
        if (current != null) checks.update(current.fail(ExpensePaymentReturnCheck.Issue.INTERNAL_ERROR, time(at)));
    }
    private boolean available(ExpensePaymentReturnCheck check, ExpensePaymentReturnSources.Source source, Instant now) {
        try { sources.requireCheck(check, source); personnel.requireEligible(check.input().tenantId(), check.input().requestedBy(), source.request().command().payee().legalEntityId()); return true; }
        catch (DomainException changed) { checks.update(check.voidSource(now)); return false; }
    }
    private ExpensePaymentReturnCheck current(ExpensePaymentReturnCheck claimed) {
        return checks.find(claimed.input().tenantId(), claimed.input().id()).filter(value -> value.equals(claimed) && value.status() == ExpensePaymentReturnCheck.Status.RUNNING).orElse(null);
    }
    private static Instant time(Instant at) { return at.truncatedTo(ChronoUnit.MICROS); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Displayed expense settlement, return ledger or query changed"); }

    /**
     * 客户端只能确认当前结算与退回版本，原付款及科目由服务端派生。
     * @author owlzhangfq@gmail.com
     */
    public record QueryInput(@Positive long settlementVersion, @Min(0) long returnVersion, @NotBlank @Size(max = 2000) String comment) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown expense return query field"); }
    }
    /**
     * 独立财务只确认已展示原件及材料，不接收自填金额或账号。
     * @author owlzhangfq@gmail.com
     */
    public record RegisterInput(@Positive long settlementVersion, @Positive long returnVersion, @NotNull UUID checkId, @Positive long checkVersion,
                                @NotNull ExpensePaymentReturnPort.Status outcome, @NotBlank @Size(max = 128) @Pattern(regexp = "[^\\p{Cntrl}]+") String evidenceReference,
                                @NotBlank @Size(max = 2000) String comment) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown expense return registration field"); }
    }
    /**
     * 幂等动作回执不携带敏感银行或科目原件。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ActionReceipt(UUID reportId, UUID checkId, long checkVersion, UUID registrationId, long returnVersion, long settlementVersion, UUID auditEventId) { }
}
