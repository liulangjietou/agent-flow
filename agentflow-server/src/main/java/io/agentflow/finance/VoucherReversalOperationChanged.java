package io.agentflow.finance;

/**
 * 独立冲销原命令的状态变化，不取代原凭证查询或安全结束事件。
 * @author owlzhangfq@gmail.com
 */
public record VoucherReversalOperationChanged(VoucherReversalOperation previous, VoucherReversalOperation current) { }
