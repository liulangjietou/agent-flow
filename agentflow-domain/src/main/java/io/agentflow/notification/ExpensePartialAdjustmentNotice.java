package io.agentflow.notification;

import io.agentflow.expense.ExpensePartialAdjustment;
import io.agentflow.expense.ExpensePartialAdjustmentPreparation;
import io.agentflow.finance.BudgetConsumptionReductionOperation;
import io.agentflow.finance.ExpenseAccrualReductionOperation;
import java.util.Optional;
import java.util.UUID;

/**
 * 部分调整消息绑定具体准备、独立命令或裁决，不能跟随根聚合的后来授权换号。
 * @author owlzhangfq@gmail.com
 */
public enum ExpensePartialAdjustmentNotice {
    PREPARATION_UNAVAILABLE(SourceType.PREPARATION, "本侧调整依据读取失败，尚未授权本侧命令。"),
    PREPARATION_VOIDED(SourceType.PREPARATION, "本次准备的来源或办理资格变化，已停止。"),
    BUDGET_UNKNOWN(SourceType.BUDGET, "原预算调减结果尚不明确。"),
    BUDGET_NOT_FOUND(SourceType.BUDGET, "原预算调减命令暂未查到。"),
    BUDGET_REJECTED(SourceType.BUDGET, "原预算调减已明确拒绝。"),
    BUDGET_EXPIRED(SourceType.BUDGET, "原预算调减发送授权已到期。"),
    BUDGET_VOIDED(SourceType.BUDGET, "原预算调减命令已停止。"),
    BUDGET_RECONCILING(SourceType.BUDGET, "原预算调减回执存在争议。"),
    BUDGET_APPLIED(SourceType.BUDGET, "原预算调减已确认，资源完成需单独核对。"),
    ACCRUAL_UNKNOWN(SourceType.ACCRUAL, "原挂账调整结果尚不明确。"),
    ACCRUAL_NOT_FOUND(SourceType.ACCRUAL, "原挂账调整命令暂未查到。"),
    ACCRUAL_FAILED(SourceType.ACCRUAL, "原挂账调整已明确拒绝。"),
    ACCRUAL_EXPIRED(SourceType.ACCRUAL, "原挂账调整发送授权已到期。"),
    ACCRUAL_VOIDED(SourceType.ACCRUAL, "原挂账调整命令已停止。"),
    ACCRUAL_RECONCILING(SourceType.ACCRUAL, "原挂账调整回执存在争议。"),
    ACCRUAL_POSTED(SourceType.ACCRUAL, "原挂账调整已确认，资源完成需单独核对。"),
    RESOURCES_BLOCKED(SourceType.ADJUSTMENT, "本地资源调整受阻，两侧财务事实保留。"),
    COMPLETED(SourceType.ADJUSTMENT, "本次两侧财务结果和资源调整均已完成。"),
    RETIRED(SourceType.ADJUSTMENT, "本次调整已依据无副作用证明安全结束。"),
    DISPUTE_RESOLVED(SourceType.DISPUTE, "本次原操作争议已登记明确裁决。");
    private static final String PREFIX = "expense-partial-adjustment:";
    private final SourceType sourceType;
    private final String content;
    ExpensePartialAdjustmentNotice(SourceType sourceType, String content) { this.sourceType = sourceType; this.content = content; }
    public SourceType sourceType() { return sourceType; }
    public String content() { return content; }
    public InboxMessage.Kind kind() { return switch (this) {
        case BUDGET_APPLIED, ACCRUAL_POSTED, COMPLETED, RETIRED, DISPUTE_RESOLVED -> InboxMessage.Kind.EXPENSE_PARTIAL_ADJUSTMENT_RESULT;
        default -> InboxMessage.Kind.EXPENSE_PARTIAL_ADJUSTMENT_ATTENTION;
    }; }
    public String title() { return kind() == InboxMessage.Kind.EXPENSE_PARTIAL_ADJUSTMENT_RESULT ? "报销部分调整结果更新" : "报销部分调整需核对"; }
    /** 准备就绪与正常排队保持安静。 */
    public static Optional<ExpensePartialAdjustmentNotice> from(ExpensePartialAdjustmentPreparation value) {
        return Optional.ofNullable(switch (value.status()) { case UNAVAILABLE -> PREPARATION_UNAVAILABLE; case VOIDED -> PREPARATION_VOIDED; default -> null; });
    }
    /** 原系统处理中和主动重查不制造异常通知。 */
    public static Optional<ExpensePartialAdjustmentNotice> from(BudgetConsumptionReductionOperation value) {
        if (value == null) return Optional.empty();
        return Optional.ofNullable(switch (value.status()) {
            case UNKNOWN -> value.failure() == null || value.failure() == BudgetConsumptionReductionOperation.Failure.RECHECK_REQUESTED ? null : BUDGET_UNKNOWN;
            case NOT_FOUND -> BUDGET_NOT_FOUND;
            case REJECTED -> BUDGET_REJECTED;
            case EXPIRED -> BUDGET_EXPIRED;
            case VOIDED -> BUDGET_VOIDED;
            case RECONCILING -> BUDGET_RECONCILING;
            case APPLIED -> BUDGET_APPLIED;
            default -> null;
        });
    }
    /** 原系统处理中和主动重查不制造异常通知。 */
    public static Optional<ExpensePartialAdjustmentNotice> from(ExpenseAccrualReductionOperation value) {
        if (value == null) return Optional.empty();
        return Optional.ofNullable(switch (value.status()) {
            case UNKNOWN -> value.failure() == null || value.failure() == ExpenseAccrualReductionOperation.Failure.RECHECK_REQUESTED ? null : ACCRUAL_UNKNOWN;
            case NOT_FOUND -> ACCRUAL_NOT_FOUND;
            case FAILED -> ACCRUAL_FAILED;
            case EXPIRED -> ACCRUAL_EXPIRED;
            case VOIDED -> ACCRUAL_VOIDED;
            case RECONCILING -> ACCRUAL_RECONCILING;
            case POSTED -> ACCRUAL_POSTED;
            default -> null;
        });
    }
    /** 资源通知只由资源执行入口发出，财务侧重查不冒充本地资源失败。 */
    public static Optional<ExpensePartialAdjustmentNotice> from(ExpensePartialAdjustment value) {
        return Optional.ofNullable(switch (value.status()) {
            case APPLIED -> COMPLETED;
            case REVIEW_REQUIRED -> value.issue() == null || value.issue().startsWith("BUDGET_") || value.issue().startsWith("ACCRUAL_") ? null : RESOURCES_BLOCKED;
            default -> null;
        });
    }
    /** 相同具体来源与事实只通知一次。 */
    public String eventKey(UUID adjustmentId, UUID sourceId) { return PREFIX + adjustmentId + ":" + sourceType + ":" + sourceId + ":" + name(); }
    /** 来源类型、事实与规范 UUID 必须共同一致。 */
    public static Optional<Source> source(String key) {
        if (key == null || !key.startsWith(PREFIX)) return Optional.empty();
        var parts = key.substring(PREFIX.length()).split(":", -1); if (parts.length != 4) return Optional.empty();
        try {
            var id = UUID.fromString(parts[0]); var type = SourceType.valueOf(parts[1]); var source = UUID.fromString(parts[2]); var fact = valueOf(parts[3]);
            return !id.toString().equals(parts[0]) || !source.toString().equals(parts[2]) || fact.sourceType != type
                    || type == SourceType.ADJUSTMENT && !id.equals(source) ? Optional.empty() : Optional.of(new Source(id, type, source, fact));
        } catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }
    /**
     * 持久来源的封闭集合。
     * @author owlzhangfq@gmail.com
     */
    public enum SourceType { PREPARATION, BUDGET, ACCRUAL, ADJUSTMENT, DISPUTE }
    /**
     * 消息原身份，不接受客户端替换。
     * @author owlzhangfq@gmail.com
     */
    public record Source(UUID adjustmentId, SourceType sourceType, UUID sourceId, ExpensePartialAdjustmentNotice notice) { }
}
