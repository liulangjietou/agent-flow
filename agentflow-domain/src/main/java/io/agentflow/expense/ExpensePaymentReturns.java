package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.ExpensePaymentReturnPort;
import io.agentflow.finance.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 原报销付款的独立退回账本，复核冻结与实际累计退回均不能被其他凭证裁决清除。
 * @author owlzhangfq@gmail.com
 */
public record ExpensePaymentReturns(ExpensePaymentReturnPort.Request request, long version, List<Entry> entries,
                                    boolean reviewRequired, Instant createdAt, Instant updatedAt) {
    /** 保留原付款与只追加的入款；已退回金额必须等待独立后续办理。 */
    public ExpensePaymentReturns {
        if (request == null || version < 1 || entries == null || entries.size() > ExpensePaymentReturnPort.MAX_RETURN_ENTRIES
                || entries.stream().anyMatch(Objects::isNull) || createdAt == null || updatedAt == null || updatedAt.isBefore(createdAt)
                || createdAt.isBefore(request.original().observedAt()) || !entries.isEmpty() && !reviewRequired) throw invalid();
        entries = List.copyOf(entries);
        if (entries.stream().map(entry -> entry.proof().fundsIdentity()).distinct().count() != entries.size()
                || entries.stream().map(entry -> entry.proof().postingIdentity()).distinct().count() != entries.size()) throw invalid();
        var total = Money.zero(request.command().amount().currency());
        for (var entry : entries) {
            var proof = entry.proof();
            if (!request.payableAccountCode().equals(proof.posting().accountCode()) || proof.funding().receivedAt().isBefore(request.original().completedAt())
                    || proof.posting().postedAt().isAfter(updatedAt)) throw invalid();
            total = total.plus(proof.funding().amount());
        }
        if (total.compareTo(request.command().amount()) > 0) throw invalid();
    }
    /** 首次查询只固定原意图，不生成资金或冻结已确认的正常结算。 */
    public static ExpensePaymentReturns open(ExpensePaymentReturnPort.Request request, Instant at) {
        return new ExpensePaymentReturns(request, 1, List.of(), false, at, at);
    }
    /** 不一致查询先冻结后续使用；已见银行事实等待独立财务确认。 */
    public ExpensePaymentReturns requireReview(Instant at) {
        requireTime(at); return reviewRequired ? this : new ExpensePaymentReturns(request, version + 1, entries, true, createdAt, at);
    }
    /** 每次明确登记只追加新增入款；没有退回的复核只解除本账本的疑点。 */
    public ExpensePaymentReturns register(ExpensePaymentReturn decision) {
        if (decision == null || !request.equals(decision.receipt().request())) throw invalid();
        requireTime(decision.registeredAt());
        var proofs = entries.stream().map(Entry::proof).toList();
        if (!decision.receipt().returns().containsAll(proofs)) throw new DomainException("EXPENSE_PAYMENT_RETURN_EVIDENCE_CHANGED", "Cumulative expense return cannot remove or replace a recorded bank receipt");
        var next = new ArrayList<>(entries);
        for (var proof : decision.receipt().returns()) if (!proofs.contains(proof)) next.add(new Entry(decision.id(), proof));
        return new ExpensePaymentReturns(request, version + 1, next, !next.isEmpty(), createdAt, decision.registeredAt());
    }
    /** 已登记退回独立于原付款金额计算，不扣减费用或借款冲销。 */
    public Money totalReturned() { return entries.stream().map(entry -> entry.proof().funding().amount()).reduce(Money.zero(request.command().amount().currency()), Money::plus); }
    private void requireTime(Instant at) { if (at == null || at.isBefore(updatedAt)) throw invalid(); }
    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_PAYMENT_RETURNS", "Expense return ledger must preserve original payment and immutable cumulative received funds"); }
    @Override public String toString() { return "ExpensePaymentReturns[paymentId=" + request.command().id() + ", version=" + version + "]"; }

    /**
     * 每笔资金保留首次明确登记的归属，重复累计查询不能换成新的登记编号。
     * @author owlzhangfq@gmail.com
     */
    public record Entry(UUID registrationId, ExpensePaymentReturnPort.ReturnItem proof) {
        public Entry { if (registrationId == null || proof == null) throw invalid(); }
    }
}
