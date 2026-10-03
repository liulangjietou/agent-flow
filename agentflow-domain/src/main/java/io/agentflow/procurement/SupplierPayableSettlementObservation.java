package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 原 ERP 结算命令的版本化结果，只有完整核销和付款凭证事实才表示应付已结。
 * @author owlzhangfq@gmail.com
 */
public record SupplierPayableSettlementObservation(UUID operationId, String commandDigest, Status status, Long revision,
        Instant observedAt, Posting posting, Rejection rejection) {
    /** 未知、处理中及查无不得携带成功凭据，终态拒绝必须有原命令身份。 */
    public SupplierPayableSettlementObservation {
        if (operationId == null || commandDigest == null || !commandDigest.matches("[a-f0-9]{64}") || status == null
                || revision == null || (status == Status.NOT_FOUND ? revision != 0 : revision < 1) || observedAt == null
                || (status == Status.SETTLED) != (posting != null) || (status == Status.REJECTED) != (rejection != null)
                || posting != null && posting.settledAt().isAfter(observedAt)) throw invalid();
    }
    /** 日志只保留原业务号、结果和修订。 */
    @Override public String toString() { return "SupplierPayableSettlementObservation[operationId=" + operationId + ", status=" + status + ", revision=" + revision + "]"; }

    /**
     * 结算消费原预留；后续冲销必须使用独立调整，不覆盖此原始结算事实。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { PENDING, SETTLED, REJECTED, NOT_FOUND }
    /**
     * 只有 ERP 对本命令确认未结算的终态事实可以使用这些原因。
     * @author owlzhangfq@gmail.com
     */
    public enum Rejection { ACCOUNTING_PERIOD_CLOSED, HOLD_UNAVAILABLE, HOLD_CHANGED, PAYMENT_UNAVAILABLE, PAYMENT_CHANGED, PAYABLE_CHANGED, ALREADY_SETTLED }

    /**
     * 核销前后余额、原预留、银行回单和 ERP 付款凭证一起证明精确结算。
     * @author owlzhangfq@gmail.com
     */
    public record Posting(String settlementReference, String holdReference, String ledgerVersion, Money settledAmount, Money settledBefore,
            Money settledAfter, String bankPaymentReference, String bankReceiptReference, String voucherReference, String periodReference,
            LocalDate accountingDate, Instant settledAt) {
        /** 金额必须为正且等于已结余额增量，任何一项原始凭据缺失都不能表示核销完成。 */
        public Posting {
            if (invalidReference(settlementReference) || invalidReference(holdReference) || invalidReference(ledgerVersion)
                    || invalidReference(bankPaymentReference) || invalidReference(bankReceiptReference) || invalidReference(voucherReference) || invalidReference(periodReference)
                    || settledAmount == null || settledBefore == null || settledAfter == null || settledAmount.value().signum() <= 0 || settledBefore.value().signum() < 0
                    || !settledAmount.currency().equals(settledBefore.currency()) || !settledAmount.currency().equals(settledAfter.currency())
                    || !settledBefore.plus(settledAmount).equals(settledAfter) || accountingDate == null || settledAt == null) throw invalid();
        }
        /** 不在日志展开会计或资金凭据。 */
        @Override public String toString() { return "SupplierPayableSettlementPosting[accountingDate=" + accountingDate + "]"; }
    }
    private static boolean invalidReference(String value) { return StringUtils.isBlank(value) || value.length() > 128 || !value.equals(value.trim()) || value.chars().anyMatch(Character::isISOControl); }
    private static DomainException invalid() { return new DomainException("INVALID_SUPPLIER_PAYABLE_SETTLEMENT_OBSERVATION", "Payable settlement requires exact balance changes and complete original posting evidence"); }
}
