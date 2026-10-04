package io.agentflow.procurement;

/**
 * 来源聚合落库后发布，实际完成与安全结束不从操作状态推定。
 * @author owlzhangfq@gmail.com
 */
public sealed interface SupplierAdjustmentChanged {
    /**
     * 原应付调整准备的新修订。
     * @author owlzhangfq@gmail.com
     */
    record Preparation(SupplierAdjustmentPreparation current) implements SupplierAdjustmentChanged { }
    /**
     * 原 ERP 指令的新修订，可能尚未完成本地账务。
     * @author owlzhangfq@gmail.com
     */
    record Operation(SupplierPayableAdjustmentOperation current) implements SupplierAdjustmentChanged { }
    /**
     * 已持久保存的本地账务完成凭据。
     * @author owlzhangfq@gmail.com
     */
    record Completed(String tenantId, SupplierAdjustmentCompletion completion) implements SupplierAdjustmentChanged { }
    /**
     * 已保存的具名安全结束决定。
     * @author owlzhangfq@gmail.com
     */
    record Retired(String tenantId, SupplierAdjustmentRetirement retirement) implements SupplierAdjustmentChanged { }
}
