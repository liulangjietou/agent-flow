package io.agentflow.event;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 原事件不可变、失败有界及人工恢复不替换目标的领域边界。
 * @author owlzhangfq@gmail.com
 */
class EventInboxItemTest {
    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private final ReceivedEvent input = new ReceivedEvent("evt-1", "a".repeat(64), 1,
            new EventSignal(1, "demo", "erp", "GoodsAccepted", UUID.randomUUID(), 1, "wait-1", "accepted", 1));

    @Test void pauseAndDisableKeepTheExactOriginalEvent() {
        var initial = EventInboxItem.receive(input, NOW);
        var waiting = initial.waitFor(EventInboxItem.Reason.PAUSED, NOW.plusSeconds(1));
        assertThat(waiting.input()).isSameAs(input); assertThat(waiting.id()).isEqualTo(initial.id());
        assertThat(waiting.failures()).isZero(); assertThat(waiting.nextAttemptAt()).isAfter(waiting.updatedAt());
        var consumed = waiting.consumed(waiting.nextAttemptAt());
        assertThat(consumed.status()).isEqualTo(EventInboxItem.Status.CONSUMED);
        assertThat(consumed.pending()).isFalse(); assertThat(consumed.nextAttemptAt()).isNull();
        assertThatThrownBy(() -> consumed.consumed(NOW.plusSeconds(60))).isInstanceOf(DomainException.class);
    }
    @Test void failuresBackOffAndEventuallyRequireExplicitOriginalEventRecovery() {
        var item = EventInboxItem.receive(input, NOW);
        for (int count = 1; count <= EventInboxItem.MAX_FAILURES; count++) {
            item = item.failed(item.nextAttemptAt()); assertThat(item.failures()).isEqualTo(count);
            if (count < EventInboxItem.MAX_FAILURES) {
                assertThat(item.status()).isEqualTo(EventInboxItem.Status.WAITING);
                assertThat(item.nextAttemptAt()).isAfter(item.updatedAt()).isBeforeOrEqualTo(item.updatedAt().plusSeconds(300));
            }
        }
        assertThat(item.status()).isEqualTo(EventInboxItem.Status.REVIEW_REQUIRED);
        assertThat(item.nextAttemptAt()).isNull();
        var retried = item.retry("admin", "已修复通知依赖，重试原事件", item.updatedAt().plusSeconds(1));
        assertThat(retried.input()).isEqualTo(input); assertThat(retried.id()).isEqualTo(item.id());
        assertThat(retried.version()).isEqualTo(item.version() + 1); assertThat(retried.failures()).isZero();
        assertThat(retried.requestedBy()).isEqualTo("admin"); assertThat(retried.requestReason()).isEqualTo("已修复通知依赖，重试原事件");
    }
    @Test void staleAndMismatchedActivationsCannotBeRetargetedByRetry() {
        for (var reason : new EventInboxItem.Reason[]{EventInboxItem.Reason.TARGET_STALE, EventInboxItem.Reason.CONTRACT_MISMATCH}) {
            var ignored = EventInboxItem.receive(input, NOW).ignored(reason, NOW.plusSeconds(1));
            assertThatThrownBy(() -> ignored.retry("admin", "试图恢复", NOW.plusSeconds(2))).isInstanceOf(DomainException.class);
        }
    }
    @Test void trustChangesRequireReviewAndRejectTimeGoingBackwards() {
        var review = EventInboxItem.receive(input, NOW).sourceChanged(NOW.plusSeconds(10));
        assertThat(review.status()).isEqualTo(EventInboxItem.Status.REVIEW_REQUIRED);
        assertThatThrownBy(() -> review.retry("admin", "原配置恢复", NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new EventInboxItem(review.id(), input, 2, EventInboxItem.Status.CONSUMED,
                NOW, NOW, null, 0, EventInboxItem.Reason.SOURCE_CHANGED, null, null, null)).isInstanceOf(IllegalStateException.class);
    }
    @Test void envelopeRejectsImplicitVersionsAndUnboundedOrExpressionIdentities() {
        assertThatThrownBy(() -> new EventSignal(1, "demo", "erp", "GoodsAccepted", UUID.randomUUID(), 0, "wait", "accepted", 1)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new EventSignal(1, "demo", "${source}", "GoodsAccepted", UUID.randomUUID(), 1, "wait", "accepted", 1)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new EventSignal(2, "demo", "erp", "GoodsAccepted", UUID.randomUUID(), 1, "wait", "accepted", 1)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new EventSignal(1, "demo", "erp", "GoodsAccepted", UUID.randomUUID(), 1, "wait", "accepted", 0)).isInstanceOf(DomainException.class);
    }
}
