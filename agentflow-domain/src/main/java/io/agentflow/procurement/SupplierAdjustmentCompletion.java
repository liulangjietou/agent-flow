package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import java.time.Instant;

/**
 * 独立调整完成的不可变证明，关联实际成功修订、之后的银行原件及精确账本前后状态。
 * @author owlzhangfq@gmail.com
 */
public record SupplierAdjustmentCompletion(SupplierPayableAdjustmentOperation operation, SupplierPaymentOperation bank,
        SupplierPaymentReturnPort.Receipt receipt, SupplierPaymentReturns before, SupplierPaymentReturns after) {
    /** 恢复时回放领域状态变化，时间、资金归属或完成版本任一不一致都拒绝。 */
    public SupplierAdjustmentCompletion {
        if (operation == null || bank == null || receipt == null || before == null || after == null
                || !receipt.matchesCurrentBank(bank) || after.updatedAt().isBefore(bank.updatedAt())
                || !before.account(operation, receipt, after.updatedAt()).equals(after)) throw invalid();
    }

    /** 应用编排先核对实际持久修订，再以同一证明原子保存账本、分录和原占用完成。 */
    public static SupplierAdjustmentCompletion from(SupplierPayableAdjustmentOperation operation, SupplierPaymentOperation bank,
            SupplierPaymentReturns before, SupplierPaymentReturnPort.Receipt receipt, Instant now) {
        return new SupplierAdjustmentCompletion(operation, bank, receipt, before, before.account(operation, receipt, now));
    }

    /** 完成时间来自精确后继账本，数据库时间列只用于索引和约束。 */
    public Instant completedAt() { return after.updatedAt(); }
    private static DomainException invalid() { return new DomainException("INVALID_SUPPLIER_ADJUSTMENT_COMPLETION", "Supplier adjustment completion must preserve actual bank evidence and exact return ledger transition"); }
    @Override public String toString() { return "SupplierAdjustmentCompletion[operationId=" + operation.command().id() + ", version=" + operation.version() + "]"; }
}
