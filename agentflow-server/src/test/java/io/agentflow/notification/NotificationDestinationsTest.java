package io.agentflow.notification;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static io.agentflow.notification.NotificationDeliveryConfiguration.*;
import static org.assertj.core.api.Assertions.*;

/** 部署白名单、租户隔离和原目的地摘要不能被个人偏好扩大。 @author owlzhangfq@gmail.com */
class NotificationDestinationsTest {
    @Test void identityAndTargetChangesInvalidateOldDigestButCredentialRotationDoesNot() {
        var config = configuration("first-secret");
        var first = destination(config);
        assertThat(new NotificationDestinations(config, false).find("other", "alice", NotificationChannel.EMAIL)).isEmpty();
        assertThat(first.toString()).doesNotContain("example", "first-secret", "alice");
        config.setSmtpServers(configuration("rotated-secret").getSmtpServers());
        assertThat(destination(config).digest()).isEqualTo(first.digest());
        config.setBindings(Map.of("alice-email", new Binding("demo", "alice", NotificationChannel.EMAIL, "mail", "replacement@example.invalid", true)));
        assertThat(destination(config).digest()).isNotEqualTo(first.digest());
        config = configuration("first-secret"); config.setPublicUrl("https://different.example.invalid/");
        assertThat(destination(config).digest()).isNotEqualTo(first.digest());
    }

    @Test void plaintextRequiresExplicitDemoAndLiteralLoopback() {
        var config = configuration("secret");
        config.setSmtpServers(Map.of("mail", new SmtpServer("demo", "127.0.0.1", 2525, Security.DEMO_PLAIN, null, null, "notify@example.invalid", true)));
        assertThatThrownBy(() -> new NotificationDestinations(config, true)).isInstanceOf(IllegalStateException.class);
        config.setAllowInsecureInDemo(true);
        assertThatThrownBy(() -> new NotificationDestinations(config, false)).isInstanceOf(IllegalStateException.class);
        assertThat(new NotificationDestinations(config, true).find("demo", "alice", NotificationChannel.EMAIL)).isPresent();
        config.setSmtpServers(Map.of("mail", new SmtpServer("demo", "mail.example.invalid", 2525, Security.DEMO_PLAIN, null, null, "notify@example.invalid", true)));
        assertThatThrownBy(() -> new NotificationDestinations(config, true)).isInstanceOf(IllegalStateException.class);
    }

    @Test void malformedRecipientsCrossTenantProvidersAndDuplicateBindingsFailClosed() {
        for (String address : java.util.List.of("alice@example.invalid,bob@example.invalid", "Alice <alice@example.invalid>", "alice@example.invalid\r\nBcc:other@example.invalid", "team:alice@example.invalid;", "invalid")) {
            var config = configuration("secret");
            config.setBindings(Map.of("alice-email", new Binding("demo", "alice", NotificationChannel.EMAIL, "mail", address, true)));
            assertThatThrownBy(() -> new NotificationDestinations(config, false)).hasMessage("Invalid notification delivery configuration");
        }
        var crossTenant = configuration("secret");
        crossTenant.setBindings(Map.of("other", new Binding("another", "alice", NotificationChannel.EMAIL, "mail", "alice@example.invalid", true)));
        assertThatThrownBy(() -> new NotificationDestinations(crossTenant, false)).isInstanceOf(IllegalStateException.class);
        var duplicates = configuration("secret"); var original = duplicates.getBindings().get("alice-email");
        duplicates.setBindings(Map.of("one", original, "two", original));
        assertThatThrownBy(() -> new NotificationDestinations(duplicates, false)).isInstanceOf(IllegalStateException.class);
    }

    @Test void disabledTargetsAndMissingImAdapterDoNotPretendToBeConfigured() {
        var config = configuration("secret");
        config.setBindings(Map.of("alice-email", new Binding("demo", "alice", NotificationChannel.EMAIL, "mail", "alice@example.invalid", false)));
        assertThat(destination(config).enabled()).isFalse();
        assertThat(new NotificationDestinations(config, false).find("demo", "alice", NotificationChannel.ENTERPRISE_IM)).isEmpty();
        config.setBindings(Map.of("alice-im", new Binding("demo", "alice", NotificationChannel.ENTERPRISE_IM, "mail", "alice@example.invalid", true)));
        assertThatThrownBy(() -> new NotificationDestinations(config, false)).isInstanceOf(IllegalStateException.class);
    }

    static NotificationDeliveryConfiguration configuration(String password) {
        var config = new NotificationDeliveryConfiguration(); config.setPublicUrl("https://approval.example.invalid/");
        config.setSmtpServers(Map.of("mail", new SmtpServer("demo", "smtp.example.invalid", 587, Security.STARTTLS, "sender", password, "notify@example.invalid", true)));
        config.setBindings(Map.of("alice-email", new Binding("demo", "alice", NotificationChannel.EMAIL, "mail", "alice@example.invalid", true)));
        return config;
    }
    private static NotificationDestinations.Destination destination(NotificationDeliveryConfiguration config) {
        return new NotificationDestinations(config, false).find("demo", "alice", NotificationChannel.EMAIL).orElseThrow();
    }
}
