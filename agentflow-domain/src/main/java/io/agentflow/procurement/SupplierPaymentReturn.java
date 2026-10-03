package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 独立财务采纳原银行累计退回，原 ERP 核销与本地完成不受此决定改写。
 * @author owlzhangfq@gmail.com
 */
public record SupplierPaymentReturn(UUID id, String tenantId, UUID checkId, SupplierPaymentReturnPort.Receipt receipt,
                                    String registeredBy, Instant registeredAt, String evidenceReference, String reason) {
    /** 仅采用近期原件，申请人和原出纳不能自行登记回款。 */
    public SupplierPaymentReturn {
        if (id == null || checkId == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64 || receipt == null
                || !tenantId.equals(receipt.request().command().tenantId()) || receipt.status() == SupplierPaymentReturnPort.Status.UNRESOLVED
                || !receipt.matches(receipt.request(), registeredAt) || invalidReference(registeredBy)
                || registeredBy.equals(receipt.request().command().cashier())
                || registeredBy.equals(receipt.request().command().holdCommand().authorization().source().reservation().source().employeeId())
                || StringUtils.isBlank(evidenceReference) || evidenceReference.length() > 128 || evidenceReference.chars().anyMatch(Character::isISOControl)
                || StringUtils.isBlank(reason) || reason.length() > 2000) throw invalid();
        evidenceReference = evidenceReference.trim(); reason = reason.trim();
    }
    private static boolean invalidReference(String value) {
        return StringUtils.isBlank(value) || value.length() > 128 || !value.equals(value.trim()) || value.chars().anyMatch(Character::isISOControl);
    }
    private static DomainException invalid() { return new DomainException("INVALID_SUPPLIER_PAYMENT_RETURN", "Supplier return registration requires fresh bank evidence and an independent finance actor"); }
    @Override public String toString() { return "SupplierPaymentReturn[id=" + id + ", paymentId=" + receipt.request().command().id() + "]"; }
}
