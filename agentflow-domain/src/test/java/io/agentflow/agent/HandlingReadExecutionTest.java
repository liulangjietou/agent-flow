package io.agentflow.agent;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 原只读步骤恢复及不重复读取已保存结果。
 * @author owlzhangfq@gmail.com
 */
class HandlingReadExecutionTest {
    @Test void leaseAndOriginalInputFenceConcurrentOrChangedRetries() {
        var now = Instant.now(); var run = HandlingReadExecution.start(UUID.randomUUID(), "input", "authorization", now);
        assertThatThrownBy(() -> run.retry(now.plusSeconds(179))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> run.requireMatches("changed", "authorization")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> run.requireMatches("input", "new-role")).isInstanceOf(DomainException.class);
        var retry = run.retry(now.plusSeconds(180)); assertThat(retry.id()).isEqualTo(run.id()); assertThat(retry.version()).isEqualTo(2);
        assertThatThrownBy(() -> run.prepared("late", now.plusSeconds(180))).isInstanceOf(DomainException.class);
    }
    @Test void savedReadAndReceiptCanBeRecoveredWithoutRepeatingNetwork() {
        var now = Instant.now(); var run = HandlingReadExecution.start(UUID.randomUUID(), "input", "authorization", now).prepared("original result", now.plusSeconds(1));
        assertThat(run.retry(now.plusSeconds(500))).isSameAs(run);
        var receipt = run.recorded("original receipt"); assertThat(receipt.retry(now.plusSeconds(800))).isSameAs(receipt);
        assertThat(receipt.preparedJson()).isEqualTo("original result");
    }
}
