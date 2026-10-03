package io.agentflow.finance;

/**
 * 预算状态与通知同事务发布，非终态不能冒充业务完成事件。
 * @author owlzhangfq@gmail.com
 */
public record BudgetOperationChanged(BudgetOperation previous, BudgetOperation current) { }
