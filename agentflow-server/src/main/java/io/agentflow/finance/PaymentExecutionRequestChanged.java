package io.agentflow.finance;

/**
 * 原出纳选择的检查进展在同一事务中投影通知，不代表付款命令已登记或银行已处理。
 * @author owlzhangfq@gmail.com
 */
public record PaymentExecutionRequestChanged(PaymentExecutionRequest previous, PaymentExecutionRequest current) { }
