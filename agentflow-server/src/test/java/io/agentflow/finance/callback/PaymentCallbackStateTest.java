package io.agentflow.finance.callback;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 回调恢复只记录本地处理事实，失败上限及人工恢复不改变原事件身份。
 * @author owlzhangfq@gmail.com
 */
class PaymentCallbackStateTest {
    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    private final PaymentCallbackVerifier.Verified input = new PaymentCallbackVerifier.Verified("evt_1", "a".repeat(64), "b".repeat(64),
            new PaymentCallbackVerifier.Signal(1, "payment.changed", "demo", PaymentCallbackVerifier.Kind.EMPLOYEE, UUID.randomUUID(), "c".repeat(64), 2));

    @Test void repeatedLocalFailureStopsAndExplicitRetryPreservesOriginalEventWithReason() {
        var value = PaymentCallback.receive(input, NOW);
        for (int count = 1; count <= 10; count++) {
            value = value.failed(value.nextAttemptAt()); assertThat(value.failures()).isEqualTo(count);
            if (count < 10) assertThat(value.nextAttemptAt()).isAfter(value.updatedAt()).isBeforeOrEqualTo(value.updatedAt().plusSeconds(300));
        }
        assertThat(value.status()).isEqualTo(PaymentCallback.Status.REVIEW_REQUIRED); assertThat(value.nextAttemptAt()).isNull();
        var retry = value.retry("admin", "恢复原查询", value.updatedAt().plusSeconds(1));
        assertThat(retry.input()).isSameAs(input); assertThat(retry.id()).isEqualTo(value.id()); assertThat(retry.failures()).isZero();
        assertThat(retry.requestReason()).isEqualTo("恢复原查询"); assertThat(retry.requestedBy()).isEqualTo("admin");
    }
    @Test void recordedQueryCannotBeReplayedAsASecondQueryOrDiscardOriginalReceipt() {
        var received = PaymentCallback.receive(input, NOW); var waiting = received.waitForOperation(NOW.plusSeconds(1));
        var done = waiting.queried(37, true, waiting.nextAttemptAt()); assertThat(done.queryVersion()).isEqualTo(37); assertThat(done.input()).isEqualTo(input);
        assertThat(done.reason()).isEqualTo(PaymentCallback.Reason.ALREADY_OBSERVED);
        assertThatThrownBy(() -> done.queried(38, false, done.updatedAt())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> done.failed(done.updatedAt())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> done.retry("admin", "重复查询", done.updatedAt())).isInstanceOf(DomainException.class);
    }
}
