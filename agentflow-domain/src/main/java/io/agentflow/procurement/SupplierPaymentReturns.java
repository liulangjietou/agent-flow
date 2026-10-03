package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 原供应商付款的独立累计回款账本，登记银行资金不等于已完成 ERP 调整。
 * @author owlzhangfq@gmail.com
 */
public record SupplierPaymentReturns(SupplierPaymentReturnPort.Request request, long version, List<Entry> entries,
                                     boolean reviewRequired, Instant createdAt, Instant updatedAt, Accounting accounting) {
    /** 持久恢复仍核对原公司账户、金额和首次登记归属，实际退回不能清成无疑点。 */
    public SupplierPaymentReturns {
        if (request == null || version < 1 || entries == null || entries.size() > SupplierPaymentReturnPort.MAX_RETURN_ENTRIES
                || entries.stream().anyMatch(Objects::isNull) || createdAt == null || updatedAt == null || updatedAt.isBefore(createdAt)
                || createdAt.isBefore(request.original().observedAt())
                || accounting != null && (accounting.entryCount() > entries.size() || accounting.accountedAt().isBefore(createdAt) || accounting.accountedAt().isAfter(updatedAt))
                || !entries.isEmpty() && !reviewRequired && (accounting == null || accounting.entryCount() != entries.size())) throw invalid();
        entries = List.copyOf(entries);
        if (entries.stream().map(entry -> entry.proof().transactionReference()).distinct().count() != entries.size()) throw invalid();
        var total = Money.zero(request.command().amount().currency());
        for (var entry : entries) {
            SupplierPaymentReturnPort.requireFunding(request, entry.proof(), updatedAt);
            total = total.plus(entry.proof().amount());
        }
        if (total.compareTo(request.command().amount()) > 0) throw invalid();
    }
    /** 旧 V80 账本没有独立 ERP 完成引用，恢复时继续保持实际回款待处理。 */
    public SupplierPaymentReturns(SupplierPaymentReturnPort.Request request, long version, List<Entry> entries,
                                 boolean reviewRequired, Instant createdAt, Instant updatedAt) {
        this(request, version, entries, reviewRequired, createdAt, updatedAt, null);
    }
    /** 仅固定原付款，不从一次查询意图产生已收资金。 */
    public static SupplierPaymentReturns open(SupplierPaymentReturnPort.Request request, Instant at) {
        return new SupplierPaymentReturns(request, 1, List.of(), false, at, at);
    }
    /** 未核清结果冻结后续办理，原核销与原完成凭据仍保留。 */
    public SupplierPaymentReturns requireReview(Instant at) {
        requireTime(at); return reviewRequired ? this : new SupplierPaymentReturns(request, version + 1, entries, true, createdAt, at, accounting);
    }
    /** 只追加新实收资金，重复累计确认保留每笔资金的首次登记编号。 */
    public SupplierPaymentReturns register(SupplierPaymentReturn decision) {
        if (decision == null || !request.equals(decision.receipt().request())) throw invalid();
        requireTime(decision.registeredAt());
        var proofs = entries.stream().map(Entry::proof).toList();
        if (!decision.receipt().returns().containsAll(proofs)) throw new DomainException("SUPPLIER_PAYMENT_RETURN_EVIDENCE_CHANGED", "Cumulative supplier returns cannot remove or replace a recorded bank receipt");
        var next = new ArrayList<>(entries);
        for (var proof : decision.receipt().returns()) if (!proofs.contains(proof)) next.add(new Entry(decision.id(), proof));
        return new SupplierPaymentReturns(request, version + 1, next, next.size() > accountedEntryCount(), createdAt, decision.registeredAt(), accounting);
    }
    /** ERP 成功后再次核对银行累计原件，只标记本命令实际覆盖的资金；更多已登记回款继续待调整。 */
    public SupplierPaymentReturns account(SupplierPayableAdjustmentOperation operation, SupplierPaymentReturnPort.Receipt verified, Instant now) {
        requireTime(now);
        if (operation == null || !operation.adjusted() || now.isBefore(operation.updatedAt()) || verified == null
                || !verified.matches(request, now) || verified.observedAt().isBefore(updatedAt) || verified.observedAt().isBefore(operation.updatedAt())
                || operation.evidence() == null || !verified.continues(operation.evidence().bank())
                || verified.status() != SupplierPaymentReturnPort.Status.PARTIALLY_RETURNED && verified.status() != SupplierPaymentReturnPort.Status.RETURNED
                || verified.returns().size() != entries.size() || !verified.returns().containsAll(entries.stream().map(Entry::proof).toList())) throw accountingChanged();
        var source = operation.command().source().returns(); var covered = source.entries().size();
        if (!source.request().equals(request) || !Objects.equals(source.accounting(), accounting)
                || covered <= accountedEntryCount() || covered > entries.size() || !source.entries().equals(entries.subList(0, covered))) throw accountingChanged();
        return new SupplierPaymentReturns(request, version + 1, entries, covered < entries.size(), createdAt, now,
                new Accounting(operation.command().id(), operation.version(), covered, now));
    }
    /** 资金数组只追加，已记账前缀引用独立完成操作，不能按金额碰巧相同推断归属。 */
    public int accountedEntryCount() { return accounting == null ? 0 : accounting.entryCount(); }
    /** 独立汇总累计回款，原指令金额保持不变。 */
    public Money totalReturned() { return entries.stream().map(entry -> entry.proof().amount()).reduce(Money.zero(request.command().amount().currency()), Money::plus); }
    private void requireTime(Instant at) { if (at == null || at.isBefore(updatedAt)) throw invalid(); }
    private static DomainException invalid() { return new DomainException("INVALID_SUPPLIER_PAYMENT_RETURNS", "Supplier return ledger must preserve original payment and immutable cumulative received funds"); }
    private static DomainException accountingChanged() { return new DomainException("SUPPLIER_PAYABLE_ADJUSTMENT_COMPLETION_CHANGED", "Confirmed supplier adjustment and fresh cumulative bank receipts must preserve the current return ledger"); }
    @Override public String toString() { return "SupplierPaymentReturns[paymentId=" + request.command().id() + ", version=" + version + "]"; }

    /**
     * 各笔资金保留第一次明确登记归属，后续同源查询只能追加。
     * @author owlzhangfq@gmail.com
     */
    public record Entry(UUID registrationId, SupplierPaymentReturnPort.BankReceipt proof) {
        public Entry { if (registrationId == null || proof == null) throw invalid(); }
    }

    /**
     * 只引用已完成调整及其累计资金前缀，持久仓储还须核对实际成功修订和完成记录。
     * @author owlzhangfq@gmail.com
     */
    public record Accounting(UUID operationId, long operationVersion, int entryCount, Instant accountedAt) {
        public Accounting {
            if (operationId == null || operationVersion < 1 || entryCount < 1 || entryCount > SupplierPaymentReturnPort.MAX_RETURN_ENTRIES || accountedAt == null) throw invalid();
        }
    }
}
