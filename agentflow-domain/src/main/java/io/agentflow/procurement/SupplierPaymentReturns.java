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
                                     boolean reviewRequired, Instant createdAt, Instant updatedAt) {
    /** 持久恢复仍核对原公司账户、金额和首次登记归属，实际退回不能清成无疑点。 */
    public SupplierPaymentReturns {
        if (request == null || version < 1 || entries == null || entries.size() > SupplierPaymentReturnPort.MAX_RETURN_ENTRIES
                || entries.stream().anyMatch(Objects::isNull) || createdAt == null || updatedAt == null || updatedAt.isBefore(createdAt)
                || createdAt.isBefore(request.original().observedAt()) || !entries.isEmpty() && !reviewRequired) throw invalid();
        entries = List.copyOf(entries);
        if (entries.stream().map(entry -> entry.proof().transactionReference()).distinct().count() != entries.size()) throw invalid();
        var total = Money.zero(request.command().amount().currency());
        for (var entry : entries) {
            SupplierPaymentReturnPort.requireFunding(request, entry.proof(), updatedAt);
            total = total.plus(entry.proof().amount());
        }
        if (total.compareTo(request.command().amount()) > 0) throw invalid();
    }
    /** 仅固定原付款，不从一次查询意图产生已收资金。 */
    public static SupplierPaymentReturns open(SupplierPaymentReturnPort.Request request, Instant at) {
        return new SupplierPaymentReturns(request, 1, List.of(), false, at, at);
    }
    /** 未核清结果冻结后续办理，原核销与原完成凭据仍保留。 */
    public SupplierPaymentReturns requireReview(Instant at) {
        requireTime(at); return reviewRequired ? this : new SupplierPaymentReturns(request, version + 1, entries, true, createdAt, at);
    }
    /** 只追加新实收资金，重复累计确认保留每笔资金的首次登记编号。 */
    public SupplierPaymentReturns register(SupplierPaymentReturn decision) {
        if (decision == null || !request.equals(decision.receipt().request())) throw invalid();
        requireTime(decision.registeredAt());
        var proofs = entries.stream().map(Entry::proof).toList();
        if (!decision.receipt().returns().containsAll(proofs)) throw new DomainException("SUPPLIER_PAYMENT_RETURN_EVIDENCE_CHANGED", "Cumulative supplier returns cannot remove or replace a recorded bank receipt");
        var next = new ArrayList<>(entries);
        for (var proof : decision.receipt().returns()) if (!proofs.contains(proof)) next.add(new Entry(decision.id(), proof));
        return new SupplierPaymentReturns(request, version + 1, next, !next.isEmpty(), createdAt, decision.registeredAt());
    }
    /** 独立汇总累计回款，原指令金额保持不变。 */
    public Money totalReturned() { return entries.stream().map(entry -> entry.proof().amount()).reduce(Money.zero(request.command().amount().currency()), Money::plus); }
    private void requireTime(Instant at) { if (at == null || at.isBefore(updatedAt)) throw invalid(); }
    private static DomainException invalid() { return new DomainException("INVALID_SUPPLIER_PAYMENT_RETURNS", "Supplier return ledger must preserve original payment and immutable cumulative received funds"); }
    @Override public String toString() { return "SupplierPaymentReturns[paymentId=" + request.command().id() + ", version=" + version + "]"; }

    /**
     * 各笔资金保留第一次明确登记归属，后续同源查询只能追加。
     * @author owlzhangfq@gmail.com
     */
    public record Entry(UUID registrationId, SupplierPaymentReturnPort.BankReceipt proof) {
        public Entry { if (registrationId == null || proof == null) throw invalid(); }
    }
}
