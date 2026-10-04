package io.agentflow.notification;

import io.agentflow.expense.ExpenseResourceAdjustment;
import io.agentflow.expense.ExpenseResourceAdjustmentPreparation;
import io.agentflow.finance.BudgetConsumptionReversalOperation;
import java.util.Optional;
import java.util.UUID;

/**
 * 整笔报销调整的原编号与事实去重，预算确认不能替代资源完成证明。
 * @author owlzhangfq@gmail.com
 */
public enum ExpenseAdjustmentNotice {
    PREPARATION_UNAVAILABLE("本次调整依据读取失败，尚未授权预算冲正。"),
    PREPARATION_VOIDED("原依据或办理资格已变化，本次准备已停止。"),
    BUDGET_UNKNOWN("原预算冲正结果暂不明确，需要继续核对原编号。"),
    BUDGET_NOT_FOUND("原预算冲正暂未查到，不能据此重新建立调整。"),
    BUDGET_REJECTED("原预算系统明确拒绝冲正，后续处理需核对原记录。"),
    BUDGET_EXPIRED("原预算发送授权已到期，本次尚未完成资源冲回。"),
    BUDGET_VOIDED("原预算发送已停止，安全结束仍需独立确认。"),
    BUDGET_RECONCILING("原预算回执存在矛盾，原结果与冲突结果分别保留。"),
    BUDGET_APPLIED("预算冲正已确认；本地资源是否完成需单独核对。"),
    RESOURCES_BLOCKED("本地资源冲回遇到问题，已发生的预算事实保持。"),
    COMPLETED("原预算冲正和本地资源恢复已记录完成。"),
    RETIRED("本次调整已依据无副作用证明安全结束。");

    private static final String PREFIX = "expense-adjustment:";
    private final String content;
    ExpenseAdjustmentNotice(String content) { this.content = content; }
    public String content() { return content; }
    public InboxMessage.Kind kind() { return this == BUDGET_APPLIED || this == COMPLETED || this == RETIRED ? InboxMessage.Kind.EXPENSE_ADJUSTMENT_RESULT : InboxMessage.Kind.EXPENSE_ADJUSTMENT_ATTENTION; }
    public String title() { return kind() == InboxMessage.Kind.EXPENSE_ADJUSTMENT_RESULT ? "报销资源调整结果更新" : "报销资源调整需核对"; }
    /** 正常排队与准备就绪不生成异常。 */
    public static Optional<ExpenseAdjustmentNotice> from(ExpenseResourceAdjustmentPreparation value) {
        return Optional.ofNullable(switch (value.status()) { case UNAVAILABLE -> PREPARATION_UNAVAILABLE; case VOIDED -> PREPARATION_VOIDED; default -> null; });
    }
    /** 原系统处理中和主动重查不被解释成失联。 */
    public static Optional<ExpenseAdjustmentNotice> from(BudgetConsumptionReversalOperation value) {
        return Optional.ofNullable(switch (value.status()) {
            case UNKNOWN -> value.failure() == null || value.failure() == BudgetConsumptionReversalOperation.Failure.RECHECK_REQUESTED ? null : BUDGET_UNKNOWN;
            case NOT_FOUND -> BUDGET_NOT_FOUND;
            case REJECTED -> BUDGET_REJECTED;
            case EXPIRED -> BUDGET_EXPIRED;
            case VOIDED -> BUDGET_VOIDED;
            case RECONCILING -> BUDGET_RECONCILING;
            case APPLIED -> BUDGET_APPLIED;
            default -> null;
        });
    }
    /** 预算问题已经由原预算通知表达；主动重查不生成资源失败消息。 */
    public static Optional<ExpenseAdjustmentNotice> from(ExpenseResourceAdjustment value) {
        return Optional.ofNullable(switch (value.status()) {
            case APPLIED -> COMPLETED;
            case REVIEW_REQUIRED -> value.issue().startsWith("BUDGET_") ? null : RESOURCES_BLOCKED;
            default -> null;
        });
    }
    /** 相同原编号的同类事实只通知一次，重新准备使用独立编号。 */
    public String eventKey(UUID id) { return PREFIX + id + ":" + name(); }
    /** 仅解析规范原编号及封闭事实集合，客户端不能替换消息来源。 */
    public static Optional<Source> source(String key) {
        if (key == null || !key.startsWith(PREFIX)) return Optional.empty();
        var parts = key.substring(PREFIX.length()).split(":", -1); if (parts.length != 2) return Optional.empty();
        try { var id = UUID.fromString(parts[0]); return id.toString().equals(parts[0]) ? Optional.of(new Source(id, valueOf(parts[1]))) : Optional.empty(); }
        catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }
    /**
     * 原准备、预算及资源共享调整编号，事实种类区分实际来源。
     * @author owlzhangfq@gmail.com
     */
    public record Source(UUID adjustmentId, ExpenseAdjustmentNotice notice) { }
}
