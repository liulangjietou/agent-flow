package io.agentflow.expense;

/**
 * 原查询或独立登记已持久保存的事实，订阅者与原状态共用事务。
 * @author owlzhangfq@gmail.com
 */
public record ExpensePaymentReturnChanged(ExpensePaymentReturnCheck current) { }
