package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 独立调整原应付后的完整记账事实，原付款凭证不会被回款凭证替换。
 * @author owlzhangfq@gmail.com
 */
public record SupplierPayableAdjustmentObservation(UUID operationId, String commandDigest, Status status, long revision,
        Instant observedAt, Posting posting, Rejection rejection) {
    public SupplierPayableAdjustmentObservation {
        if (operationId == null || commandDigest == null || !commandDigest.matches("[a-f0-9]{64}") || status == null || observedAt == null
                || (status == Status.NOT_FOUND ? revision != 0 : revision < 1) || (status == Status.ADJUSTED) != (posting != null)
                || (status == Status.REJECTED) != (rejection != null) || posting != null && posting.adjustedAt().isAfter(observedAt)) throw invalid();
    }
    @Override public String toString() { return "SupplierPayableAdjustmentObservation[operationId=" + operationId + ", status=" + status + ", revision=" + revision + "]"; }

    /**
     * 每次调整只允许一个原子结果，查无和未知均不能释放本地占用。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { PENDING, ADJUSTED, REJECTED, NOT_FOUND }
    /**
     * 明确拒绝仍需区分已被其他调整消费的资金，不能把该类风险当作无影响证明。
     * @author owlzhangfq@gmail.com
     */
    public enum Rejection { ACCOUNTING_PERIOD_CLOSED, ORIGINAL_CHANGED, RETURNS_CHANGED, PREVIOUS_CHANGED, PAYABLE_CHANGED, ALREADY_ADJUSTED }

    /**
     * 核销前后余额与新增入款分录共同证明本次效果，累计数值保持可独立核对。
     * @author owlzhangfq@gmail.com
     */
    public record Posting(String adjustmentReference, String holdReference, String ledgerVersion, String recognitionVoucherReference,
            Money returnedAmount, Money totalReturned, Money netPaid, Money payableSettledBefore, Money payableSettledAfter,
            List<ReturnEntry> entries, String periodReference, LocalDate accountingDate, Instant adjustedAt) {
        public Posting {
            if (!reference(adjustmentReference) || !reference(holdReference) || !reference(ledgerVersion) || !reference(recognitionVoucherReference)
                    || returnedAmount == null || returnedAmount.value().signum() <= 0 || totalReturned == null || netPaid == null
                    || payableSettledBefore == null || payableSettledAfter == null || !reference(periodReference) || accountingDate == null || adjustedAt == null
                    || entries == null || entries.isEmpty() || entries.size() > SupplierPaymentReturnPort.MAX_RETURN_ENTRIES || entries.stream().anyMatch(Objects::isNull)) throw invalid();
            entries = List.copyOf(entries);
            for (var value : List.of(totalReturned, netPaid, payableSettledBefore, payableSettledAfter)) returnedAmount.sameCurrency(value);
            if (totalReturned.compareTo(returnedAmount) < 0) throw invalid();
            var sum = Money.zero(returnedAmount.currency()); var bank = new HashSet<String>(); var accounting = new HashSet<List<String>>();
            for (var entry : entries) {
                if (!bank.add(entry.transactionReference()) || !accounting.add(List.of(entry.voucherReference(), entry.entryReference()))
                        || entry.voucherReference().equals(recognitionVoucherReference)) throw invalid();
                sum = sum.plus(entry.amount());
            }
            if (!sum.equals(returnedAmount)) throw invalid();
        }
        @Override public String toString() { return "SupplierPayableAdjustmentPosting[accountingDate=" + accountingDate + "]"; }
    }

    /**
     * 公司实际入款与 ERP 恢复应付分录逐笔对应，金额不能由客户端声明。
     * @author owlzhangfq@gmail.com
     */
    public record ReturnEntry(String transactionReference, Money amount, String voucherReference, String entryReference) {
        public ReturnEntry {
            if (!reference(transactionReference) || amount == null || amount.value().signum() <= 0 || !reference(voucherReference) || !reference(entryReference)) throw invalid();
        }
        @Override public String toString() { return "SupplierPayableReturnEntry"; }
    }
    static boolean reference(String value) { return StringUtils.isNotBlank(value) && value.length() <= 128 && value.equals(value.trim()) && value.chars().noneMatch(Character::isISOControl); }
    private static DomainException invalid() { return new DomainException("INVALID_SUPPLIER_PAYABLE_ADJUSTMENT_OBSERVATION", "Supplier adjustment requires exact independent bank receipt postings and original payable balances"); }
}
