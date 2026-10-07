package io.agentflow.integration;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.JsonUtil;
import io.agentflow.observability.DiagnosticContext;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 原签名正文不重写；业务号从原索引读取，实例只使用匹配信封声明的原轮次。 */
class WebhookBusinessTraceTest {
    private static final String TENANT = "tenant-a", BUSINESS = "ORIGINAL-WEBHOOK", INSTANCE = "original-instance", TASK = "original-task";
    private final UUID applicationId = UUID.randomUUID(), deliveryId = UUID.randomUUID();
    private final String eventId = UUID.randomUUID().toString(), traceId = UUID.randomUUID().toString();
    private final JsonUtil json = new JsonUtil(new ObjectMapper());
    private JdbcTemplate jdbc;

    @BeforeEach void schema() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:webhook-business-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE approval_application(tenant_id VARCHAR(64),id VARCHAR(36),business_no VARCHAR(128),round_no INT)");
        jdbc.execute("CREATE TABLE approval_submission_round(tenant_id VARCHAR(64),application_id VARCHAR(36),round_no INT,process_instance_id VARCHAR(128))");
    }

    @AfterEach void cleanup() { MDC.clear(); jdbc.execute("DROP ALL OBJECTS"); }

    @ParameterizedTest @EnumSource(Scenario.class)
    void retryUsesOriginalBusinessWithoutChangingSignedBytesOrBorrowingCurrentRound(Scenario scenario) {
        source(TENANT, scenario != Scenario.ORIGINAL_ROUND_MISSING);
        var observed = sendTwice(body(scenario));
        assertThat(observed).hasSize(2).allSatisfy(context -> {
            assertThat(context).containsEntry("tenantId", TENANT).containsEntry("businessNo", BUSINESS)
                    .containsEntry("traceId", scenario == Scenario.MALFORMED ? DiagnosticContext.legacyId("webhook-event", TENANT, eventId) : traceId);
            if (scenario == Scenario.NORMAL || scenario == Scenario.APPLICATION_EVENT) assertThat(context).containsEntry("processInstanceId", INSTANCE);
            else assertThat(context).doesNotContainKey("processInstanceId");
            if (scenario == Scenario.NORMAL || scenario == Scenario.ORIGINAL_ROUND_MISSING) assertThat(context).containsEntry("taskId", TASK);
            else assertThat(context).doesNotContainKey("taskId");
        });
    }

    @Test void aDifferentTenantWithTheSameApplicationIdCannotSupplyBusinessFacts() {
        source("tenant-b", true);
        assertThat(sendTwice(body(Scenario.NORMAL))).allSatisfy(context -> assertThat(context)
                .containsEntry("tenantId", TENANT).containsEntry("traceId", traceId)
                .doesNotContainKeys("businessNo", "processInstanceId", "taskId"));
    }

    private void source(String tenant, boolean originalRound) {
        jdbc.update("INSERT INTO approval_application VALUES(?,?,?,2)", tenant, applicationId.toString(), BUSINESS);
        jdbc.update("INSERT INTO approval_submission_round VALUES(?,?,2,'new-instance')", tenant, applicationId.toString());
        if (originalRound) jdbc.update("INSERT INTO approval_submission_round VALUES(?,?,1,?)", tenant, applicationId.toString(), INSTANCE);
    }

    private String body(Scenario scenario) {
        if (scenario == Scenario.MALFORMED) return "unreadable-private-body-sentinel";
        var payload = new LinkedHashMap<String, Object>();
        payload.put("applicationId", scenario == Scenario.APPLICATION_MISMATCH ? UUID.randomUUID().toString() : applicationId.toString());
        if (scenario != Scenario.ROUND_UNDECLARED) payload.put("roundNo", scenario == Scenario.ROUND_NOT_INTEGER ? "1" : 1);
        if (scenario != Scenario.APPLICATION_EVENT) payload.put("taskId", TASK);
        payload.put("comment", "private-body-sentinel");
        return json.write(Map.of("tenantId", scenario == Scenario.TENANT_MISMATCH ? "tenant-b" : TENANT, "traceId", traceId, "payload", payload));
    }

    private List<Map<String, String>> sendTwice(String body) {
        var store = spy(new JdbcWebhookStore(jdbc)); var targets = mock(WebhookTargets.class); var now = Instant.now();
        var delivery = new JdbcWebhookStore.Delivery(deliveryId, TENANT, "local", "digest", eventId, "TaskActionAccepted", applicationId,
                1, body, now, now, DeliveryProgress.pending(now).claim(now, "lease"));
        doReturn(List.of(deliveryId)).when(store).due(any()); doReturn(delivery).when(store).claim(eq(deliveryId), any());
        doReturn(true).when(store).finish(eq(delivery), any(), any());
        when(targets.find(TENANT, "local")).thenReturn(Optional.of(new WebhookTargets.Destination("local", TENANT, "Local",
                URI.create("http://127.0.0.1"), new byte[32], true, "digest")));
        var observed = new ArrayList<Map<String, String>>(); var calls = new AtomicInteger();
        var logger = (Logger) LoggerFactory.getLogger(WebhookWorker.class);
        var events = new ListAppender<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) { event.prepareForDeferredProcessing(); super.append(event); }
        };
        events.start(); logger.addAppender(events);
        try (var outer = new DiagnosticContext(UUID.randomUUID().toString(), "caller", "foreign-business", "new-instance", "foreign-task").open()) {
            var previous = MDC.getCopyOfContextMap();
            var worker = new WebhookWorker(store, targets, (destination, event, sentBody) -> {
                assertThat(sentBody).isEqualTo(body); assertThat(event).isEqualTo(eventId); observed.add(MDC.getCopyOfContextMap());
                if (calls.incrementAndGet() == 1) throw new IllegalStateException("private-external-response");
                return DeliveryProgress.Outcome.http(204);
            }, json);
            worker.poll(); worker.poll();
            assertThat(MDC.getCopyOfContextMap()).isEqualTo(previous);
            assertThat(events.list).hasSize(2).allSatisfy(event -> {
                assertThat(event.getMDCPropertyMap()).isEqualTo(observed.get(0));
                assertThat(event.getFormattedMessage()).doesNotContain("private-body-sentinel", "private-external-response");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(events); events.stop(); }
        verify(store).finish(eq(delivery), any(), any()); assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        return observed;
    }

    private enum Scenario { NORMAL, APPLICATION_EVENT, ORIGINAL_ROUND_MISSING, TENANT_MISMATCH, APPLICATION_MISMATCH, MALFORMED, ROUND_NOT_INTEGER, ROUND_UNDECLARED }
}
