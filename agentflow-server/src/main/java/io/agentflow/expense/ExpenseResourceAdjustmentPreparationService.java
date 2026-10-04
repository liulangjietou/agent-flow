package io.agentflow.expense;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.AccountingPeriodPort;
import io.agentflow.finance.BudgetConsumptionReversalOperation;
import io.agentflow.finance.FinanceResult;
import io.agentflow.finance.JdbcBudgetConsumptionReversalRepository;
import io.agentflow.finance.PaymentPersonnel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

/**
 * 准备只读期间，独立财务明确确认后才共同登记调整授权与预算冲正命令。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseResourceAdjustmentPreparationService {
    private final ApplicationEventPublisher events;
    private static final Duration LEASE = Duration.ofSeconds(90);
    private final CurrentActor actors;
    private final ExpenseResourceAdjustmentAccess access;
    private final ExpenseReportRepository reports;
    private final ExpenseResourceAdjustmentSources sources;
    private final PaymentPersonnel personnel;
    private final JdbcExpenseResourceAdjustmentPreparationRepository preparations;
    private final JdbcExpenseResourceAdjustmentRepository adjustments;
    private final JdbcBudgetConsumptionReversalRepository budgets;
    private final ExpenseResourceAdjustmentAudit audit;
    /** 原财务来源、角色、任职、准备和预算写意图在应用层协调。 */
    public ExpenseResourceAdjustmentPreparationService(CurrentActor actors, ExpenseResourceAdjustmentAccess access, ExpenseReportRepository reports,
            ExpenseResourceAdjustmentSources sources, PaymentPersonnel personnel, JdbcExpenseResourceAdjustmentPreparationRepository preparations,
            JdbcExpenseResourceAdjustmentRepository adjustments, JdbcBudgetConsumptionReversalRepository budgets, ExpenseResourceAdjustmentAudit audit, ApplicationEventPublisher events) {
        this.events = events;
        this.actors = actors; this.access = access; this.reports = reports; this.sources = sources; this.personnel = personnel;
        this.preparations = preparations; this.adjustments = adjustments; this.budgets = budgets; this.audit = audit;
    }
    /** 只记录日期与完整取消原因，所有金额及外部目标来自已核对的原财务记录。 */
    @Transactional
    public ActionReceipt prepare(UUID report, PrepareInput input) {
        var context = access.locked(report, input.roundNo()); access.requireVersions(context, input.roundNo(), input.applicationVersion(), input.businessVersion());
        var actor = actors.actor(); var basis = sources.find(actor.tenantId(), report); requireSettlement(basis, input.settlementVersion()); requireNoActive(basis);
        var latest = preparations.latest(actor.tenantId(), report, actor.userId()).orElse(null);
        if (latest != null && latest.active()) throw new DomainException("EXPENSE_ADJUSTMENT_PREPARATION_PENDING", "Current finance actor already has a pending adjustment preparation");
        var now = time(Instant.now()); var value = ExpenseResourceAdjustmentPreparation.queue(new ExpenseResourceAdjustmentPreparation.Input(UUID.randomUUID(), basis,
                input.accountingDate(), actor.userId(), input.evidenceReference(), input.reason(), now));
        preparations.create(value); var event = audit.record(basis, value.input().id(), value.version(), "EXPENSE_ADJUSTMENT_PREPARE", input.reason(), now);
        return receipt(value, null, null, event);
    }
    /** 原发起财务只能消费自己的最新准备；消费、调整、预算命令和审计同事务。 */
    @Transactional
    public ActionReceipt authorize(UUID report, AuthorizeInput input) {
        var context = access.locked(report, input.roundNo()); access.requireVersions(context, input.roundNo(), input.applicationVersion(), input.businessVersion());
        var actor = actors.actor(); var prepared = preparations.find(actor.tenantId(), input.preparationId()).orElseThrow(ExpenseResourceAdjustmentPreparationService::conflict);
        if (!prepared.input().basis().reportId().equals(report) || prepared.version() != input.preparationVersion() || !prepared.input().requestedBy().equals(actor.userId())
                || !preparations.latest(actor.tenantId(), report, actor.userId()).filter(value -> value.input().id().equals(input.preparationId())).isPresent()) throw conflict();
        requireSettlement(prepared.input().basis(), input.settlementVersion()); requireAvailable(prepared); var now = time(Instant.now()); var authorized = prepared.authorize(now);
        persist(authorized); var adjustment = ExpenseResourceAdjustment.begin(authorized.authorizedInput()); adjustments.create(adjustment, authorized.version());
        var budget = BudgetConsumptionReversalOperation.queue(adjustment.input().budget(), now); budgets.create(budget);
        var event = audit.record(prepared.input().basis(), adjustment.id(), adjustment.version(), "EXPENSE_ADJUSTMENT_AUTHORIZE", input.comment(), now);
        return receipt(authorized, adjustment, budget, event);
    }
    /** 短事务领取并复核原依据，租约过期只留下失败，不自动授权。 */
    @Transactional
    public ExpenseResourceAdjustmentPreparation claim(String tenant, UUID id, Instant at) {
        var initial = preparations.find(tenant, id).orElse(null); if (initial == null || !initial.active()) return null;
        reports.lock(tenant, initial.input().basis().reportId()); var value = preparations.find(tenant, id).orElseThrow(); var now = time(at);
        if (value.expired(now)) { persist(value.fail("TIMEOUT", now)); return null; }
        if (value.status() != ExpenseResourceAdjustmentPreparation.Status.QUEUED || !available(value, now)) return null;
        var claimed = value.claim(now, LEASE); persist(claimed); return claimed;
    }
    /** 完成读取时仍复核当前来源，迟到或外部非法期间不能生成可授权准备。 */
    @Transactional
    public void finish(ExpenseResourceAdjustmentPreparation claimed, FinanceResult<AccountingPeriodPort.OpenPeriod> result, Instant at) {
        var current = lockedCurrent(claimed); if (current == null) return; var now = time(at);
        if (current.expired(now)) { persist(current.fail("TIMEOUT", now)); return; }
        if (!available(current, now)) return;
        ExpenseResourceAdjustmentPreparation next;
        if (result instanceof FinanceResult.Success<AccountingPeriodPort.OpenPeriod> success) {
            try { next = current.ready(success.value(), now); }
            catch (DomainException invalid) { next = current.fail("INVALID_RESPONSE", now); }
        } else next = current.fail(failure(result), now);
        persist(next);
    }
    /** 本地异常只记录分类，不用错误正文或合成期间推进流程。 */
    @Transactional
    public void fail(ExpenseResourceAdjustmentPreparation claimed, Instant at) {
        var current = lockedCurrent(claimed); if (current != null) persist(current.fail("INTERNAL_ERROR", time(at)));
    }
    /** 工作台与授权入口共用来源及过期规则，读取入口另执行当前字段授权。 */
    public String authorizationIssue(ExpenseResourceAdjustmentPreparation value, Instant at) {
        if (value == null || value.status() != ExpenseResourceAdjustmentPreparation.Status.READY) return "EXPENSE_ADJUSTMENT_NOT_READY";
        if (!value.usable(at)) return "EXPENSE_ADJUSTMENT_PREPARATION_EXPIRED";
        try { requireAvailable(value); return null; } catch (DomainException problem) { return problem.code(); }
    }
    private void persist(ExpenseResourceAdjustmentPreparation value) {
        preparations.update(value); events.publishEvent(new ExpenseAdjustmentChanged.Preparation(value));
    }
    private void requireAvailable(ExpenseResourceAdjustmentPreparation value) {
        var basis = value.input().basis(); requireNoActive(basis); sources.requireCurrent(basis);
        personnel.requireEligible(basis.tenantId(), value.input().requestedBy(), basis.legalEntityId());
    }
    private boolean available(ExpenseResourceAdjustmentPreparation value, Instant at) {
        try { requireAvailable(value); return true; } catch (DomainException changed) { persist(value.voidSource(at)); return false; }
    }
    private void requireNoActive(ExpenseResourceAdjustmentBasis basis) {
        if (adjustments.active(basis.tenantId(), basis.reportId()).isPresent()) throw new DomainException("EXPENSE_ADJUSTMENT_EXISTS", "Original expense already has an active adjustment");
    }
    private ExpenseResourceAdjustmentPreparation lockedCurrent(ExpenseResourceAdjustmentPreparation claimed) {
        var basis = claimed.input().basis(); reports.lock(basis.tenantId(), basis.reportId());
        return preparations.find(basis.tenantId(), claimed.input().id()).filter(value -> value.equals(claimed) && value.status() == ExpenseResourceAdjustmentPreparation.Status.RUNNING).orElse(null);
    }
    private static void requireSettlement(ExpenseResourceAdjustmentBasis basis, long version) { if (basis.settlement().version() != version) throw conflict(); }
    private static String failure(FinanceResult<?> result) {
        if (result instanceof FinanceResult.Unavailable<?> value) return value.failure().name();
        if (result instanceof FinanceResult.Rejected<?> value) return value.reason().name(); return "INVALID_RESPONSE";
    }
    private static ActionReceipt receipt(ExpenseResourceAdjustmentPreparation value, ExpenseResourceAdjustment adjustment, BudgetConsumptionReversalOperation budget, UUID event) {
        var basis = value.input().basis();
        return new ActionReceipt(basis.reportId(), basis.settlement().input().source().roundNo(), value.input().id(), value.version(), adjustment == null ? null : adjustment.id(),
                adjustment == null ? null : adjustment.version(), budget == null ? null : budget.version(), event);
    }
    private static Instant time(Instant at) { return at.truncatedTo(ChronoUnit.MICROS); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Displayed expense source or adjustment preparation changed"); }

    /**
     * 准备不接受金额或资源列表，服务端固定原报销事实。
     * @author owlzhangfq@gmail.com
     */
    public record PrepareInput(@Positive int roundNo, @Positive long applicationVersion, @Positive long businessVersion, @Positive long settlementVersion,
            @NotNull LocalDate accountingDate, @NotBlank @Size(max = 128) @Pattern(regexp = "[^\\p{Cntrl}]+") String evidenceReference,
            @NotBlank @Size(max = 2000) @Pattern(regexp = "[^\\p{Cntrl}]+") String reason) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown expense adjustment preparation field"); }
    }
    /**
     * 明确采纳已经展示的本人准备，不能在授权时替换日期或明细。
     * @author owlzhangfq@gmail.com
     */
    public record AuthorizeInput(@Positive int roundNo, @Positive long applicationVersion, @Positive long businessVersion, @Positive long settlementVersion,
            @NotNull UUID preparationId, @Positive long preparationVersion, @NotBlank @Size(max = 2000) String comment) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown expense adjustment authorization field"); }
    }
    /**
     * 本地接受回执不代表预算外发或资源已经冲回。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ActionReceipt(UUID reportId, int roundNo, UUID preparationId, long preparationVersion, UUID adjustmentId,
            Long adjustmentVersion, Long budgetVersion, UUID auditEventId) { }
}
