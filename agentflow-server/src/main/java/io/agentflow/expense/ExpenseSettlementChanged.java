package io.agentflow.expense;

/**
 * 原结算和修订均已保存的事实，不代表归档或后续资源调整完成。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseSettlementChanged(ExpenseSettlement previous, ExpenseSettlement current) { }
