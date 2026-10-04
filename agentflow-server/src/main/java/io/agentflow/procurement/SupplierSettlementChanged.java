package io.agentflow.procurement;

/**
 * 来源聚合落库后发布，实际完成与安全结束不从操作状态推定。
 * @author owlzhangfq@gmail.com
 */
public sealed interface SupplierSettlementChanged {
    /**
     * 原结算准备的新修订。
     * @author owlzhangfq@gmail.com
     */
    record Preparation(SupplierSettlementPreparation current) implements SupplierSettlementChanged { }
    /**
     * 原 ERP 指令的新修订，可能尚未完成本地占用。
     * @author owlzhangfq@gmail.com
     */
    record Operation(SupplierPayableSettlementOperation current) implements SupplierSettlementChanged { }
    /**
     * 已持久保存的本地占用完成凭据。
     * @author owlzhangfq@gmail.com
     */
    record Completed(String tenantId, ProcurementPayableReservation.Settlement completion) implements SupplierSettlementChanged { }
    /**
     * 已保存的具名安全结束决定。
     * @author owlzhangfq@gmail.com
     */
    record Retired(String tenantId, SupplierSettlementRetirement retirement) implements SupplierSettlementChanged { }
}
