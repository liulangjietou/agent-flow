package io.agentflow.procurement;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.FinanceResult;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原付款只读查询、独立资金登记与原应付冻结编排，银行资金不自动改写 ERP。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierPaymentReturnService {
    private static final Duration QUERY_LEASE = Duration.ofSeconds(90);
    private final CurrentActor actors;
    private final SupplierSettlementAccess access;
    private final SupplierPaymentReturnSources sources;
    private final JdbcSupplierPaymentReturnCheckRepository checks;
    private final JdbcSupplierPaymentReturnsRepository ledgers;
    private final JdbcSupplierPaymentReturnRepository registrations;
    private final JdbcTemplate jdbc;
    private final JsonUtil json;

    /** 网络读取留给事务外工作器，财务决定与审计在原申请事务内保存。 */
    public SupplierPaymentReturnService(CurrentActor actors, SupplierSettlementAccess access, SupplierPaymentReturnSources sources,
            JdbcSupplierPaymentReturnCheckRepository checks, JdbcSupplierPaymentReturnsRepository ledgers,
            JdbcSupplierPaymentReturnRepository registrations, JdbcTemplate jdbc, JsonUtil json) {
        this.actors = actors; this.access = access; this.sources = sources; this.checks = checks; this.ledgers = ledgers;
        this.registrations = registrations; this.jdbc = jdbc; this.json = json;
    }

    /** 幂等回放也核对当前独立财务岗位、法人任职及原轮次完整字段权限。 */
    public void requireFinance(UUID paymentId) { access.requireFinance(paymentId); }

    /** 固定首次成功原件后建立读取意图，当前银行查询或争议不阻止取证。 */
    @Transactional
    public ActionReceipt queue(UUID paymentId, QueryInput input) {
        var source = locked(paymentId); var actor = actors.actor(); var now = time(Instant.now());
        var ledger = ledgers.find(actor.tenantId(), paymentId).orElse(null);
        if (source.payment().version() != input.operationVersion() || (ledger == null ? 0 : ledger.version()) != input.returnVersion()) throw conflict();
        if (checks.latest(actor.tenantId(), paymentId, actor.userId()).filter(SupplierPaymentReturnCheck::active).isPresent())
            throw new DomainException("SUPPLIER_PAYMENT_RETURN_PENDING", "Current finance actor already has an active supplier return query");
        if (ledger == null) { ledger = SupplierPaymentReturns.open(source.request(), now); ledgers.create(ledger); }
        else if (!ledger.request().equals(source.request())) throw conflict();
        var check = SupplierPaymentReturnCheck.queue(new SupplierPaymentReturnCheck.Input(UUID.randomUUID(), actor.tenantId(), source.request().command().targetDigest(),
                source.paymentVersion(), source.request(), actor.userId(), now));
        checks.create(check);
        return receipt(source, check, null, ledger, audit(source, check.input().id(), check.version(), "QUERY", input.comment(), now));
    }

    /** 原件、累计登记、单次消费、跨业务防重及审计任一失败都整体回滚。 */
    @Transactional
    public ActionReceipt register(UUID paymentId, RegisterInput input) {
        var source = locked(paymentId); var actor = actors.actor(); var now = time(Instant.now());
        var ledger = ledgers.find(actor.tenantId(), paymentId).orElseThrow(SupplierPaymentReturnService::conflict);
        var check = checks.find(actor.tenantId(), input.checkId()).orElseThrow(SupplierPaymentReturnService::conflict);
        if (source.payment().version() != input.operationVersion() || ledger.version() != input.returnVersion() || check.version() != input.checkVersion()
                || !check.input().requestedBy().equals(actor.userId())
                || !checks.latest(actor.tenantId(), paymentId, actor.userId()).map(value -> value.input().id().equals(check.input().id())).orElse(false)) throw conflict();
        sources.requireCheck(check, source); var issue = registrationIssue(source, ledger, check, now);
        if (issue != null) throw new DomainException(issue, "Supplier return registration requires fresh complete evidence and the current independent finance decision");
        if (input.outcome() != check.receipt().status()) throw new DomainException("SUPPLIER_PAYMENT_RETURN_OUTCOME_CHANGED", "Registration must match the displayed supplier return outcome");
        var decision = new SupplierPaymentReturn(UUID.randomUUID(), actor.tenantId(), check.input().id(), check.receipt(), actor.userId(), now, input.evidenceReference().trim(), input.comment());
        SupplierPaymentReturns next;
        try { next = registrations.register(decision, ledger.version(), check.version()); }
        catch (DuplicateKeyException duplicate) {
            throw new DomainException("SUPPLIER_PAYMENT_RETURN_ALREADY_RECORDED", "Bank receipt is already registered to a financial source");
        }
        return receipt(source, check.resolve(decision, now), decision.id(), next, audit(source, decision.id(), next.version(), "REGISTER", input.comment(), now));
    }

    /** 页面与写入口共用守卫，尚未登记的其他财务原件同样约束累计证据。 */
    public String registrationIssue(SupplierPaymentReturnSources.Source source, SupplierPaymentReturns ledger, SupplierPaymentReturnCheck check, Instant now) {
        if (check == null || !check.usable(now)) return "SUPPLIER_PAYMENT_RETURN_EVIDENCE_UNAVAILABLE";
        if (ledger == null || !ledger.reviewRequired()) return "SUPPLIER_PAYMENT_RETURN_NOT_REQUIRED";
        var bank = source.payment(); var proof = check.receipt();
        if ((!bank.settleable() && bank.status() != SupplierPaymentOperation.Status.REVERSED) || bank.conflictingObservation() != null)
            return "SUPPLIER_PAYMENT_RETURN_PAYMENT_UNRESOLVED";
        if (!proof.samePaymentFacts(bank.observation()) || bank.observation().revision() > proof.current().revision()
                || bank.observation().observedAt().isAfter(proof.current().observedAt()) || evidenceChanged(check)) return "SUPPLIER_PAYMENT_RETURN_EVIDENCE_CHANGED";
        try {
            sources.requireCheck(check, source);
            ledger.register(new SupplierPaymentReturn(UUID.randomUUID(), check.input().tenantId(), check.input().id(), proof,
                    check.input().requestedBy(), now, "preview", "Check original supplier return evidence"));
            return null;
        } catch (DomainException invalid) { return invalid.code(); }
    }

    /** 短事务领取一次只读任务，已过期租约明确失败而不重新外发。 */
    @Transactional
    public SupplierPaymentReturnCheck claim(String tenant, UUID id, Instant at) {
        var initial = checks.find(tenant, id).orElse(null); if (initial == null || !initial.active()) return null;
        var source = sources.locked(tenant, initial.input().request().command().id()); var current = checks.find(tenant, id).orElseThrow(); var now = time(at);
        if (current.expired(now)) { checks.update(current.fail(SupplierPaymentReturnCheck.Issue.TIMEOUT, now)); return null; }
        if (current.status() != SupplierPaymentReturnCheck.Status.QUEUED || !available(current, source, now)) return null;
        var claimed = current.claim(now, QUERY_LEASE); checks.update(claimed); return claimed;
    }

    /** 新入款、未核清或矛盾证据先冻结同一应付，查询不能自动登记资金。 */
    @Transactional
    public void finish(SupplierPaymentReturnCheck claimed, FinanceResult<SupplierPaymentReturnPort.Receipt> result, Instant at) {
        var input = claimed.input(); var source = sources.locked(input.tenantId(), input.request().command().id());
        var current = current(claimed); if (current == null) return; var now = time(at);
        if (!available(current, source, now)) return;
        var completed = current.complete(result, now); checks.update(completed); if (completed.receipt() == null) return;
        var ledger = ledgers.find(input.tenantId(), input.request().command().id()).orElseThrow(SupplierPaymentReturnService::conflict);
        var proof = completed.receipt(); var recorded = ledger.entries().stream().map(SupplierPaymentReturns.Entry::proof).toList();
        boolean same = proof.status() != SupplierPaymentReturnPort.Status.UNRESOLVED && proof.returns().size() == recorded.size()
                && proof.returns().containsAll(recorded) && proof.samePaymentFacts(source.payment().observation()) && !evidenceChanged(completed);
        if (!same) ledgers.requireReview(input.tenantId(), input.request().command().id(), now);
    }

    /** 外部异常不创造财务结论，既有资金及冻结继续保留。 */
    @Transactional
    public void fail(SupplierPaymentReturnCheck claimed, Instant at) {
        sources.locked(claimed.input().tenantId(), claimed.input().request().command().id()); var current = current(claimed);
        if (current != null) checks.update(current.fail(SupplierPaymentReturnCheck.Issue.INTERNAL_ERROR, time(at)));
    }

    private boolean evidenceChanged(SupplierPaymentReturnCheck check) {
        return checks.history(check.input().tenantId(), check.input().request().command().id()).stream().anyMatch(value -> !check.receipt().continues(value.receipt()));
    }
    private SupplierPaymentReturnSources.Source locked(UUID paymentId) {
        requireFinance(paymentId); var actor = actors.actor(); var source = sources.locked(actor.tenantId(), paymentId);
        requireFinance(paymentId); sources.requireFinance(source, actor.userId()); return source;
    }
    private boolean available(SupplierPaymentReturnCheck check, SupplierPaymentReturnSources.Source source, Instant now) {
        try { sources.requireCheck(check, source); sources.requireFinance(source, check.input().requestedBy()); return true; }
        catch (DomainException changed) { checks.update(check.voidSource(now)); return false; }
    }
    private SupplierPaymentReturnCheck current(SupplierPaymentReturnCheck claimed) {
        return checks.find(claimed.input().tenantId(), claimed.input().id()).filter(value -> value.equals(claimed) && value.status() == SupplierPaymentReturnCheck.Status.RUNNING).orElse(null);
    }
    private UUID audit(SupplierPaymentReturnSources.Source source, UUID aggregate, long version, String action, String comment, Instant at) {
        var original = source.request().command().holdCommand().authorization().source().reservation().source(); var event = UUID.randomUUID();
        var payload = Map.of("requestId", original.requestId(), "roundNo", original.round().roundNo(), "paymentId", source.payment().command().id(), "authorizedRole", "FINANCE", "comment", comment.trim());
        jdbc.update("""
                INSERT INTO audit_event(id,tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,application_id,action,actor_id,payload_json,occurred_at)
                VALUES(?,?,?,'SupplierPaymentReturn',?,?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), original.tenantId(), event.toString(), aggregate.toString(), version, original.applicationId().toString(),
                "SUPPLIER_PAYMENT_RETURN_" + action, actors.actor().userId(), json.write(payload), Timestamp.from(at));
        return event;
    }
    private static ActionReceipt receipt(SupplierPaymentReturnSources.Source source, SupplierPaymentReturnCheck check, UUID registrationId, SupplierPaymentReturns ledger, UUID event) {
        return new ActionReceipt(source.payment().command().id(), source.payment().version(), check.input().id(), check.version(), registrationId, ledger.version(), event);
    }
    private static Instant time(Instant at) { return at.truncatedTo(ChronoUnit.MICROS); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Displayed original supplier payment, return ledger or query changed"); }

    /**
     * 查询只确认展示的原银行和账本版本，不接受替代交易或账号。
     * @author owlzhangfq@gmail.com
     */
    public record QueryInput(@Positive long operationVersion, @Min(0) long returnVersion, @NotBlank @Size(max = 2000) String comment) {
        /** 未声明的财务事实不允许静默忽略。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown supplier return query field"); }
    }
    /**
     * 明确登记只消费已展示原件，金额与收款账户全部由服务端派生。
     * @author owlzhangfq@gmail.com
     */
    public record RegisterInput(@Positive long operationVersion, @Positive long returnVersion, @NotNull UUID checkId, @Positive long checkVersion,
            @NotNull SupplierPaymentReturnPort.Status outcome, @NotBlank @Size(max = 128) @Pattern(regexp = "[^\\p{Cntrl}]+") String evidenceReference,
            @NotBlank @Size(max = 2000) String comment) {
        /** 未核清只能展示，不能被人工声明为已经登记的结论。 */
        public RegisterInput {
            if (outcome == SupplierPaymentReturnPort.Status.UNRESOLVED) throw new IllegalArgumentException("An explicit supplier return outcome is required");
        }
        /** 客户端不能覆盖网关回款金额、修订或账号。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown supplier return registration field"); }
    }
    /**
     * 动作回执仅定位本地保存版本，不把查询受理当作资金或 ERP 完成。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ActionReceipt(UUID paymentId, long operationVersion, UUID checkId, long checkVersion, UUID registrationId, long returnVersion, UUID auditEventId) { }
}
