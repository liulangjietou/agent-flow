package io.agentflow.finance;

/**
 * 独立财务结束原冲销后只恢复对应凭证来源，其余资金与凭证争议保持。
 * @author owlzhangfq@gmail.com
 */
public record VoucherReversalRetired(VoucherOperation voucher, VoucherReversalRetirement retirement) { }
