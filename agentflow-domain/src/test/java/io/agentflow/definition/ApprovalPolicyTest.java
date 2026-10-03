package io.agentflow.definition;

import io.agentflow.approval.model.CountersignProgress;
import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static io.agentflow.definition.DefinitionModels.ApprovalMode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 比例门槛的整数边界与历史全员语义，不以浮点计算替代实际责任人数。
 * @author owlzhangfq@gmail.com
 */
class ApprovalPolicyTest {
    @Test
    void requiredVotesRoundUpWithoutOverflow() {
        assertThat(new ApprovalPolicy(ApprovalMode.PERCENT, 33).requiredApprovals(3)).isEqualTo(1);
        assertThat(new ApprovalPolicy(ApprovalMode.PERCENT, 34).requiredApprovals(3)).isEqualTo(2);
        assertThat(new ApprovalPolicy(ApprovalMode.PERCENT, 50).requiredApprovals(3)).isEqualTo(2);
        assertThat(new ApprovalPolicy(ApprovalMode.PERCENT, 67).requiredApprovals(3)).isEqualTo(3);
        assertThat(new ApprovalPolicy(ApprovalMode.PERCENT, 100).requiredApprovals(Integer.MAX_VALUE)).isEqualTo(Integer.MAX_VALUE);
        assertThat(new ApprovalPolicy(ApprovalMode.PERCENT, 1).requiredApprovals(1)).isEqualTo(1);
    }

    @Test
    void anyAndAllKeepTheirDistinctMeaningAndLegacyDefault() {
        assertThat(new ApprovalPolicy(ApprovalMode.ANY, null).requiredApprovals(3)).isEqualTo(1);
        assertThat(new ApprovalPolicy(ApprovalMode.ALL, null).requiredApprovals(3)).isEqualTo(3);
        assertThat(ApprovalPolicy.fromProperties(Map.of()).mode()).isEqualTo(ApprovalMode.SINGLE);
        assertThat(ApprovalPolicy.fromProperties(Map.of()).multiInstance()).isFalse();
        assertThat(new CountersignProgress(4, 1).required()).isEqualTo(4);
    }

    @Test
    void emptyRosterAndProgressMismatchFailClosed() {
        assertThatThrownBy(() -> new ApprovalPolicy(ApprovalMode.ANY, null).requiredApprovals(0)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new CountersignProgress(3, 0, ApprovalMode.PERCENT, 67, 2)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new CountersignProgress(3, 4)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new CountersignProgress(3, 0, ApprovalMode.SINGLE, null, 1)).isInstanceOf(DomainException.class);
    }
}
