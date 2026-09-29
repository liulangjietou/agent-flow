package io.agentflow.expense;

/**
 * 独立财务完成退回复核后，原报销只在其他资金及会计依据也成立时接续结算。
 * @author owlzhangfq@gmail.com
 */
public record ExpensePaymentReturnRegistered(ExpensePaymentReturn registration, ExpensePaymentReturns ledger) { }
