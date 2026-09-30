package io.agentflow.expense;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原批准、已完成净额和待办调整分别投影；读取不会查询外部财务或推进任何状态。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpensePartialAdjustmentWorkspace {
    private final CurrentActor actors;
    private final ExpenseSettlementAccess settlementAccess;
    private final ExpenseResourceAdjustmentAccess access;
    private final ExpenseReportRepository reports;
    private final JdbcExpenseSettlementRepository settlements;
    private final JdbcExpensePartialAdjustmentRepository adjustments;
    private final JdbcExpensePaymentReturnsRepository returns;
    private final JdbcBudgetOperationRepository budgets;
    private final JdbcPaymentOperationRepository payments;
    private final JdbcVoucherOperationRepository vouchers;
    private final JdbcExpensePartialPreparationRepository preparations;
    private final ExpensePartialPreparationService preparing;

    /** 查询层组合真实账本与权限，金额和授权规则继续由原领域模型负责。 */
    public ExpensePartialAdjustmentWorkspace(CurrentActor actors, ExpenseSettlementAccess settlementAccess, ExpenseResourceAdjustmentAccess access,
            ExpenseReportRepository reports, JdbcExpenseSettlementRepository settlements, JdbcExpensePartialAdjustmentRepository adjustments,
            JdbcExpensePaymentReturnsRepository returns, JdbcBudgetOperationRepository budgets, JdbcPaymentOperationRepository payments,
            JdbcVoucherOperationRepository vouchers, JdbcExpensePartialPreparationRepository preparations, ExpensePartialPreparationService preparing) {
        this.actors = actors; this.settlementAccess = settlementAccess; this.access = access; this.reports = reports; this.settlements = settlements;
        this.adjustments = adjustments; this.returns = returns; this.budgets = budgets; this.payments = payments; this.vouchers = vouchers;
        this.preparations = preparations; this.preparing = preparing;
    }

    /** 原轮次完整字段授权后，在同一数据库快照内读取显示版本与独立财务历史。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View read(UUID reportId, Map<String, String> parameters) {
        if (!Set.of("roundNo").containsAll(parameters.keySet())) throw invalid(); Integer round = null;
        if (parameters.containsKey("roundNo")) {
            var value = parameters.get("roundNo"); if (!value.matches("[1-9][0-9]{0,8}")) throw invalid(); round = Integer.valueOf(value);
        }
        var context = settlementAccess.read(reportId, round); var actor = actors.actor(); var tenant = actor.tenantId();
        var settlement = settlements.find(tenant, reportId).orElseThrow(ExpensePartialAdjustmentWorkspace::notFound);
        if (!settlement.resourcesConsumed() || settlement.input().gross().value().signum() == 0 || settlement.input().source().roundNo() != context.roundNo()) throw notFound();
        var report = reports.find(tenant, reportId).orElseThrow(ExpensePartialAdjustmentWorkspace::notFound);
        var history = adjustments.history(tenant, reportId); var previous = adjustments.latestCompleted(tenant, reportId).orElse(null);
        var original = history.isEmpty() ? ExpenseAdjustmentAmounts.from(report)
                : ExpenseAdjustmentAmounts.from(ExpenseReport.restore(history.get(0).input().basis().funding().financial().change().before().original()));
        var remaining = previous == null ? original : previous.input().basis().funding().financial().change().after();
        var ledger = returns.find(tenant, reportId).orElse(null); var claimed = adjustments.claimedReturnIds(tenant, reportId);
        boolean finance = context.application().roundNo() == context.roundNo() && canManage(reportId, context.roundNo()); var now = Instant.now();
        var entries = ledger == null ? List.<ReturnEntry>of() : ledger.entries().stream().map(value -> new ReturnEntry(value.registrationId(), value.proof().fundsIdentity(),
                value.proof().funding().amount(), value.proof().funding().receivedAt(), !claimed.contains(value.proof().fundsIdentity()))).toList();
        return new View(reportId, report.applicationId(), context.roundNo(), context.application().version(), context.businessVersion(), settlement.version(), settlement.status(),
                original(tenant, settlement, original), amounts(remaining), previous == null ? null : previous.id(), previous == null ? 0 : previous.version(),
                ledger == null ? 0 : ledger.version(), entries, finance, history.stream().map(value -> adjustment(value, finance, now)).toList());
    }

    private boolean canManage(UUID report, int round) {
        try { access.requireFinance(report, round); return true; } catch (DomainException denied) { return false; }
    }
    private Original original(String tenant, ExpenseSettlement settlement, ExpenseAdjustmentAmounts amounts) {
        var budget = budgets.find(tenant, settlement.budgetOperationId()).orElseThrow(ExpensePartialAdjustmentWorkspace::notFound);
        var accrual = vouchers.find(tenant, settlement.input().voucherOperationId()).orElseThrow(ExpensePartialAdjustmentWorkspace::notFound);
        var bank = settlement.input().payment() == null ? null : payments.find(tenant, settlement.input().payment().operationId()).orElseThrow(ExpensePartialAdjustmentWorkspace::notFound);
        var source = settlement.input().source();
        var paymentVoucher = bank == null ? null : vouchers.forRound(tenant, source.applicationId(), source.roundNo(), VoucherCommand.Kind.PAYMENT).orElse(null);
        return new Original(amounts(amounts), new Source(budget.input().command().id(), budget.version(), budget.status().name(), budget.updatedAt(), name(budget.failure())),
                voucher(accrual), bank == null ? null : new Source(bank.input().command().id(), bank.version(), bank.status().name(), bank.updatedAt(), name(bank.failure())),
                paymentVoucher == null ? null : voucher(paymentVoucher));
    }
    private static Source voucher(VoucherOperation value) {
        return new Source(value.input().command().id(), value.version(), value.status().name(), value.updatedAt(), name(value.failure()));
    }
    private static Amounts amounts(ExpenseAdjustmentAmounts value) {
        return new Amounts(value.gross(), value.tax(), value.offsetTotal(), value.payable(), value.lines().stream().map(line -> new Line(line.lineNo(), line.gross(), line.tax())).toList());
    }
    private Adjustment adjustment(ExpensePartialAdjustment value, boolean finance, Instant now) {
        var input = value.input(); var change = input.basis().funding().financial().change(); var completed = value.completion(); var retired = value.retirement();
        return new Adjustment(value.id(), value.version(), value.status(), value.issue(), input.requestedBy(), input.evidenceReference(), input.reason(), input.createdAt(), value.updatedAt(),
                amounts(change.before()), amounts(change.after()), input.basis().funding().selectedReturns().stream().map(entry -> entry.proof().fundsIdentity()).toList(),
                budget(value.budget()), accrual(value.accrual()), completed == null ? null : new Completion(completed.budgetVersion(), completed.accrualVersion(),
                        completed.budget().posting().reference(), completed.accrual().posting().voucher().postingReference(), completed.accrual().posting().voucher().voucherReference(), completed.at()),
                retired == null ? null : new Retirement(retired.actor(), retired.evidenceReference(), retired.reason(), retired.at()),
                finance ? preparation(value, ExpensePartialAdjustmentPreparation.Side.BUDGET, now) : null,
                finance ? preparation(value, ExpensePartialAdjustmentPreparation.Side.ACCRUAL, now) : null);
    }
    private Preparation preparation(ExpensePartialAdjustment value, ExpensePartialAdjustmentPreparation.Side side, Instant now) {
        return preparations.latest(value.input().basis().tenantId(), value.id(), side, actors.actor().userId()).map(prepared -> {
            var input = prepared.input(); var evidence = prepared.evidence(); var issue = preparing.authorizationIssue(prepared, now);
            return new Preparation(input.id(), prepared.version(), prepared.status(), input.accountingDate(), input.evidenceReference(), input.reason(), input.requestedAt(), prepared.updatedAt(),
                    prepared.issue(), evidence == null ? null : evidence.period().periodReference(), evidence == null ? null : evidence.expiresAt(), issue == null, issue);
        }).orElse(null);
    }
    private static Budget budget(BudgetConsumptionReductionOperation value) {
        if (value == null) return null; var command = value.input().command();
        return new Budget(command.id(), value.version(), value.status(), value.attempts(), command.authorizedBy(), command.createdAt(), command.period().request().accountingDate(),
                command.expiresAt(), value.updatedAt(), name(value.failure()), budgetFact(value.observation()), budgetFact(value.conflictingObservation()));
    }
    private static BudgetFact budgetFact(BudgetConsumptionReductionObservation value) {
        if (value == null) return null; var posting = value.posting();
        return new BudgetFact(value.status(), value.observedAt(), name(value.rejection()), posting == null ? null : posting.reference(),
                posting == null ? null : posting.reducedAmount(), posting == null ? null : posting.appliedAt());
    }
    private static Accrual accrual(ExpenseAccrualReductionOperation value) {
        if (value == null) return null; var command = value.input().command();
        return new Accrual(command.id(), value.version(), value.status(), value.attempts(), command.authorizedBy(), command.createdAt(), command.period().request().accountingDate(),
                command.expiresAt(), value.updatedAt(), name(value.failure()), accrualFact(value.observation()), accrualFact(value.conflictingObservation()));
    }
    private static AccrualFact accrualFact(ExpenseAccrualReductionObservation value) {
        if (value == null) return null; var posting = value.posting() == null ? null : value.posting().voucher();
        return new AccrualFact(value.status(), value.revision(), value.observedAt(), name(value.rejection()), value.acceptanceReference(), posting == null ? null : posting.postingReference(),
                posting == null ? null : posting.voucherReference(), posting == null ? null : posting.postedAt());
    }
    private static String name(Enum<?> value) { return value == null ? null : value.name(); }
    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_PARTIAL_ADJUSTMENT_QUERY", "Partial adjustment query only accepts a positive roundNo"); }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Consumed expense settlement is unavailable in this round"); }

    /**
     * 未完成意图不改变 remaining，原件版本供后续明确写入核对。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(UUID reportId, UUID applicationId, int roundNo, long applicationVersion, long businessVersion, long settlementVersion, ExpenseSettlement.Status settlementStatus,
            Original original, Amounts remaining, UUID previousId, long previousVersion, long returnsVersion, List<ReturnEntry> returns, boolean finance, List<Adjustment> adjustments) { }
    /**
     * 无付款的报销保留空付款及空付款凭证，不生成虚构编号或版本。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Original(Amounts amounts, Source budget, Source accrual, Source payment, Source paymentVoucher) { }
    /**
     * 仅公开财务操作身份和当前状态，不公开命令、目的地摘要或账户。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Source(UUID id, long version, String status, Instant updatedAt, String issue) { }
    /**
     * 本币核定、税额、借款抵扣和银行应付由同一金额投影计算。
     * @author owlzhangfq@gmail.com
     */
    public record Amounts(Money gross, Money tax, Money offsets, Money payable, List<Line> lines) { }
    /**
     * 原费用行号保持，页面只能在此基础上明确填写下一剩余额。
     * @author owlzhangfq@gmail.com
     */
    public record Line(int lineNo, Money gross, Money tax) { }
    /**
     * available 仅表示没有持久占用；是否符合本次差额仍在创建时核对。
     * @author owlzhangfq@gmail.com
     */
    public record ReturnEntry(UUID registrationId, String fundsIdentity, Money amount, Instant receivedAt, boolean available) { }
    /**
     * 准备只返回当前独立财务本人最新候选，已授权历史则供原轮次合法读者读取。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Adjustment(UUID id, long version, ExpensePartialAdjustment.Status status, String issue, String requestedBy, String evidenceReference, String reason,
            Instant createdAt, Instant updatedAt, Amounts before, Amounts after, List<String> returnIds, Budget budget, Accrual accrual, Completion completion,
            Retirement retirement, Preparation budgetPreparation, Preparation accrualPreparation) { }
    /**
     * 证据到期仍保留原日期，能力预览不延长有效期。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Preparation(UUID id, long version, ExpensePartialAdjustmentPreparation.Status status, LocalDate accountingDate, String evidenceReference, String reason,
            Instant requestedAt, Instant updatedAt, String issue, String periodReference, Instant expiresAt, boolean canAuthorize, String authorizationIssue) { }
    /**
     * 预算接受和冲突结果分别显示，查询中的状态不冒充实际预算回退。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Budget(UUID id, long version, BudgetConsumptionReductionOperation.Status status, int attempts, String authorizedBy, Instant authorizedAt,
            LocalDate accountingDate, Instant expiresAt, Instant updatedAt, String issue, BudgetFact accepted, BudgetFact conflicting) { }
    /**
     * 独立预算事实只保留页面核对所需的差额及凭据。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record BudgetFact(BudgetConsumptionReductionObservation.Status status, Instant observedAt, String rejection, String reference, Money reducedAmount, Instant appliedAt) { }
    /**
     * 会计和预算独立投影，成功一侧不会掩盖另一侧尚未办理。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Accrual(UUID id, long version, ExpenseAccrualReductionOperation.Status status, int attempts, String authorizedBy, Instant authorizedAt,
            LocalDate accountingDate, Instant expiresAt, Instant updatedAt, String issue, AccrualFact accepted, AccrualFact conflicting) { }
    /**
     * 当前接受与有争议的会计凭证身份分别保留。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AccrualFact(ExpenseAccrualReductionObservation.Status status, long revision, Instant observedAt, String rejection, String acceptanceReference,
            String postingReference, String voucherReference, Instant postedAt) { }
    /**
     * 已完成证明引用当时实际采用的两侧版本，后续查询不会替换它。
     * @author owlzhangfq@gmail.com
     */
    public record Completion(long budgetVersion, long accrualVersion, String budgetReference, String postingReference, String voucherReference, Instant at) { }
    /**
     * 安全结束保留具名理由和证据，原意图不删除。
     * @author owlzhangfq@gmail.com
     */
    public record Retirement(String actor, String evidenceReference, String reason, Instant at) { }
}
