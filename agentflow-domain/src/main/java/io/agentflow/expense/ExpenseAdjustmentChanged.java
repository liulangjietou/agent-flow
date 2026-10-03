package io.agentflow.expense;

import io.agentflow.finance.BudgetConsumptionReversalOperation;

/**
 * 已保存的整笔报销调整事实，预算与资源分别提交，安全结束另有持久证明。
 * @author owlzhangfq@gmail.com
 */
public final class ExpenseAdjustmentChanged {
    private ExpenseAdjustmentChanged() { }
    /**
     * 原只读准备发生变化。
     * @author owlzhangfq@gmail.com
     */
    public record Preparation(ExpenseResourceAdjustmentPreparation current) { }
    /**
     * 原预算冲正指令发生变化。
     * @author owlzhangfq@gmail.com
     */
    public record Budget(BudgetConsumptionReversalOperation current) { }
    /**
     * 本地资源执行实际完成或受阻。
     * @author owlzhangfq@gmail.com
     */
    public record Resources(ExpenseResourceAdjustment current) { }
    /**
     * 已持久登记的无副作用结束证明。
     * @author owlzhangfq@gmail.com
     */
    public record Retired(ExpenseResourceAdjustmentRetirement retirement) { }
}
