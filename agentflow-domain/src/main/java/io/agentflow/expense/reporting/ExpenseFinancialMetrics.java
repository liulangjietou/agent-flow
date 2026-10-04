package io.agentflow.expense.reporting;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.approval.model.SubmissionRound.Status;
import io.agentflow.finance.Money;
import org.apache.commons.lang3.StringUtils;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 只消费已授权原轮次事实的纯指标计算，不查询当前组织或推断资金成功。
 * @author owlzhangfq@gmail.com
 */
public final class ExpenseFinancialMetrics {
    private static final int RATE_SCALE = 6;
    private static final int MEDIAN_PERCENTILE = 50;
    private static final int TAIL_PERCENTILE = 90;
    private static final int PERCENTILE_BASE = 100;
    private ExpenseFinancialMetrics() { }

    /** 每个输入代表一个真实轮次；类别筛选只改变明细范围，不把同轮多行计为多次提交。 */
    public static Metrics summarize(List<Round> rounds, Instant generatedAt, String categoryCode) {
        Map<Status, Long> counts = new EnumMap<>(Status.class);
        Map<String, AmountAccumulator> amounts = new TreeMap<>();
        Map<String, Long> reasons = new TreeMap<>(Comparator.nullsLast(Comparator.naturalOrder()));
        var approval = new Samples(); var payment = new Samples(); var endToEnd = new Samples();
        long submitted = 0, overLimit = 0, reduced = 0;
        for (var round : rounds) {
            var lines = categoryCode == null ? round.lines() : round.lines().stream().filter(line -> categoryCode.equals(line.categoryCode())).toList();
            if (lines.isEmpty()) continue;
            submitted++; counts.merge(round.status(), 1L, Long::sum);
            if (lines.stream().anyMatch(Line::exceptionRequired)) overLimit++;
            if (lines.stream().anyMatch(line -> line.approved().compareTo(line.claimed()) < 0)) reduced++;
            for (var line : lines) amounts.computeIfAbsent(line.claimed().currency(), ignored -> new AmountAccumulator()).add(line);
            if (round.status() == Status.RETURNED) reasons.merge(StringUtils.trimToNull(round.returnReason()), 1L, Long::sum);
            if (round.status() == Status.APPROVED) {
                approval.observe(round.submittedAt(), round.completedAt(), generatedAt);
                switch (round.payment()) {
                    case CONFIRMED -> {
                        payment.observe(round.completedAt(), round.paidAt(), generatedAt);
                        endToEnd.observe(round.submittedAt(), round.paidAt(), generatedAt);
                    }
                    case AWAITING -> { payment.pending++; endToEnd.pending++; }
                    case UNKNOWN -> { payment.unknown++; endToEnd.unknown++; }
                    case NOT_REQUIRED -> { payment.notApplicable++; endToEnd.notApplicable++; }
                }
            } else if (round.status() == Status.IN_APPROVAL) {
                approval.pending++; payment.pending++; endToEnd.pending++;
            } else {
                approval.notApplicable++; payment.notApplicable++; endToEnd.notApplicable++;
            }
        }
        long approved = counts.getOrDefault(Status.APPROVED, 0L), returned = counts.getOrDefault(Status.RETURNED, 0L),
                rejected = counts.getOrDefault(Status.REJECTED, 0L);
        var reasonRows = reasons.entrySet().stream().map(entry -> new Reason(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingLong(Reason::count).reversed().thenComparing(Reason::reason, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
        return new Metrics(submitted, approved, returned, rejected, counts.getOrDefault(Status.WITHDRAWN, 0L),
                counts.getOrDefault(Status.CANCELLED, 0L), counts.getOrDefault(Status.IN_APPROVAL, 0L), overLimit, reduced,
                ratio(BigDecimal.valueOf(overLimit), BigDecimal.valueOf(submitted)), ratio(BigDecimal.valueOf(reduced), BigDecimal.valueOf(submitted)),
                ratio(BigDecimal.valueOf(returned), BigDecimal.valueOf(approved + returned + rejected)),
                approval.result(), payment.result(), endToEnd.result(), amounts.entrySet().stream().map(entry -> entry.getValue().result(entry.getKey())).toList(), reasonRows);
    }

    // 轮次比率和额度执行率共用明确舍入；分母为零保留未知，执行率不截断到百分之百。
    static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        return denominator.signum() == 0 ? null : numerator.divide(denominator, RATE_SCALE, RoundingMode.HALF_UP);
    }
    static String amount(BigDecimal value) { return value.setScale(Money.SCALE, RoundingMode.UNNECESSARY).toPlainString(); }

    /**
     * 付款是否适用和是否已确认由原业务来源判断，当前时间不能代替到账时间。
     * @author owlzhangfq@gmail.com
     */
    public enum Payment { NOT_REQUIRED, AWAITING, UNKNOWN, CONFIRMED }
    /**
     * 原行本位币金额及原制度例外，组织和日期筛选由查询层完成。
     * @author owlzhangfq@gmail.com
     */
    public record Line(String categoryCode, Money claimed, Money approved, boolean exceptionRequired) { }
    /**
     * 原审批结论与第一次确认到账组成一份统计事实，后续退回不替换 paidAt。
     * @author owlzhangfq@gmail.com
     */
    public record Round(Status status, Instant submittedAt, Instant completedAt, Payment payment, Instant paidAt,
                        List<Line> lines, String returnReason) {
        /** 明细快照不能在计算过程中被调用方修改。 */
        public Round { lines = List.copyOf(lines); }
    }
    /**
     * 金额以两位十进制文本输出，汇总不限于单笔交易上限，浏览器不得转浮点计算。
     * @author owlzhangfq@gmail.com
     */
    public record Amounts(String currency, String claimed, String approved, String reduced) { }
    /**
     * 缺失原因保留 null，与真实自由文本原因分开。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Reason(String reason, long count) { }
    /**
     * 四种样本互斥；无有效样本时两个分位数明确为空。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Cycle(long samples, long unknown, long pending, long notApplicable, Long p50Seconds, Long p90Seconds) { }
    /**
     * 比率为零到一的小数，空分母不是零；各金额行始终独立按币种汇总。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Metrics(long submitted, long approved, long returned, long rejected, long withdrawn, long cancelled, long inApproval,
                          long overLimit, long reduced, BigDecimal overLimitRate, BigDecimal reductionRate, BigDecimal returnRate,
                          Cycle approval, Cycle payment, Cycle endToEnd, List<Amounts> amounts, List<Reason> returnReasons) { }

    /**
     * 一个周期的内部样本，时间异常只影响相关周期，不抹掉另一个周期的真实事实。
     * @author owlzhangfq@gmail.com
     */
    private static final class Samples {
        private final List<Long> seconds = new ArrayList<>();
        private long unknown, pending, notApplicable;
        private void observe(Instant start, Instant end, Instant now) {
            if (start == null || end == null || end.isBefore(start) || end.isAfter(now)) unknown++;
            else seconds.add(Duration.between(start, end).getSeconds());
        }
        private Cycle result() {
            seconds.sort(Comparator.naturalOrder());
            return new Cycle(seconds.size(), unknown, pending, notApplicable, percentile(MEDIAN_PERCENTILE), percentile(TAIL_PERCENTILE));
        }
        private Long percentile(int percent) {
            if (seconds.isEmpty()) return null;
            int rank = (int) (((long) percent * seconds.size() + PERCENTILE_BASE - 1) / PERCENTILE_BASE);
            return seconds.get(rank - 1);
        }
    }
    /**
     * 已经验证的原行使用任意精度累计，不能套用单笔 Money 上限。
     * @author owlzhangfq@gmail.com
     */
    private static final class AmountAccumulator {
        private BigDecimal claimed = BigDecimal.ZERO, approved = BigDecimal.ZERO;
        private void add(Line line) { claimed = claimed.add(line.claimed().value()); approved = approved.add(line.approved().value()); }
        private Amounts result(String currency) { return new Amounts(currency, amount(claimed), amount(approved), amount(claimed.subtract(approved))); }
    }
}
