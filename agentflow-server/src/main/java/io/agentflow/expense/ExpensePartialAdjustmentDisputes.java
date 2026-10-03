package io.agentflow.expense;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.BudgetConsumptionReductionOperation;
import io.agentflow.finance.ExpenseAccrualReductionOperation;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 公开裁决只采用服务器保存的单侧候选，当前权限、相邻证明及具名审计共同提交。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpensePartialAdjustmentDisputes {
    private final CurrentActor actors;
    private final ExpenseResourceAdjustmentAccess access;
    private final ExpenseReportRepository reports;
    private final JdbcExpenseSettlementRepository settlements;
    private final JdbcExpensePartialAdjustmentRepository adjustments;
    private final JdbcExpensePartialDisputeRepository disputes;
    private final ExpensePartialAdjustmentAudit audit;

    /** 应用层编排实际权限与持久历史，财务效果和可裁决规则仍由领域状态负责。 */
    public ExpensePartialAdjustmentDisputes(CurrentActor actors, ExpenseResourceAdjustmentAccess access, ExpenseReportRepository reports,
            JdbcExpenseSettlementRepository settlements, JdbcExpensePartialAdjustmentRepository adjustments,
            JdbcExpensePartialDisputeRepository disputes, ExpensePartialAdjustmentAudit audit) {
        this.actors = actors; this.access = access; this.reports = reports; this.settlements = settlements;
        this.adjustments = adjustments; this.disputes = disputes; this.audit = audit;
    }

    /** 原件未知不阻止保存真实差额决定；新效果与来源确认仍由原入口独立复核。 */
    @Transactional
    public ExpensePartialAdjustmentAudit.Receipt resolve(UUID reportId, Input input) {
        var context = access.locked(reportId, input.roundNo());
        access.requireVersions(context, input.roundNo(), input.applicationVersion(), input.businessVersion());
        var actor = actors.actor(); var report = reports.find(actor.tenantId(), reportId).orElseThrow(ExpensePartialAdjustmentDisputes::conflict);
        var settlement = settlements.find(actor.tenantId(), reportId).orElseThrow(ExpensePartialAdjustmentDisputes::conflict);
        if (settlement.version() != input.settlementVersion() || !settlement.resourcesConsumed()) throw conflict(); settlement.requireReport(report);
        var before = adjustments.find(actor.tenantId(), input.adjustmentId()).orElseThrow(ExpensePartialAdjustmentDisputes::conflict);
        if (before.retirement() != null || before.version() != input.adjustmentVersion() || !before.input().basis().reportId().equals(reportId)
                || before.input().basis().funding().financial().settlement().input().source().roundNo() != input.roundNo()) throw conflict();
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS); var issue = resolutionIssue(before, input.side(), now);
        if (issue != null) throw new DomainException(input.side() == ExpensePartialAdjustmentPreparation.Side.BUDGET
                ? "BUDGET_REDUCTION_DISPUTE_UNRESOLVABLE" : "EXPENSE_ACCRUAL_REDUCTION_DISPUTE_UNRESOLVABLE", "Partial adjustment dispute cannot be resolved: " + issue);
        var budget = input.side() == ExpensePartialAdjustmentPreparation.Side.BUDGET ? before.budget().conflictingObservation() : null;
        var accrual = input.side() == ExpensePartialAdjustmentPreparation.Side.ACCRUAL ? before.accrual().conflictingObservation() : null;
        if (!input.outcome().name().equals(budget != null ? budget.status().name() : accrual.status().name())) throw conflict();
        var decision = new ExpensePartialDisputeResolution(UUID.randomUUID(), actor.tenantId(), before.id(), input.side(),
                budget != null ? budget.operationId() : accrual.operationId(), before.version(), Math.incrementExact(before.version()),
                budget, accrual, actor.userId(), now, input.evidenceReference(), input.comment());
        var after = adjustments.resolve(decision); var event = audit.dispute(report, decision, after);
        return new ExpensePartialAdjustmentAudit.Receipt(reportId, input.roundNo(), after.id(), after.version(), null, null, event);
    }

    /** 工作台与实际写入共用纯状态预览；查询历史不外发、不更新原件或续期。 */
    public String resolutionIssue(ExpensePartialAdjustment value, ExpensePartialAdjustmentPreparation.Side side, Instant at) {
        try {
            if (side == ExpensePartialAdjustmentPreparation.Side.BUDGET) {
                var operation = value.budget();
                if (operation == null || operation.status() != BudgetConsumptionReductionOperation.Status.RECONCILING) return BudgetConsumptionReductionOperation.ResolutionIssue.NOT_DISPUTED.name();
                var history = disputes.budgetHistory(value); var issue = operation.resolutionIssue(history, at);
                if (issue != null) return issue.name(); value.resolveBudget(operation.conflictingObservation().status(), history, at);
            } else {
                var operation = value.accrual();
                if (operation == null || operation.status() != ExpenseAccrualReductionOperation.Status.RECONCILING) return ExpenseAccrualReductionOperation.ResolutionIssue.NOT_DISPUTED.name();
                var history = disputes.accrualHistory(value); var issue = operation.resolutionIssue(history, at);
                if (issue != null) return issue.name(); value.resolveAccrual(operation.conflictingObservation().status(), history, at);
            }
            return null;
        } catch (DomainException unavailable) { return unavailable.code(); }
    }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Displayed partial adjustment, original settlement or selected dispute outcome changed"); }

    /**
     * 页面明确选择已显示的侧和终态，候选、身份与财务原件只能从实际记录恢复。
     * @author owlzhangfq@gmail.com
     */
    public record Input(@Positive int roundNo, @Positive long applicationVersion, @Positive long businessVersion, @Positive long settlementVersion,
            @NotNull UUID adjustmentId, @Positive long adjustmentVersion, @NotNull ExpensePartialAdjustmentPreparation.Side side,
            @NotNull ExpensePartialDisputeResolution.Outcome outcome, @NotBlank @Size(max = 128) @Pattern(regexp = "[^\\p{Cntrl}]+") String evidenceReference,
            @NotBlank @Size(max = 2000) @Pattern(regexp = "[^\\p{Cntrl}]+") String comment) {
        /** 禁止注入客户端候选、办理人、租户或任何原凭证内容。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown partial adjustment dispute field"); }
    }
}
