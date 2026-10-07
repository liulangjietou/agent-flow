package io.agentflow.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.JsonUtil;
import io.agentflow.observability.DiagnosticContext;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 旧正文与失败恢复不能改变签名输入，也不能把前一租户留在后续任务。
 * @author owlzhangfq@gmail.com
 */
class WebhookWorkerTraceTest {
    @Test
    void legacyBodiesAndFailedAttemptsKeepStableTenantScopedTraceWithoutLeakingOtherContext() {
        var store = mock(JdbcWebhookStore.class);
        var targets = mock(WebhookTargets.class);
        var json = new JsonUtil(new ObjectMapper());
        Instant now = Instant.now();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        when(store.due(any())).thenReturn(List.of(a, b));
        var first = delivery(a, "a", "legacy-private-body", now);
        var second = delivery(b, "b", "{\"tenantId\":\"forged\",\"traceId\":\"private-invalid-trace\"}", now);
        when(store.claim(eq(a), any())).thenReturn(first);
        when(store.claim(eq(b), any())).thenReturn(second);
        for (String tenant : List.of("a", "b")) when(targets.find(tenant, "local")).thenReturn(Optional.of(
                new WebhookTargets.Destination("local", tenant, "Local", URI.create("http://127.0.0.1"), new byte[32], true, "digest")));
        var seen = new AtomicInteger();
        String outerTrace = UUID.randomUUID().toString();
        try (var scope = new DiagnosticContext(outerTrace, "outer").open()) {
            var worker = new WebhookWorker(store, targets, (target, event, body) -> {
                seen.incrementAndGet();
                assertThat(MDC.getCopyOfContextMap()).containsEntry("tenantId", target.tenantId())
                        .containsEntry("traceId", DiagnosticContext.legacyId("webhook-event", target.tenantId(), event));
                assertThat(body).isEqualTo(target.tenantId().equals("a") ? first.body() : second.body());
                if (target.tenantId().equals("a")) throw new IllegalStateException("private-external-response");
                return DeliveryProgress.Outcome.http(204);
            }, json);
            worker.poll();
            worker.poll();
            assertThat(seen).hasValue(4);
            assertThat(MDC.getCopyOfContextMap()).containsExactlyInAnyOrderEntriesOf(Map.of("traceId", outerTrace, "tenantId", "outer"));
        }
        verify(store, never()).finish(eq(first), any(), any());
        verify(store, times(2)).finish(eq(second), any(), any());
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    private static JdbcWebhookStore.Delivery delivery(UUID id, String tenant, String body, Instant now) {
        return new JdbcWebhookStore.Delivery(id, tenant, "local", "digest", "same-event", "ApplicationSubmitted", UUID.randomUUID(),
                1, body, now, now, DeliveryProgress.pending(now).claim(now, "lease"));
    }
}
