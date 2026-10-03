package io.agentflow.expense;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.BudgetConsumptionReversalOperation;
import io.agentflow.finance.JdbcBudgetConsumptionReversalRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 调整查询、明确重发、本地恢复及安全结束沿用原授权和当前独立财务权限。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseResourceAdjustmentActionService {
    private final CurrentActor actors;
    private final ExpenseResourceAdjustmentAccess access;
    private final JdbcExpenseResourceAdjustmentRepository adjustments;
    private final JdbcBudgetConsumptionReversalRepository budgets;
    private final ExpenseResourceAdjustmentBudgetExecution budgetExecution;
    private final ExpenseResourceAdjustmentSources sources;
    private final ExpenseResourceAdjustmentAudit audit;
    /** 人工动作与审计共用当前权限，预算发送及原资源转换保持各自职责。 */
    public ExpenseResourceAdjustmentActionService(CurrentActor actors, ExpenseResourceAdjustmentAccess access, JdbcExpenseResourceAdjustmentRepository adjustments,
            JdbcBudgetConsumptionReversalRepository budgets, ExpenseResourceAdjustmentBudgetExecution budgetExecution,
            ExpenseResourceAdjustmentSources sources, ExpenseResourceAdjustmentAudit audit) {
        this.actors = actors; this.access = access; this.adjustments = adjustments; this.budgets = budgets; this.budgetExecution = budgetExecution; this.sources = sources; this.audit = audit;
    }
    /** 查询不依赖原来源仍可写，重发与资源恢复则复核原财务依据；所有动作保留原命令。 */
    @Transactional
    public ActionReceipt act(UUID report, OperationInput input) {
        var context = access.locked(report, input.roundNo()); access.requireVersions(context, input.roundNo(), input.applicationVersion(), input.businessVersion());
        var before = requireAdjustment(report, input.roundNo(), input.adjustmentId(), input.adjustmentVersion());
        var budget = requireBudget(before, input.budgetVersion()); var actor = actors.actor(); var now = now(); before.input().basis().requireAuthorization(actor.userId(), now);
        switch (input.action()) {
            case QUERY -> budgetExecution.query(actor.tenantId(), before.id(), budget.version(), now);
            case RESEND_ORIGINAL -> {
                if (!budget.input().command().authorizedBy().equals(actor.userId())) throw new DomainException("FORBIDDEN", "Only the original finance authorizer can resend the original budget command");
                budgetExecution.resend(actor.tenantId(), before.id(), budget.version(), now);
            }
            case RETRY_RESOURCES -> {
                sources.requireSupported(before.input().basis()); adjustments.update(before.retryResources(budget, now));
            }
            case CONFIRM_COMPLETED -> {
                sources.requireSupported(before.input().basis()); adjustments.update(before.confirmCompleted(budget, now));
            }
        }
        var after = adjustments.find(actor.tenantId(), before.id()).orElseThrow(); var operation = budgets.find(actor.tenantId(), before.id()).orElseThrow();
        var event = audit.record(before.input().basis(), before.id(), after.version(), "EXPENSE_ADJUSTMENT_" + input.action().name(), input.comment(), now);
        return receipt(after, operation, event);
    }
    /** 只有确定没有预算副作用时才安全结束；停止命令、释放占用、结束证明和审计共同提交。 */
    @Transactional
    public ActionReceipt retire(UUID report, RetireInput input) {
        var context = access.locked(report, input.roundNo()); access.requireVersions(context, input.roundNo(), input.applicationVersion(), input.businessVersion());
        var before = requireAdjustment(report, input.roundNo(), input.adjustmentId(), input.adjustmentVersion()); requireBudget(before, input.budgetVersion()); var now = now();
        var stopped = budgetExecution.stop(actors.actor().tenantId(), before.id(), input.budgetVersion(), now);
        var decision = new ExpenseResourceAdjustmentRetirement(before, stopped, actors.actor().userId(), input.evidenceReference(), input.reason(), now);
        adjustments.retire(decision); var event = audit.record(before.input().basis(), before.id(), decision.after().version(), "EXPENSE_ADJUSTMENT_RETIRE", input.reason(), now);
        return receipt(decision.after(), stopped, event);
    }
    /** 工作台只投影当前可办理动作，纯领域转换用于预判，不保存任何状态。 */
    public List<Action> availableActions(ExpenseResourceAdjustment value, BudgetConsumptionReversalOperation budget, Instant at) {
        if (value.status() == ExpenseResourceAdjustment.Status.RETIRED) return List.of();
        return Arrays.stream(Action.values()).filter(action -> {
            try {
                value.input().basis().requireAuthorization(actors.actor().userId(), at);
                switch (action) {
                    case QUERY -> { if (budget.attempts() == 0) return false; budget.requestQuery(at); }
                    case RESEND_ORIGINAL -> {
                        if (!budget.input().command().authorizedBy().equals(actors.actor().userId())) return false;
                        budget.retryNotFound(at); budgetExecution.requireSendSources(budget);
                    }
                    case RETRY_RESOURCES -> { value.retryResources(budget, at); sources.requireSupported(value.input().basis()); }
                    case CONFIRM_COMPLETED -> { value.confirmCompleted(budget, at); sources.requireSupported(value.input().basis()); }
                }
                return true;
            } catch (DomainException unavailable) { return false; }
        }).toList();
    }
    /** 安全结束候选仍须提交时在同一原报销锁下重新核对，页面能力不是授权令牌。 */
    public boolean canRetire(ExpenseResourceAdjustment value, BudgetConsumptionReversalOperation budget, Instant at) {
        try {
            value.input().basis().requireAuthorization(actors.actor().userId(), at);
            value.retire(budget.status() == BudgetConsumptionReversalOperation.Status.QUEUED ? budget.voidBeforeSend(at) : budget, at);
            return true;
        } catch (DomainException unavailable) { return false; }
    }
    private ExpenseResourceAdjustment requireAdjustment(UUID report, int round, UUID id, long version) {
        var value = adjustments.find(actors.actor().tenantId(), id).orElseThrow(ExpenseResourceAdjustmentActionService::conflict);
        if (!value.input().basis().reportId().equals(report) || value.input().basis().settlement().input().source().roundNo() != round
                || value.version() != version || value.status() == ExpenseResourceAdjustment.Status.RETIRED) throw conflict(); return value;
    }
    private BudgetConsumptionReversalOperation requireBudget(ExpenseResourceAdjustment value, long version) {
        var operation = budgets.find(actors.actor().tenantId(), value.id()).orElseThrow(ExpenseResourceAdjustmentActionService::conflict);
        if (operation.version() != version || !operation.input().equals(value.input().budget())) throw conflict(); return operation;
    }
    private static ActionReceipt receipt(ExpenseResourceAdjustment value, BudgetConsumptionReversalOperation budget, UUID event) {
        return new ActionReceipt(value.input().basis().reportId(), value.input().basis().settlement().input().source().roundNo(), value.id(), value.version(), budget.version(),
                value.status(), budget.status(), value.resourcesReversed(), event);
    }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Displayed expense adjustment or budget operation changed"); }
    /**
     * 查询、明确重发与本地恢复分别表达意图，不提供换编号自动重试。
     * @author owlzhangfq@gmail.com
     */
    public enum Action { QUERY, RESEND_ORIGINAL, RETRY_RESOURCES, CONFIRM_COMPLETED }
    /**
     * 业务与执行双重版本防止旧页面改变新的财务状态。
     * @author owlzhangfq@gmail.com
     */
    public record OperationInput(@Positive int roundNo, @Positive long applicationVersion, @Positive long businessVersion, @NotNull UUID adjustmentId,
            @Positive long adjustmentVersion, @Positive long budgetVersion, @NotNull Action action, @NotBlank @Size(max = 2000) String comment) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown expense adjustment operation field"); }
    }
    /**
     * 独立结束证明只包含原因，不接受任意预算或资源完成标记。
     * @author owlzhangfq@gmail.com
     */
    public record RetireInput(@Positive int roundNo, @Positive long applicationVersion, @Positive long businessVersion, @NotNull UUID adjustmentId,
            @Positive long adjustmentVersion, @Positive long budgetVersion, @NotBlank @Size(max = 128) @Pattern(regexp = "[^\\p{Cntrl}]+") String evidenceReference,
            @NotBlank @Size(max = 2000) @Pattern(regexp = "[^\\p{Cntrl}]+") String reason) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown expense adjustment retirement field"); }
    }
    /**
     * 人工动作回执显示原预算与本地资源两种状态。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ActionReceipt(UUID reportId, int roundNo, UUID adjustmentId, long adjustmentVersion, long budgetVersion,
            ExpenseResourceAdjustment.Status status, BudgetConsumptionReversalOperation.Status budgetStatus, boolean resourcesReversed, UUID auditEventId) { }
}
