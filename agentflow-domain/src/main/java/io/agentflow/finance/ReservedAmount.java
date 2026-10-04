package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.expense.ExpenseUse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * 事前申请与借款共用的金额预留账本；由各业务聚合组合使用，不拥有独立业务状态。
 * @author owlzhangfq@gmail.com
 */
public record ReservedAmount(Money limit, List<Reservation> consumptions, List<Reservation> reservations,
                             List<ConsumptionReversal> reversals, List<ConsumptionReduction> reductions, Ceiling ceiling) {
    /** 硬上限限制累计用量；参考账本保留超出额，任何一笔都必须有唯一轮次归属。 */
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
        if (reductions != null && reductions.stream().anyMatch(java.util.Objects::isNull)) throw invalid();
        reductions = reductions == null ? List.of() : List.copyOf(reductions);
        requireReductions(consumptions, reversals, reductions);
        Money used = Money.zero(limit.currency());
        for (var reservation : all) used = used.plus(reservation.amount());
        for (var reversal : reversals) used = used.minus(reversal.amount());
        for (var reduction : reductions) used = used.minus(reduction.amount());
        if (ceiling != Ceiling.REFERENCE_ONLY && used.compareTo(limit) > 0) throw insufficient();
        consumptions = List.copyOf(consumptions); reservations = List.copyOf(reservations);
    }

    /** 兼容没有独立冲回的原账本与调用方。 */
    public ReservedAmount(Money limit, List<Reservation> consumptions, List<Reservation> reservations) {
        this(limit, consumptions, reservations, List.of(), List.of());
    }

    /** 兼容只记录完整取消的旧构造和快照，部分调整不会伪装成旧全额反向事实。 */
    public ReservedAmount(Money limit, List<Reservation> consumptions, List<Reservation> reservations, List<ConsumptionReversal> reversals) {
        this(limit, consumptions, reservations, reversals, List.of());
    }

    /** 历史快照及调用方保持硬上限，不增写原 JSON 没有的模式属性。 */
    public ReservedAmount(Money limit, List<Reservation> consumptions, List<Reservation> reservations,
                          List<ConsumptionReversal> reversals, List<ConsumptionReduction> reductions) {
        this(limit, consumptions, reservations, reversals, reductions, null);
    }

    /** 创建已批准但尚未预留或核销的额度。 */
    public static ReservedAmount available(Money limit) { return new ReservedAmount(limit, List.of(), List.of()); }

    /** 事前宽松控制记录全部实际用量，参考金额不充当资金硬上限。 */
    public static ReservedAmount availableWithoutCeiling(Money reference) {
        return new ReservedAmount(reference, List.of(), List.of(), List.of(), List.of(), Ceiling.REFERENCE_ONLY);
    }

    /** 缺少新字段的历史记录仍严格限制额度。 */
    public boolean hardLimit() { return ceiling != Ceiling.REFERENCE_ONLY; }

    /** 当前净核销扣除独立冲回，原核销逐笔保留，旧轮次仍不能重新占用。 */
    public Money consumed() { return grossConsumed().minus(reversed()).minus(reduced()); }

    /** 原核销累计值不因取消报销而减写。 */
    public Money grossConsumed() { return consumptions.stream().map(Reservation::amount).reduce(Money.zero(limit.currency()), Money::plus); }

    /** 已完成独立调整的核销冲回额。 */
    public Money reversed() { return reversals.stream().map(ConsumptionReversal::amount).reduce(Money.zero(limit.currency()), Money::plus); }

    /** 独立部分调整的累计释放额，与旧完整取消分开保留。 */
    public Money reduced() { return reductions.stream().map(ConsumptionReduction::amount).reduce(Money.zero(limit.currency()), Money::plus); }

    /** 汇总实际预留，不向资金选择器暴露其他使用单据的身份。 */
    public Money reserved() { return reservations.stream().map(Reservation::amount).reduce(Money.zero(limit.currency()), Money::plus); }

    /** 参考剩余额度最低为零；是否仍可新增由所属聚合的控制模式决定。 */
    public Money available() {
        var used = consumed().plus(reserved());
        return used.compareTo(limit) >= 0 ? Money.zero(limit.currency()) : limit.minus(used);
    }

    /** 当前净核销及全部预留超过参考金额的真实差额。 */
    public Money exceeded() {
        var used = consumed().plus(reserved());
        return used.compareTo(limit) > 0 ? used.minus(limit) : Money.zero(limit.currency());
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

    /** 原归属的剩余净核销，原核销身份即使全部冲回也不会删除。 */
    public Money netConsumedAmountFor(ExpenseUse use) {
        var amount = consumedAmountFor(use);
        if (reversals.stream().anyMatch(value -> value.use().equals(use))) return Money.zero(limit.currency());
        for (var reduction : reductions) if (reduction.use().equals(use)) amount = amount.minus(reduction.amount());
        return amount;
    }

    /** 同一调整只能减少同一原核销一次，累计和时间顺序由账本恢复不变量共同校验。 */
    public ReservedAmount reduceConsumption(ExpenseUse use, Money amount, UUID adjustmentId, Instant at) {
        var updated = new ArrayList<>(reductions); updated.add(new ConsumptionReduction(adjustmentId, use, amount, at));
        return new ReservedAmount(limit, consumptions, reservations, reversals, updated, ceiling);
    }

    /** 独立调整完整冲回一笔原核销，不删除历史，也不改动其他报销的预留。 */
    public ReservedAmount reverseConsumption(ExpenseUse use, UUID adjustmentId, Instant at) {
        Money amount = consumedAmountFor(use);
        if (amount.value().signum() == 0 || reversals.stream().anyMatch(item -> item.use().equals(use))
                || reductions.stream().anyMatch(item -> item.use().equals(use))) {
            throw new DomainException("CONSUMPTION_REVERSAL_CONFLICT", "An unreversed original consumption is required");
        }
        var updated = new ArrayList<>(reversals); updated.add(new ConsumptionReversal(adjustmentId, use, amount, at));
        return new ReservedAmount(limit, consumptions, reservations, updated, reductions, ceiling);
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
        return new ReservedAmount(limit, consumptions, updated, reversals, reductions, ceiling);
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
        return new ReservedAmount(limit, settled, reservations.stream().filter(item -> !item.use().equals(use)).toList(), reversals, reductions, ceiling);
    }

    private static void requireReductions(List<Reservation> consumed, List<ConsumptionReversal> reversals, List<ConsumptionReduction> reductions) {
        var remaining = new HashMap<ExpenseUse, Money>(); consumed.forEach(value -> remaining.put(value.use(), value.amount()));
        reversals.forEach(value -> remaining.remove(value.use()));
        var decisions = new HashMap<ExpenseUse, HashSet<UUID>>(); var times = new HashMap<ExpenseUse, Instant>();
        for (var reduction : reductions) {
            var original = remaining.get(reduction.use()); var previousTime = times.put(reduction.use(), reduction.reducedAt());
            if (original == null || reduction.amount().compareTo(original) > 0
                    || !decisions.computeIfAbsent(reduction.use(), ignored -> new HashSet<>()).add(reduction.adjustmentId())
                    || previousTime != null && reduction.reducedAt().isBefore(previousTime)) throw invalid();
            remaining.put(reduction.use(), original.minus(reduction.amount()));
        }
    }

    private static DomainException invalid() { return new DomainException("INVALID_RESERVATION", "Reservation identity or amount is invalid"); }
    private static DomainException insufficient() { return new DomainException("INSUFFICIENT_FINANCIAL_BALANCE", "Reserved and consumed amounts exceed the available limit"); }

    /**
     * HARD 与历史空值均限制余额；REFERENCE_ONLY 仅用于业务明确允许超额的事前计划。
     * @author owlzhangfq@gmail.com
     */
    public enum Ceiling { HARD, REFERENCE_ONLY }

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

    /**
     * 部分调整只追加本次真实差额，原核销与其他调整的身份、金额和时间分别保留。
     * @author owlzhangfq@gmail.com
     */
    public record ConsumptionReduction(UUID adjustmentId, ExpenseUse use, Money amount, Instant reducedAt) {
        /** 零额不能形成完成证据，来源归属与非空调整编号必须同时具备。 */
        public ConsumptionReduction { if (adjustmentId == null || use == null || amount == null || amount.value().signum() <= 0 || reducedAt == null) throw invalid(); }
    }
}
