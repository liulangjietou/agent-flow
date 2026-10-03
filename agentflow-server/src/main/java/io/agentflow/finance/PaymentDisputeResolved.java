package io.agentflow.finance;

/**
 * 只有明确持久化的裁决才触发资金解冻，普通成功查询不会发布本事件。
 * @author owlzhangfq@gmail.com
 */
public record PaymentDisputeResolved(PaymentOperation payment, PaymentDisputeResolution resolution) { }
