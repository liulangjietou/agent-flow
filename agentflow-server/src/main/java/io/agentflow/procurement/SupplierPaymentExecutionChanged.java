package io.agentflow.procurement;

/**
 * 原供应商付款状态在保存事务内发布，通知与金融事实同时提交或回滚。
 * @author owlzhangfq@gmail.com
 */
public record SupplierPaymentExecutionChanged(SupplierPaymentExecutionRequest previous, SupplierPaymentExecutionRequest current) { }
