package io.agentflow.finance;

/**
 * 冲销准备状态与最小通知同事务保存，准备结果不能冒充 ERP 写入。
 * @author owlzhangfq@gmail.com
 */
public record VoucherReversalPreparationChanged(VoucherReversalPreparation previous, VoucherReversalPreparation current) { }
