package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.AccountingPeriodPort;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 财务明确授权的独立 ERP 调整，固定原资金及新增入款，不修改原银行或核销命令。
 * @author owlzhangfq@gmail.com
 */
public record SupplierPayableAdjustmentCommand(UUID id, SupplierPayableAdjustmentSource source,
        AccountingPeriodPort.OpenPeriod period, String financeActor, Instant registeredAt) {
    public SupplierPayableAdjustmentCommand {
        if (source == null || period == null) throw invalid();
        source.requireIntent(id, financeActor, period.request().accountingDate(), registeredAt);
        var payment = source.returns().request().command();
        if (!period.matches(new AccountingPeriodPort.Request(payment.payee().legalEntityId(), payment.amount().currency(), period.request().accountingDate()), registeredAt)
                || !period.observedAt().plus(ProcurementPayablePort.MAX_EVIDENCE_AGE).isAfter(registeredAt)) throw invalid();
    }
    public String tenantId() { return source.returns().request().command().tenantId(); }
    public String targetDigest() { return source.returns().request().command().targetDigest(); }

    /** 摘要绑定全部已登记原件、首次核销、上次调整和本次期间，重试只复用该固定命令。 */
    public String digest() {
        try {
            var digest = MessageDigest.getInstance("SHA-256"); var ledger = source.returns(); var original = ledger.request().original();
            add(digest, "agentflow-supplier-payable-adjustment-1", id, ledger.request().command().digest(), ledger.version(), ledger.reviewRequired(), ledger.createdAt(), ledger.updatedAt(), ledger.entries().size(),
                    original.revision(), original.observedAt(), original.paymentReference(), original.receiptReference(), original.completedAt(), original.accountDigest(), original.paidAmount());
            for (var entry : ledger.entries()) addReturn(digest, entry);
            if (ledger.accounting() != null) add(digest, "accounted", ledger.accounting().operationId(), ledger.accounting().operationVersion(), ledger.accounting().entryCount(), ledger.accounting().accountedAt());
            var settlement = source.settlement(); add(digest, settlement != null);
            if (settlement != null) {
                var observed = settlement.observation(); var posting = observed.posting();
                add(digest, settlement.version(), settlement.command().digest(), observed.revision(), observed.observedAt(), posting.settlementReference(), posting.holdReference(),
                        posting.ledgerVersion(), posting.settledAmount(), posting.settledBefore(), posting.settledAfter(), posting.bankPaymentReference(), posting.bankReceiptReference(),
                        posting.voucherReference(), posting.periodReference(), posting.accountingDate(), posting.settledAt());
            }
            var previous = source.previous(); add(digest, previous != null);
            if (previous != null) {
                var observed = previous.observation(); var posting = observed.posting();
                add(digest, previous.paymentId(), previous.paymentDigest(), previous.originalSettlementId(), previous.version(), previous.entries().size(), observed.operationId(), observed.commandDigest(), observed.revision(), observed.observedAt());
                for (var entry : previous.entries()) addReturn(digest, entry);
                add(digest, posting.adjustmentReference(), posting.holdReference(), posting.ledgerVersion(), posting.recognitionVoucherReference(), posting.returnedAmount(), posting.totalReturned(),
                        posting.netPaid(), posting.payableSettledBefore(), posting.payableSettledAfter(), posting.periodReference(), posting.accountingDate(), posting.adjustedAt(), posting.entries().size());
                for (var entry : posting.entries()) add(digest, entry.transactionReference(), entry.amount(), entry.voucherReference(), entry.entryReference());
            }
            add(digest, period.request().legalEntityId(), period.request().currency(), period.request().accountingDate(), period.periodReference(), period.sourceVersion(),
                    period.startsOn(), period.endsOn(), period.observedAt(), period.validUntil(), financeActor, registeredAt);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }

    /** ERP 必须精确登记每笔新增入款；首次未核销调整的付款确认与退回恢复在同一事务完成。 */
    public boolean matches(SupplierPayableAdjustmentObservation value, boolean queried, Instant now) {
        if (value == null || now == null || !value.operationId().equals(id) || !value.commandDigest().equals(digest())
                || value.observedAt().isBefore(registeredAt) || value.observedAt().isAfter(now)
                || value.status() == SupplierPayableAdjustmentObservation.Status.NOT_FOUND && !queried) return false;
        if (value.status() != SupplierPayableAdjustmentObservation.Status.ADJUSTED) return true;
        var posting = value.posting(); var payment = source.returns().request().command();
        var accrual = payment.holdCommand().authorization().payable().accrualVoucherReference();
        if (!posting.holdReference().equals(payment.held().holdReference()) || !posting.returnedAmount().equals(source.newReturned())
                || !posting.totalReturned().equals(source.returns().totalReturned()) || !posting.netPaid().equals(source.netPaid())
                || !posting.periodReference().equals(period.periodReference()) || !posting.accountingDate().equals(period.request().accountingDate())
                || posting.adjustedAt().isBefore(registeredAt) || posting.entries().size() != source.newReturns().size()
                || accrual.equals(posting.recognitionVoucherReference()) || posting.entries().stream().anyMatch(entry -> accrual.equals(entry.voucherReference()))) return false;
        for (var entry : source.newReturns()) if (posting.entries().stream().noneMatch(line -> line.transactionReference().equals(entry.proof().transactionReference()) && line.amount().equals(entry.proof().amount()))) return false;
        var recognized = source.previous() != null ? source.previous().observation().posting().recognitionVoucherReference()
                : source.settlement() != null ? source.settlement().observation().posting().voucherReference() : null;
        if (recognized != null && !recognized.equals(posting.recognitionVoucherReference())
                || source.previous() != null && (source.previous().observation().posting().adjustmentReference().equals(posting.adjustmentReference())
                    || posting.entries().stream().anyMatch(entry -> source.previous().observation().posting().entries().stream()
                        .anyMatch(old -> old.voucherReference().equals(entry.voucherReference()) && old.entryReference().equals(entry.entryReference()))))) return false;
        var before = posting.payableSettledBefore().value(); var after = posting.payableSettledAfter().value(); var gross = payment.holdCommand().authorization().payable().gross().value();
        if (before.compareTo(gross) > 0 || after.compareTo(gross) > 0) return false;
        if (source.recognizesOriginalPayment()) before = before.add(payment.amount().value());
        return before.compareTo(gross) <= 0 && before.compareTo(after.add(posting.returnedAmount().value())) == 0;
    }

    private static void addReturn(MessageDigest digest, SupplierPaymentReturns.Entry entry) {
        var proof = entry.proof(); add(digest, entry.registrationId(), proof.transactionReference(), proof.creditAccountReference(), proof.amount(), proof.receivedAt());
    }
    private static void add(MessageDigest digest, Object... values) {
        for (var value : values) {
            digest.update((byte) (value == null ? 0 : 1)); if (value == null) continue;
            var text = value instanceof io.agentflow.finance.Money money ? money.currency() + ":" + money.value().toPlainString() : value.toString();
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8); digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array()); digest.update(bytes);
        }
    }
    private static DomainException invalid() { return new DomainException("INVALID_SUPPLIER_PAYABLE_ADJUSTMENT_COMMAND", "Independent finance, registered bank returns and a matching open accounting date are required"); }
    @Override public String toString() { return "SupplierPayableAdjustmentCommand[id=" + id + "]"; }
}
