package io.agentflow.expense;

import io.agentflow.finance.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 一次原子核减的不可变差额证据，既保留费用行也保留受影响的借款冲销。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseAdjustment(UUID id, long previousFinancialVersion, String adjustedBy, Instant adjustedAt,
                                String reasonCode, String comment, List<LineChange> lineChanges,
                                List<OffsetChange> offsetChanges) {
    /** 防止外部持有的可变集合改写既有核减审计。 */
    public ExpenseAdjustment { lineChanges = List.copyOf(lineChanges); offsetChanges = List.copyOf(offsetChanges); }

    /**
     * 以本位币记录核减前后含税及税额。
     * @author owlzhangfq@gmail.com
     */
    public record LineChange(int lineNo, Money previousGross, Money approvedGross, Money previousTax, Money approvedTax) { }

    /**
     * 冲销自动收敛的前后金额，供应用服务退还多余预留。
     * @author owlzhangfq@gmail.com
     */
    public record OffsetChange(UUID advanceId, Money previousAmount, Money amount) { }
}
