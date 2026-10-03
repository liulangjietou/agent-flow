package io.agentflow.expense;

/**
 * 部分调整已保存的事实；预算、挂账和资源各自提交，消息不触发财务操作。
 * @author owlzhangfq@gmail.com
 */
public final class ExpensePartialAdjustmentChanged {
    private ExpensePartialAdjustmentChanged() { }
    /**
     * 原单侧准备变化。
     * @author owlzhangfq@gmail.com
     */
    public record Preparation(ExpensePartialAdjustmentPreparation current) { }
    /**
     * 原预算操作变化。
     * @author owlzhangfq@gmail.com
     */
    public record Budget(ExpensePartialAdjustment current) { }
    /**
     * 原挂账操作变化。
     * @author owlzhangfq@gmail.com
     */
    public record Accrual(ExpensePartialAdjustment current) { }
    /**
     * 实际资源完成或受阻。
     * @author owlzhangfq@gmail.com
     */
    public record Resources(ExpensePartialAdjustment current) { }
    /**
     * 持久的无副作用结束。
     * @author owlzhangfq@gmail.com
     */
    public record Retired(ExpensePartialAdjustment current) { }
    /**
     * 指定原操作的实际争议裁决。
     * @author owlzhangfq@gmail.com
     */
    public record Resolved(ExpensePartialAdjustment current, ExpensePartialDisputeResolution decision) { }
}
