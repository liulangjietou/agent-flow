package io.agentflow.finance;

/**
 * 原准备状态已保存的事务内事实，通知失败与准备状态一同回滚。
 * @author owlzhangfq@gmail.com
 */
public record VoucherPreparationChanged(VoucherPreparation previous, VoucherPreparation current) { }
