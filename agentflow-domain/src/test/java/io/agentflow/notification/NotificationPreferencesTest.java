package io.agentflow.notification;

import io.agentflow.common.DomainException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 渠道同意的撤销与版本规则，不依赖投递基础设施。 @author owlzhangfq@gmail.com */
class NotificationPreferencesTest {
    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    @Test void defaultsAreOffAndUnchangedSelectionDoesNotInventHistory() {
        var original = NotificationPreferences.defaults("tenant", "alice");
        assertThat(original.emailEnabled()).isFalse(); assertThat(original.enterpriseImEnabled()).isFalse();
        assertThat(original.updatedAt()).isNull(); assertThat(original.version()).isZero();
        assertThat(original.revise(0, false, false, NOW)).isSameAs(original);
        assertThatThrownBy(() -> original.revise(1, false, false, NOW)).isInstanceOf(DomainException.class);
    }

    @Test void reenablingCannotAuthorizeAnOldDispatchAndOtherChannelKeepsItsConsent() {
        var first = NotificationPreferences.defaults("tenant", "alice").revise(0, true, true, NOW);
        var off = first.revise(1, false, true, NOW.plusSeconds(1));
        var again = off.revise(2, true, true, NOW.plusSeconds(2));
        assertThat(again.permits("tenant", "alice", NotificationChannel.EMAIL, first.emailGeneration())).isFalse();
        assertThat(again.permits("tenant", "alice", NotificationChannel.ENTERPRISE_IM, first.enterpriseImGeneration())).isTrue();
        assertThat(again.emailGeneration()).isEqualTo(3); assertThat(again.version()).isEqualTo(3);
    }

    @Test void consentNeverCrossesUserOrTenantAndStaleWritesCannotOverwrite() {
        var current = NotificationPreferences.defaults("tenant", "alice").revise(0, true, false, NOW);
        assertThat(current.permits("other", "alice", NotificationChannel.EMAIL, 1)).isFalse();
        assertThat(current.permits("tenant", "bob", NotificationChannel.EMAIL, 1)).isFalse();
        assertThat(current.permits("tenant", "alice", NotificationChannel.ENTERPRISE_IM, 0)).isFalse();
        assertThatThrownBy(() -> current.revise(0, false, true, NOW)).isInstanceOf(DomainException.class);
    }
}
