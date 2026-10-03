package io.agentflow.notification;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
     * 原来源与事实不能通过替换类型或非规范编号混淆。
     * @author owlzhangfq@gmail.com
     */
class ExpensePartialAdjustmentNoticeTest {
    @Test void originalOperationAndCanonicalKeyRemainBound() {
        var id = UUID.fromString("12345678-1234-1234-1234-123456789abc"); var op = UUID.randomUUID();
        for (var fact : ExpensePartialAdjustmentNotice.values()) {
            var source = fact.sourceType() == ExpensePartialAdjustmentNotice.SourceType.ADJUSTMENT ? id : op;
            var key = fact.eventKey(id, source); var parsed = ExpensePartialAdjustmentNotice.source(key).orElseThrow();
            assertThat(parsed.adjustmentId()).isEqualTo(id); assertThat(parsed.sourceId()).isEqualTo(source); assertThat(parsed.notice()).isEqualTo(fact);
            assertThat(ExpensePartialAdjustmentNotice.source(key.toUpperCase())).isEmpty();
            assertThat(ExpensePartialAdjustmentNotice.source(key + ":extra")).isEmpty();
        }
        assertThat(ExpensePartialAdjustmentNotice.source(ExpensePartialAdjustmentNotice.COMPLETED.eventKey(id, op))).isEmpty();
        assertThat(ExpensePartialAdjustmentNotice.source(ExpensePartialAdjustmentNotice.BUDGET_UNKNOWN.eventKey(id, op).replace(":BUDGET:", ":ACCRUAL:"))).isEmpty();
        assertThat(ExpensePartialAdjustmentNotice.source(null)).isEmpty();
    }
}
