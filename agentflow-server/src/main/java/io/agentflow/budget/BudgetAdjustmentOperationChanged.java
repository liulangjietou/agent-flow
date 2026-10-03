package io.agentflow.budget;

/**
 * 原预算调整状态已保存，通知与财务动作共同提交或回滚。
 * @author owlzhangfq@gmail.com
 */
public record BudgetAdjustmentOperationChanged(BudgetAdjustmentOperation current, BudgetAdjustmentRetirement retirement) { }
