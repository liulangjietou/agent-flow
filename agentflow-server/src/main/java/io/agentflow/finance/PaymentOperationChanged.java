package io.agentflow.finance;

/**
 * 付款状态与结算消费者加入同一数据库事务，失败后通过原资金交易恢复。
 * @author owlzhangfq@gmail.com
 */
public record PaymentOperationChanged(PaymentOperation previous, PaymentOperation current) { }
