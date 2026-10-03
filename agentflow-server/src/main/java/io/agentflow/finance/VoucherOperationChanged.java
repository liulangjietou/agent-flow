package io.agentflow.finance;

/**
 * 同事务凭证状态变化，后续结算据此处理过账或暂停新付款授权。
 * @author owlzhangfq@gmail.com
 */
public record VoucherOperationChanged(VoucherOperation previous, VoucherOperation current) { }
