package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceCatalog;
import java.time.Instant;
import org.apache.commons.lang3.StringUtils;

/**
 * 每轮审批固定申请内容、原供应商应付及其财务目的地；目录后续变化不能覆盖本轮依据。
 * @author owlzhangfq@gmail.com
 */
public record ProcurementPaymentRound(int roundNo, long submittedRequestVersion, String submittedBy, Instant submittedAt,
                                      ProcurementPaymentContent content, FinanceCatalog.LegalEntity legalEntity,
                                      String catalogVersion, String targetDigest, ProcurementPayablePort.Payable payable) {
    /** 恢复历史时按当时提交时刻核对证据，当前过期不删除已批准依据。 */
    public ProcurementPaymentRound {
        if (roundNo < 1 || submittedRequestVersion < 1 || StringUtils.isBlank(submittedBy) || submittedBy.length() > 128
                || submittedAt == null || content == null || legalEntity == null || !legalEntity.id().equals(content.legalEntityId())
                || StringUtils.isBlank(catalogVersion) || catalogVersion.length() > 128 || targetDigest == null || !targetDigest.matches("[a-f0-9]{64}")
                || payable == null || !payable.matches(content.payableRequest(submittedBy), submittedAt)) {
            throw new DomainException("INVALID_PROCUREMENT_PAYMENT_ROUND", "Procurement round must preserve current matching evidence for the original applicant and payable");
        }
        if (!content.amount().currency().equals(legalEntity.baseCurrency()) || !payable.gross().currency().equals(legalEntity.baseCurrency())) {
            throw new DomainException("PROCUREMENT_BASE_CURRENCY_REQUIRED", "Procurement payment must use the legal entity base currency");
        }
        if (content.amount().compareTo(payable.outstanding()) > 0) {
            throw new DomainException("PROCUREMENT_AMOUNT_EXCEEDS_PAYABLE", "Requested payment exceeds the verified outstanding payable");
        }
    }

    /** 避免默认日志展开合同、发票、供应商与账户资料。 */
    @Override public String toString() { return "ProcurementPaymentRound[roundNo=" + roundNo + ", submittedRequestVersion=" + submittedRequestVersion + "]"; }
}
