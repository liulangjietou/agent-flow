package io.agentflow.integration;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/**
 * 真实本地 HTTP 验证签名、重定向和完整响应等待上界。
 * @author owlzhangfq@gmail.com
 */
class WebhookTransportTest {
    @Test
    void receiverRetryAfterPreventsAnEarlyAutomaticRetry() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/limited", exchange -> {
            exchange.getResponseHeaders().set("Retry-After", "120");
            exchange.sendResponseHeaders(429, -1); exchange.close();
        });
        server.start();
        try {
            Instant started = Instant.now();
            var claimed = DeliveryProgress.pending(started).claim(started, "lease");
            var outcome = new HttpWebhookTransport().send(target(server, "/limited"), "event", "{}");
            var waiting = claimed.finish("lease", outcome, Instant.now());
            assertThat(waiting.httpStatus()).isEqualTo(429);
            assertThat(waiting.nextAttemptAt()).isAfterOrEqualTo(started.plusSeconds(120));
            assertThat(waiting.due(started.plusSeconds(119))).isFalse();
        } finally { server.stop(0); }
    }

    @Test
    void receivesHttpDateAndFallsBackOnRepeatedRetryAfterHeaders() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 固定月初日期，真实 HTTP 夹具也遵守 IMF-fixdate 的两位日期格式。
        Instant deadline = Instant.parse("2026-10-03T03:00:00Z");
        String date = "Sat, 03 Oct 2026 03:00:00 GMT";
        server.createContext("/date", exchange -> {
            exchange.getResponseHeaders().set("Retry-After", date);
            exchange.sendResponseHeaders(503, -1); exchange.close();
        });
        server.createContext("/repeated", exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "120");
            exchange.getResponseHeaders().add("Retry-After", "240");
            exchange.sendResponseHeaders(429, -1); exchange.close();
        });
        server.start();
        try {
            var sender = new HttpWebhookTransport();
            assertThat(sender.send(target(server, "/date"), "event", "{}").retryNotBefore()).isEqualTo(deadline);
            var repeated = sender.send(target(server, "/repeated"), "event", "{}");
            assertThat(repeated.httpStatus()).isEqualTo(429);
            assertThat(repeated.retryable()).isTrue();
            assertThat(repeated.retryNotBefore()).isNull();
        } finally { server.stop(0); }
    }

    @Test
    void signsRawBodyAndDoesNotFollowRedirects() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); var received = new AtomicInteger();
        String body = "{\"note\":\"中文\"}";
        server.createContext("/receive", exchange -> {
            received.incrementAndGet(); String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(raw).isEqualTo(body);
            assertThat(exchange.getRequestHeaders().getFirst("webhook-id")).isEqualTo("event-1");
            assertThat(exchange.getRequestHeaders().getFirst("webhook-signature")).isEqualTo(HttpWebhookTransport.signature(new byte[32], "event-1", exchange.getRequestHeaders().getFirst("webhook-timestamp"), body));
            exchange.sendResponseHeaders(204, -1); exchange.close();
        });
        server.createContext("/redirect", exchange -> { exchange.getResponseHeaders().set("Location", "/receive"); exchange.sendResponseHeaders(302, -1); exchange.close(); });
        server.start();
        try {
            var sender = new HttpWebhookTransport();
            assertThat(sender.send(target(server, "/receive"), "event-1", body).success()).isTrue();
            var redirected = sender.send(target(server, "/redirect"), "event-1", body);
            assertThat(redirected.httpStatus()).isEqualTo(302); assertThat(redirected.retryable()).isFalse(); assertThat(received).hasValue(1);
            // 独立已知向量，防止发送与验签双方共享同一个拼接错误。
            assertThat(HttpWebhookTransport.signature(new byte[32], "id", "123", "{}"))
                    .isEqualTo("v1,lsgJkuo3mNTTjT7mCvJI8NfEwUYy43jAnIx++IHEYOg=");
        } finally { server.stop(0); }
    }

    @Test
    void rejectsNetworkCallsInTransactionAndBoundsSlowResponseBody() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> { exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().write(1); exchange.getResponseBody().flush(); });
        server.start();
        try {
            var sender = new HttpWebhookTransport();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try { assertThatThrownBy(() -> sender.send(target(server, "/slow"), "event", "{}")).isInstanceOf(IllegalStateException.class); }
            finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
            assertThatCode(() -> org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
                var outcome = sender.send(target(server, "/slow"), "event", "{}");
                assertThat(outcome.success()).isFalse(); assertThat(outcome.retryable()).isTrue();
            })).doesNotThrowAnyException();
        } finally { server.stop(0); }
    }

    @Test
    void validatesDeploymentDestinationsAndRedactsSecrets() {
        String secret = "whsec_" + Base64.getEncoder().encodeToString(new byte[32]);
        var config = new WebhookConfiguration();
        assertThat(new WebhookTargets(config, true).enabled("demo")).isEmpty();
        for (String url : new String[]{"http://localhost:9000", "https://u:p@example.com", "https://example.com?secret=x", "https://example.com#x", "file:///tmp/x"}) {
            config.setTargets(Map.of("erp", new WebhookConfiguration.Target("demo", "ERP", url, secret, true)));
            assertThatThrownBy(() -> new WebhookTargets(config, false)).hasMessage("Invalid webhook target configuration");
        }
        config.setAllowInsecureHttpInDemo(true);
        config.setTargets(Map.of("erp", new WebhookConfiguration.Target("demo", "ERP", "http://localhost:9000", secret, true)));
        assertThatThrownBy(() -> new WebhookTargets(config, false)).isInstanceOf(IllegalStateException.class);
        var targets = new WebhookTargets(config, true);
        assertThat(targets.enabled("other")).isEmpty(); assertThat(targets.find("other", "erp")).isEmpty();
        assertThat(targets.enabled("demo").get(0).toString()).doesNotContain(secret, "localhost");
        assertThat(config.getTargets().get("erp").toString()).doesNotContain(secret, "localhost");
    }
    private WebhookTargets.Destination target(HttpServer server, String path) {
        return new WebhookTargets.Destination("local", "demo", "Local", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path), new byte[32], true, "digest");
    }
}
