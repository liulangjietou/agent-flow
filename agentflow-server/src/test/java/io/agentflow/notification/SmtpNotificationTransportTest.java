package io.agentflow.notification;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.agentflow.notification.NotificationDeliveryConfiguration.*;
import static io.agentflow.notification.NotificationDeliveryProgress.*;
import static org.assertj.core.api.Assertions.*;

/** 验证实际 SMTP 命令和最终 DATA 回执，不以模拟 send 方法代替协议验收。
 * @author owlzhangfq@gmail.com
 */
class SmtpNotificationTransportTest {
    private final SmtpNotificationTransport transport = new SmtpNotificationTransport();

    @Test void acceptedMailHasOnlyMinimalTextAndStableOriginalMessageIdentity() throws Exception {
        try (var smtp = new LocalSmtpServer(LocalSmtpServer.Mode.ACCEPT)) {
            var target = target(smtp.port(), Security.DEMO_PLAIN, false); var delivery = delivery(target);
            assertThat(transport.send(target, delivery).result()).isEqualTo(Result.ACCEPTED);
            assertThat(transport.send(target, delivery).result()).isEqualTo(Result.ACCEPTED);
            assertThat(smtp.messages).hasSize(2);
            var first = mail(smtp.messages.get(0)); var second = mail(smtp.messages.get(1));
            assertThat(first.getMessageID()).isEqualTo(second.getMessageID()).contains(delivery.id().toString());
            assertThat(first.getAllRecipients()).hasSize(1);
            assertThat(first.getSubject()).isEqualTo("AgentFlow 站内消息提醒");
            assertThat(first.getContent().toString()).contains("消息中心", target.publicUrl()).doesNotContain(delivery.inboxId().toString(), "alice", "金额");
        }
    }

    @Test void explicitRecipientOrDataRejectionIsClassifiedWithoutRetryingPermanentFailure() throws Exception {
        for (var mode : java.util.List.of(LocalSmtpServer.Mode.REJECT_RECIPIENT_TEMPORARY, LocalSmtpServer.Mode.REJECT_DATA_TEMPORARY,
                LocalSmtpServer.Mode.REJECT_RECIPIENT_PERMANENT, LocalSmtpServer.Mode.REJECT_DATA_PERMANENT)) {
            try (var smtp = new LocalSmtpServer(mode)) {
                var target = target(smtp.port(), Security.DEMO_PLAIN, false);
                var outcome = transport.send(target, delivery(target));
                boolean temporary = mode.name().endsWith("TEMPORARY");
                assertThat(outcome.result()).as(mode.name()).isEqualTo(temporary ? Result.RETRYABLE : Result.FAILED);
                assertThat(outcome.code()).isEqualTo(temporary ? FailureCode.SMTP_TEMPORARY_REJECTION : FailureCode.SMTP_PERMANENT_REJECTION);
                if (mode.name().startsWith("REJECT_RECIPIENT")) assertThat(smtp.messages).isEmpty();
            }
        }
    }

    @Test void disconnectAndTimeoutAfterDataStayUnknownEvenThoughFixtureSawMessage() throws Exception {
        for (var mode : java.util.List.of(LocalSmtpServer.Mode.DISCONNECT_AFTER_DATA, LocalSmtpServer.Mode.WAIT_AFTER_DATA)) {
            try (var smtp = new LocalSmtpServer(mode)) {
                var target = target(smtp.port(), Security.DEMO_PLAIN, false); var start = Instant.now();
                assertThat(transport.send(target, delivery(target))).isEqualTo(Outcome.unknown(FailureCode.SMTP_RESULT_UNKNOWN));
                assertThat(smtp.messages).hasSize(1);
                assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(8));
            }
        }
    }

    @Test void missingStartTlsDoesNotFallBackToPlaintextMail() throws Exception {
        try (var smtp = new LocalSmtpServer(LocalSmtpServer.Mode.ACCEPT)) {
            var target = target(smtp.port(), Security.STARTTLS, false);
            assertThat(transport.send(target, delivery(target)).result()).isEqualTo(Result.RETRYABLE);
            assertThat(smtp.messages).isEmpty(); assertThat(smtp.commands).noneMatch(value -> value.startsWith("MAIL FROM"));
            var properties = SmtpNotificationTransport.properties(target.server());
            assertThat(properties.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
            assertThat(properties.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
            assertThat(properties).doesNotContainKey("mail.smtp.ssl.trust");
        }
    }

    @Test void authenticationRejectionNeverStartsMail() throws Exception {
        try (var smtp = new LocalSmtpServer(LocalSmtpServer.Mode.AUTH_REJECT)) {
            var target = target(smtp.port(), Security.DEMO_PLAIN, true);
            assertThat(transport.send(target, delivery(target))).isEqualTo(Outcome.failed(FailureCode.SMTP_AUTH_FAILED));
            assertThat(smtp.messages).isEmpty(); assertThat(smtp.commands).noneMatch(value -> value.startsWith("MAIL FROM"));
        }
    }

    static NotificationDestinations.Destination target(int port, Security security, boolean auth) {
        var config = new NotificationDeliveryConfiguration(); config.setAllowInsecureInDemo(true); config.setPublicUrl("http://127.0.0.1:15333/");
        config.setSmtpServers(Map.of("mail", new SmtpServer("demo", "127.0.0.1", port, security, auth ? "sender" : null, auth ? "test-password" : null, "notify@example.invalid", true)));
        config.setBindings(Map.of("alice-email", new Binding("demo", "alice", NotificationChannel.EMAIL, "mail", "alice@example.invalid", true)));
        return new NotificationDestinations(config, true).find("demo", "alice", NotificationChannel.EMAIL).orElseThrow();
    }
    static NotificationDelivery delivery(NotificationDestinations.Destination target) {
        var now = Instant.now(); return new NotificationDelivery(UUID.randomUUID(), "demo", "alice", UUID.randomUUID(), NotificationChannel.EMAIL, 1,
                target.id(), target.digest(), now, pending(now).start(now, UUID.randomUUID()));
    }
    private static MimeMessage mail(String raw) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(raw.getBytes(StandardCharsets.US_ASCII)));
    }
}
