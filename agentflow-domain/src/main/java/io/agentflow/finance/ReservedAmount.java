package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.expense.ExpenseUse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 事前申请与借款共用的金额预留账本；由各业务聚合组合使用，不拥有独立业务状态。
 * @author owlzhangfq@gmail.com
 */
public record ReservedAmount(Money limit, List<Reservation> consumptions, List<Reservation> reservations, List<ConsumptionReversal> reversals) {
    /** 核销与全部预留不得超过批准额度，任何一笔都必须有唯一轮次归属。 */
    public ReservedAmount {
        if (limit == null || consumptions == null || reservations == null) throw invalid();
        var all = new ArrayList<>(consumptions); all.addAll(reservations);
        if (all.stream().anyMatch(java.util.Objects::isNull) || all.stream().map(Reservation::use).distinct().count() != all.size()) throw invalid();
        // 旧账本没有冲回字段，恢复为空集合；新记录必须完整对应原核销，不能直接补出可用额。
        reversals = reversals == null ? List.of() : List.copyOf(reversals);
        if (reversals.stream().map(ConsumptionReversal::use).distinct().count() != reversals.size()) throw invalid();
        for (var reversal : reversals) {
            if (consumptions.stream().noneMatch(item -> item.use().equals(reversal.use()) && item.amount().equals(reversal.amount()))) throw invalid();
        }
        Money used = Money.zero(limit.currency());
        for (var reservation : all) used = used.plus(reservation.amount());
        for (var reversal : reversals) used = used.minus(reversal.amount());
        if (used.compareTo(limit) > 0) throw insufficient();
        consumptions = List.copyOf(consumptions); reservations = List.copyOf(reservations);
    }

    /** 兼容没有独立冲回的原账本与调用方。 */
    public ReservedAmount(Money limit, List<Reservation> consumptions, List<Reservation> reservations) {
        this(limit, consumptions, reservations, List.of());
    }

    /** 创建已批准但尚未预留或核销的额度。 */
    public static ReservedAmount available(Money limit) { return new ReservedAmount(limit, List.of(), List.of()); }

    /** 当前净核销扣除独立冲回，原核销逐笔保留，旧轮次仍不能重新占用。 */
    public Money consumed() { return grossConsumed().minus(reversed()); }

    /** 原核销累计值不因取消报销而减写。 */
    public Money grossConsumed() { return consumptions.stream().map(Reservation::amount).reduce(Money.zero(limit.currency()), Money::plus); }

    /** 已完成独立调整的核销冲回额。 */
    public Money reversed() { return reversals.stream().map(ConsumptionReversal::amount).reduce(Money.zero(limit.currency()), Money::plus); }

    /** 汇总实际预留，不向资金选择器暴露其他使用单据的身份。 */
    public Money reserved() { return reservations.stream().map(Reservation::amount).reduce(Money.zero(limit.currency()), Money::plus); }

    /** 当前可用额严格扣除所有仍然有效的预留。 */
    public Money available() {
        return limit.minus(consumed()).minus(reserved());
    }

    /** 查询某一完整轮次归属已经预留的金额。 */
    public Money reservedFor(ExpenseUse use) {
        return reservations.stream().filter(item -> item.use().equals(use)).map(Reservation::amount).findFirst().orElse(Money.zero(limit.currency()));
    }

    /** 已结算的完整轮次归属不能被后续重提伪装成未预留资源。 */
    public boolean consumedFor(ExpenseUse use) { return consumptions.stream().anyMatch(item -> item.use().equals(use)); }

    /** 原轮次曾核销的精确金额，已冲回时仍保留来源金额。 */
    public Money consumedAmountFor(ExpenseUse use) {
        return consumptions.stream().filter(item -> item.use().equals(use)).map(Reservation::amount).findFirst().orElse(Money.zero(limit.currency()));
    }

    /** 独立调整完整冲回一笔原核销，不删除历史，也不改动其他报销的预留。 */
    public ReservedAmount reverseConsumption(ExpenseUse use, UUID adjustmentId, Instant at) {
        Money amount = consumedAmountFor(use);
        if (amount.value().signum() == 0 || reversals.stream().anyMatch(item -> item.use().equals(use))) {
            throw new DomainException("CONSUMPTION_REVERSAL_CONFLICT", "An unreversed original consumption is required");
        }
        var updated = new ArrayList<>(reversals); updated.add(new ConsumptionReversal(adjustmentId, use, amount, at));
        return new ReservedAmount(limit, consumptions, reservations, updated);
    }

    /** 设置该归属的精确预留额；为零时释放，其余归属不受影响。 */
    public ReservedAmount reserve(ExpenseUse use, Money amount) {
        limit.sameCurrency(amount);
        if (use == null) throw invalid();
        if (consumedFor(use)) {
            throw new DomainException("RESERVATION_ALREADY_CONSUMED", "A consumed expense round cannot reserve the same resource again");
        }
        var updated = new ArrayList<>(reservations.stream().filter(item -> !item.use().equals(use)).toList());
        if (amount.value().signum() > 0) updated.add(new Reservation(use, amount));
        return new ReservedAmount(limit, consumptions, updated, reversals);
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
        return new ReservedAmount(limit, settled, reservations.stream().filter(item -> !item.use().equals(use)).toList(), reversals);
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

    /**
     * 独立调整对原核销的完整反向事实，金额不得由调用方改为任意释放额。
     * @author owlzhangfq@gmail.com
     */
    public record ConsumptionReversal(UUID adjustmentId, ExpenseUse use, Money amount, Instant reversedAt) {
        /** 归属、调整编号、精确正金额及完成时刻同时保存。 */
        public ConsumptionReversal { if (adjustmentId == null || use == null || amount == null || amount.value().signum() <= 0 || reversedAt == null) throw invalid(); }
    }
}
