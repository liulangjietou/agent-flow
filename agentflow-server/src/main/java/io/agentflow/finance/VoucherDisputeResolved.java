package io.agentflow.finance;

/**
 * 只有持久裁决才允许下游复核恢复，原操作查询不发布此事件。
 * @author owlzhangfq@gmail.com
 */
public record VoucherDisputeResolved(VoucherOperation voucher, VoucherDisputeResolution resolution) { }
