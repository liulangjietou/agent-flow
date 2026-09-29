package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.util.UUID;

/**
 * 独立财务采纳真实 ERP 反向凭证的不可变记录，不承担资金或报销资源调整。
 * @author owlzhangfq@gmail.com
 */
public record VoucherReversalRecord(UUID id, String tenantId, UUID checkId, long operationVersion, VoucherReversalPort.Receipt receipt,
        String recordedBy, Instant recordedAt, String evidenceReference, String comment) {
    /** 原申请人与原付款出纳均不能为自己的原凭证登记冲销，证据不能过期或手工补金额。 */
    public VoucherReversalRecord {
        if (id == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64 || checkId == null || operationVersion < 1 || receipt == null
                || !tenantId.equals(receipt.request().command().tenantId()) || receipt.status() != VoucherReversalPort.Status.VERIFIED
                || StringUtils.isBlank(recordedBy) || recordedBy.length() > 128 || !VoucherDisputeResolution.independent(receipt.request().command(), recordedBy)
                || !receipt.matches(receipt.request(), recordedAt) || StringUtils.isBlank(evidenceReference) || evidenceReference.length() > 128
                || StringUtils.isBlank(comment) || comment.length() > 2000) throw new DomainException("INVALID_VOUCHER_REVERSAL_RECORD", "Voucher reversal recording requires independent finance and complete fresh original evidence");
    }
    public UUID operationId() { return receipt.request().command().id(); }
    public UUID legalEntityId() { return receipt.request().command().legalEntityId(); }
    @Override public String toString() { return "VoucherReversalRecord[id=" + id + ", operationId=" + operationId() + "]"; }
}
