package io.agentflow.expense;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.BudgetConsumptionReversalCommand;
import io.agentflow.finance.BudgetConsumptionReversalOperation;
import io.agentflow.finance.JdbcBudgetConsumptionReversalRepository;
import io.agentflow.finance.Money;
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
 * 原结算、独立预算和资源结果分别投影；来源失效不遮蔽已授权调整及其历史。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseResourceAdjustmentWorkspace {
    private final CurrentActor actors;
    private final ExpenseSettlementAccess settlementAccess;
    private final ExpenseResourceAdjustmentAccess access;
    private final JdbcExpenseSettlementRepository settlements;
    private final ExpenseResourceAdjustmentSources sources;
    private final JdbcExpenseResourceAdjustmentPreparationRepository preparations;
    private final ExpenseResourceAdjustmentPreparationService preparing;
    private final JdbcExpenseResourceAdjustmentRepository adjustments;
    private final JdbcBudgetConsumptionReversalRepository budgets;
    private final ExpenseResourceAdjustmentActionService actions;
    /** 先按原轮次完整字段授权，所有响应对象仅保留办理所需的财务事实。 */
    public ExpenseResourceAdjustmentWorkspace(CurrentActor actors, ExpenseSettlementAccess settlementAccess, ExpenseResourceAdjustmentAccess access,
            JdbcExpenseSettlementRepository settlements, ExpenseResourceAdjustmentSources sources, JdbcExpenseResourceAdjustmentPreparationRepository preparations,
            ExpenseResourceAdjustmentPreparationService preparing, JdbcExpenseResourceAdjustmentRepository adjustments,
            JdbcBudgetConsumptionReversalRepository budgets, ExpenseResourceAdjustmentActionService actions) {
        this.actors = actors; this.settlementAccess = settlementAccess; this.access = access; this.settlements = settlements; this.sources = sources;
        this.preparations = preparations; this.preparing = preparing; this.adjustments = adjustments; this.budgets = budgets; this.actions = actions;
    }
    /** 一致快照下返回当前能力，真正写入仍在原报销锁下重新核对。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View read(UUID report, Map<String, String> parameters) {
        if (!Set.of("roundNo").containsAll(parameters.keySet())) throw invalid(); Integer round = null;
        if (parameters.containsKey("roundNo")) {
            var number = parameters.get("roundNo"); if (!number.matches("[1-9][0-9]{0,8}")) throw invalid(); round = Integer.valueOf(number);
        }
        var context = settlementAccess.read(report, round); var tenant = actors.actor().tenantId();
        var settlement = settlements.find(tenant, report).orElseThrow(ExpenseResourceAdjustmentWorkspace::notFound);
        if (!settlement.resourcesConsumed() || settlement.input().source().roundNo() != context.roundNo()) throw notFound();
        boolean finance = canManage(report, context.roundNo()) && context.application().roundNo() == context.roundNo(); var now = Instant.now();
        var latest = finance ? preparations.latest(tenant, report, actors.actor().userId()).orElse(null) : null;
        String issue = preparationIssue(tenant, report, finance, latest);
        var input = settlement.input();
        return new View(report, input.source().applicationId(), context.roundNo(), context.application().version(), context.businessVersion(), settlement.version(),
                new Original(input.gross(), input.offsets(), input.payable(), settlement.budgetOperationId()), finance, issue == null, issue,
                latest == null ? null : preparation(latest, now), adjustments.history(tenant, report).stream().map(value -> adjustment(value, finance, now)).toList());
    }
    private boolean canManage(UUID report, int round) {
        try { access.requireFinance(report, round); return true; } catch (DomainException denied) { return false; }
    }
    private String preparationIssue(String tenant, UUID report, boolean finance, ExpenseResourceAdjustmentPreparation latest) {
        if (!finance) return "INDEPENDENT_FINANCE_REQUIRED";
        if (adjustments.active(tenant, report).isPresent()) return "EXPENSE_ADJUSTMENT_EXISTS";
        if (latest != null && latest.active()) return "EXPENSE_ADJUSTMENT_PREPARATION_PENDING";
        try { sources.find(tenant, report); return null; } catch (DomainException unavailable) { return unavailable.code(); }
    }
    private Preparation preparation(ExpenseResourceAdjustmentPreparation value, Instant now) {
        var input = value.input(); var period = value.period(); var issue = preparing.authorizationIssue(value, now);
        Instant expiry = period == null ? null : period.observedAt().plus(BudgetConsumptionReversalCommand.MAX_AUTHORIZATION_AGE);
        if (expiry != null && expiry.isAfter(period.validUntil())) expiry = period.validUntil();
        return new Preparation(input.id(), value.version(), value.status(), input.accountingDate(), input.evidenceReference(), input.reason(), input.requestedAt(), value.updatedAt(),
                value.issue(), period == null ? null : period.periodReference(), expiry, issue == null, issue);
    }
    private Adjustment adjustment(ExpenseResourceAdjustment value, boolean finance, Instant now) {
        var tenant = value.input().basis().tenantId(); var operation = budgets.find(tenant, value.id()).orElseThrow(ExpenseResourceAdjustmentWorkspace::notFound);
        var command = operation.input().command(); var applied = value.budgetReversal(); var receipt = operation.observation();
        var issue = operation.failure() == null ? receipt != null && receipt.rejection() != null ? "BUDGET_" + receipt.rejection().name() : null : operation.failure().name();
        var budget = new Budget(operation.version(), operation.status(), operation.attempts(), command.expiresAt(), operation.updatedAt(), issue,
                applied == null ? null : applied.reference(), applied == null ? null : applied.appliedAt());
        var retirement = adjustments.retirement(tenant, value.id()).map(item -> new Retirement(item.retiredBy(), item.retiredAt(), item.evidenceReference(), item.reason())).orElse(null);
        return new Adjustment(value.id(), value.version(), value.status(), value.resourcesReversed(), value.issue(), command.authorizedBy(), command.createdAt(), value.updatedAt(),
                command.period().request().accountingDate(), command.evidenceReference(), command.reason(), budget,
                finance ? actions.availableActions(value, operation, now) : List.of(), finance && actions.canRetire(value, operation, now), retirement);
    }
    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_ADJUSTMENT_QUERY", "Expense adjustment query only accepts a positive roundNo"); }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Consumed expense settlement is unavailable in this round"); }

    /**
     * 本人准备不向其他读者展示；已授权调整是原轮次财务历史的一部分。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(UUID reportId, UUID applicationId, int roundNo, long applicationVersion, long businessVersion, long settlementVersion,
            Original original, boolean finance, boolean canPrepare, String preparationIssue, Preparation latestPreparation, List<Adjustment> adjustments) { }
    /**
     * 原批准总额和原借款冲销保留，原支付净额由二者相减得到。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Original(Money gross, Money offsets, Money payable, UUID budgetOperationId) { }
    /**
     * 期间准备不包含写命令，有效期限不因页面刷新而延长。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Preparation(UUID id, long version, ExpenseResourceAdjustmentPreparation.Status status, LocalDate accountingDate, String evidenceReference,
            String reason, Instant requestedAt, Instant updatedAt, String issue, String periodReference, Instant expiresAt, boolean canAuthorize, String authorizationIssue) { }
    /**
     * 预算和资源完成分别展示，结束证明保留在对应原调整中。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Adjustment(UUID id, long version, ExpenseResourceAdjustment.Status status, boolean resourcesReversed, String issue,
            String authorizedBy, Instant authorizedAt, Instant updatedAt, LocalDate accountingDate, String evidenceReference, String reason, Budget budget,
            List<ExpenseResourceAdjustmentActionService.Action> availableActions, boolean canRetire, Retirement retirement) { }
    /**
     * 先前接受的预算成功独立保存，后续查询期间也不抹除实际效果。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Budget(long version, BudgetConsumptionReversalOperation.Status status, int attempts, Instant expiresAt, Instant updatedAt,
            String issue, String acceptedReference, Instant acceptedAt) { }
    /**
     * 安全结束是独立财务决定，不能被新准备覆盖。
     * @author owlzhangfq@gmail.com
     */
    public record Retirement(String retiredBy, Instant retiredAt, String evidenceReference, String reason) { }
}
