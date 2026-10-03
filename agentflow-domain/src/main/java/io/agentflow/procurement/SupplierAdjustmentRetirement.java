package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 财务结束已证明无调整效果的原尝试，新记账日期只能在此依据持久保存后另行登记。
 * @author owlzhangfq@gmail.com
 */
public record SupplierAdjustmentRetirement(UUID operationId, UUID paymentId, long operationVersion,
        SupplierPayableAdjustmentOperation.RetirementBasis basis, String retiredBy, Instant retiredAt) {
    /** 结束固定原调整及银行身份、实际修订、独立人员与时间。 */
    public SupplierAdjustmentRetirement {
        if (operationId == null || paymentId == null || operationVersion < 1 || basis == null || retiredAt == null
                || StringUtils.isBlank(retiredBy) || retiredBy.length() > 128 || !retiredBy.equals(retiredBy.trim()) || retiredBy.chars().anyMatch(Character::isISOControl)) throw invalid();
    }
    /** 必须先停止队列和只读领取，不能拿到期或查无替代无副作用的实际依据。 */
    public static SupplierAdjustmentRetirement from(SupplierPayableAdjustmentOperation operation, String finance, Instant now) {
        if (operation == null || operation.retirementBasis() == null || operation.status() == SupplierPayableAdjustmentOperation.Status.QUEUED || operation.running()) throw unsafe();
        var value = new SupplierAdjustmentRetirement(operation.command().id(), operation.command().source().returns().request().command().id(), operation.version(), operation.retirementBasis(), finance, now);
        if (!value.matches(operation)) throw unsafe(); return value;
    }
    /** 保存和恢复均核对原调整修订，不能把其他尝试、申请人或原出纳作为结束依据。 */
    public boolean matches(SupplierPayableAdjustmentOperation operation) {
        if (operation == null || operation.retirementBasis() == null || operation.status() == SupplierPayableAdjustmentOperation.Status.QUEUED || operation.running()) return false;
        var command = operation.command(); var payment = command.source().returns().request().command();
        return operationId.equals(command.id()) && paymentId.equals(payment.id()) && operationVersion == operation.version()
                && basis == operation.retirementBasis() && !retiredAt.isBefore(operation.updatedAt())
                && !retiredBy.equals(payment.cashier()) && !retiredBy.equals(payment.holdCommand().authorization().source().reservation().source().employeeId());
    }
    private static DomainException invalid() { return new DomainException("INVALID_SUPPLIER_ADJUSTMENT_RETIREMENT", "Supplier adjustment retirement requires a named decision and exact original source revision"); }
    private static DomainException unsafe() { return new DomainException("SUPPLIER_ADJUSTMENT_RETIREMENT_UNSAFE", "Stopped original settlement must prove no external settlement before replacement"); }
    /** 日志只保留操作标识和修订，不展开人员或资金资料。 */
    @Override public String toString() { return "SupplierAdjustmentRetirement[operationId=" + operationId + ", operationVersion=" + operationVersion + "]"; }
}
