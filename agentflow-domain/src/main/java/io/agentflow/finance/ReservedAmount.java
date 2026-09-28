package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.expense.ExpenseUse;
import java.util.ArrayList;
import java.util.List;

/**
 * 事前申请与借款共用的金额预留账本；由各业务聚合组合使用，不拥有独立业务状态。
 * @author owlzhangfq@gmail.com
 */
public record ReservedAmount(Money limit, List<Reservation> consumptions, List<Reservation> reservations) {
    /** 核销与全部预留不得超过批准额度，任何一笔都必须有唯一轮次归属。 */
    public ReservedAmount {
        if (limit == null || consumptions == null || reservations == null) throw invalid();
        var all = new ArrayList<>(consumptions); all.addAll(reservations);
        if (all.stream().anyMatch(java.util.Objects::isNull) || all.stream().map(Reservation::use).distinct().count() != all.size()) throw invalid();
        Money used = Money.zero(limit.currency());
        for (var reservation : all) used = used.plus(reservation.amount());
        if (used.compareTo(limit) > 0) throw insufficient();
        consumptions = List.copyOf(consumptions); reservations = List.copyOf(reservations);
    }

    /** 创建已批准但尚未预留或核销的额度。 */
    public static ReservedAmount available(Money limit) { return new ReservedAmount(limit, List.of(), List.of()); }

    /** 核销总额由不可变的逐笔归属派生，不能丢掉已核销轮次的防重依据。 */
    public Money consumed() { return consumptions.stream().map(Reservation::amount).reduce(Money.zero(limit.currency()), Money::plus); }

    /** 当前可用额严格扣除所有仍然有效的预留。 */
    public Money available() {
        Money reserved = reservations.stream().map(Reservation::amount).reduce(Money.zero(limit.currency()), Money::plus);
        return limit.minus(consumed()).minus(reserved);
    }

    /** 查询某一完整轮次归属已经预留的金额。 */
    public Money reservedFor(ExpenseUse use) {
        return reservations.stream().filter(item -> item.use().equals(use)).map(Reservation::amount).findFirst().orElse(Money.zero(limit.currency()));
    }

    /** 设置该归属的精确预留额；为零时释放，其余归属不受影响。 */
    public ReservedAmount reserve(ExpenseUse use, Money amount) {
        limit.sameCurrency(amount);
        if (use == null) throw invalid();
        if (consumptions.stream().anyMatch(item -> item.use().equals(use))) {
            throw new DomainException("RESERVATION_ALREADY_CONSUMED", "A consumed expense round cannot reserve the same resource again");
        }
        var updated = new ArrayList<>(reservations.stream().filter(item -> !item.use().equals(use)).toList());
        if (amount.value().signum() > 0) updated.add(new Reservation(use, amount));
        return new ReservedAmount(limit, consumptions, updated);
    }

    /** 重提时原子迁移原轮次归属并调整金额，超额失败时原账本保持不变。 */
    public ReservedAmount move(ExpenseUse previous, ExpenseUse next, Money amount) {
        if (previous == null || next == null || !previous.reportId().equals(next.reportId()) || next.roundNo() != previous.roundNo() + 1
                || previous.lineNo() != next.lineNo() || reservedFor(previous).value().signum() == 0
                || reservedFor(next).value().signum() != 0) throw invalid();
        return reserve(previous, Money.zero(limit.currency())).reserve(next, amount);
    }

    /** 结算核销恰好消耗已经预留的金额，不能再次核销或扩大核销额。 */
    public ReservedAmount consume(ExpenseUse use) {
        Money amount = reservedFor(use);
        if (amount.value().signum() == 0) throw new DomainException("RESERVATION_NOT_FOUND", "No active reservation exists for this expense round");
        var settled = new ArrayList<>(consumptions); settled.add(new Reservation(use, amount));
        return new ReservedAmount(limit, settled, reservations.stream().filter(item -> !item.use().equals(use)).toList());
    }

    private static DomainException invalid() { return new DomainException("INVALID_RESERVATION", "Reservation identity or amount is invalid"); }
    private static DomainException insufficient() { return new DomainException("INSUFFICIENT_FINANCIAL_BALANCE", "Reserved and consumed amounts exceed the available limit"); }

    /**
     * 一笔正金额预留，取消以移除该项表示，不保存无意义的零预留。
     * @author owlzhangfq@gmail.com
     */
    public record Reservation(ExpenseUse use, Money amount) {
        /** 金额归属在创建时必须完整。 */
        public Reservation { if (use == null || amount == null || amount.value().signum() <= 0) throw invalid(); }
    }
}
