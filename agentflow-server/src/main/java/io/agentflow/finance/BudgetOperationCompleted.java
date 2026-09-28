package io.agentflow.finance;

/**
 * 本地预算任务和台账已确认的事务内事件，业务后续状态必须与该结果共同提交。
 * @author owlzhangfq@gmail.com
 */
public record BudgetOperationCompleted(BudgetOperation operation) { }
