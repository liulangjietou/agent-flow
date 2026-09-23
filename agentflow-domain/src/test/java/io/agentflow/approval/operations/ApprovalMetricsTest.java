package io.agentflow.approval.operations;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 退回率分母遵循已确认业务口径，未结论与撤回不能稀释指标。
 * @author owlzhangfq@gmail.com
 */
class ApprovalMetricsTest {
    @Test
    void returnRateExcludesWithdrawnAndPendingRounds() {
        var metrics = ApprovalOperationsReadPort.Metrics.of(9, 8, 3, 2, 1, 1, 2, 2, 90L);
        assertThat(metrics.decidedRounds()).isEqualTo(4);
        assertThat(metrics.returnRatePercent()).isEqualByComparingTo("25.0");
    }

    @Test
    void noDecisionMeansUnknownRateAndFractionIsRoundedOnce() {
        assertThat(ApprovalOperationsReadPort.Metrics.of(2, 1, 1, 0, 0, 0, 1, 0, null).returnRatePercent()).isNull();
        assertThat(ApprovalOperationsReadPort.Metrics.of(3, 3, 0, 2, 1, 0, 0, 2, 120L).returnRatePercent())
                .isEqualByComparingTo("33.3");
    }
}
