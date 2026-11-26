package io.agentflow.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证空转指数退避上限、任务处理后清零及主动唤醒行为。
 * @author owlzhangfq@gmail.com
 */
class PollingBackoffRegistryTest {
    @Test
    void backsOffOnEmptyPollsAndResetsImmediatelyOnWorkOrWake() {
        var millis = new AtomicLong(10_000L);
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
        };
        var registry = new PollingBackoffRegistry(clock, 1000L, 5000L);
        assertThat(registry.shouldPoll("notifications")).isTrue();
        registry.record("notifications", false);
        assertThat(registry.emptyStreak("notifications")).isEqualTo(1);
        assertThat(registry.shouldPoll("notifications")).isFalse();
        millis.addAndGet(999L);
        assertThat(registry.shouldPoll("notifications")).isFalse();
        millis.addAndGet(1L);
        assertThat(registry.shouldPoll("notifications")).isTrue();
        registry.record("notifications", false);
        assertThat(registry.emptyStreak("notifications")).isEqualTo(2);
        millis.addAndGet(1500L);
        assertThat(registry.shouldPoll("notifications")).isFalse();
        registry.wake("notifications");
        assertThat(registry.emptyStreak("notifications")).isZero();
        assertThat(registry.shouldPoll("notifications")).isTrue();
        registry.record("notifications", false);
        registry.record("notifications", true);
        assertThat(registry.emptyStreak("notifications")).isZero();
        assertThat(registry.shouldPoll("notifications")).isTrue();
    }
}
