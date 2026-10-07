package io.agentflow.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.JsonUtil;
import io.agentflow.event.*;
import io.agentflow.finance.callback.*;
import io.agentflow.notification.*;
import io.agentflow.servicetask.*;
import io.agentflow.signature.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.stubbing.Answer;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 真实候选 SQL 核对原业务、原轮次及通知归属；完整业务约束由各集成模块的持久化和公开接口测试覆盖。
 * @author owlzhangfq@gmail.com
 */
class IntegrationBusinessTraceTest {
    private static final String TENANT = "tenant-a", BUSINESS = "ORIGINAL-INTEGRATION", INSTANCE = "original-instance", TASK = "original-task";
    private final UUID applicationId = UUID.randomUUID(), runId = UUID.randomUUID(), inboxId = UUID.randomUUID();
    private final String traceId = UUID.randomUUID().toString();
    private final JsonUtil json = new JsonUtil(new ObjectMapper());
    private JdbcTemplate jdbc;

    @BeforeEach void schema() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:integration-business-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE approval_application(tenant_id VARCHAR(64),id VARCHAR(36),business_no VARCHAR(128),round_no INT)");
        jdbc.execute("CREATE TABLE approval_submission_round(tenant_id VARCHAR(64),application_id VARCHAR(36),round_no INT,process_instance_id VARCHAR(128))");
        jdbc.execute("CREATE TABLE notification_inbox(tenant_id VARCHAR(64),id VARCHAR(36),recipient_id VARCHAR(128),application_id VARCHAR(36),business_no VARCHAR(128),round_no INT,task_id VARCHAR(128))");
        for (String table : new String[]{"payment_authorization", "supplier_payment_authorization"})
            jdbc.execute("CREATE TABLE " + table + "(tenant_id VARCHAR(64),id VARCHAR(36),application_id VARCHAR(36),round_no INT)");
        for (String table : new String[]{"service_task_operation", "signature_operation", "event_inbox", "payment_callback", "notification_dispatch"}) {
            jdbc.execute("CREATE TABLE " + table + "(tenant_id VARCHAR(64),id VARCHAR(36),application_id VARCHAR(36),round_no INT,process_instance_id VARCHAR(128),"
                    + "recipient_id VARCHAR(128),inbox_id VARCHAR(36),payment_kind VARCHAR(16),employee_payment_id VARCHAR(36),supplier_payment_id VARCHAR(36),trace_id VARCHAR(36),"
                    + "status VARCHAR(32),progress VARCHAR(16),active_guard INT,created_at TIMESTAMP,updated_at TIMESTAMP,received_at TIMESTAMP,poll_at TIMESTAMP,next_attempt_at TIMESTAMP,lease_until TIMESTAMP)");
        }
    }

    @AfterEach void cleanup() { MDC.clear(); jdbc.execute("DROP ALL OBJECTS"); }

    @ParameterizedTest @EnumSource(Kind.class)
    void executionAndFailureKeepOriginalBusinessAfterApplicationMovesOn(Kind kind) {
        source(TENANT, true); enqueue(kind);
        var observed = new ArrayList<Map<String, String>>();
        var harness = worker(kind, invocation -> { observed.add(MDC.getCopyOfContextMap()); throw new IllegalStateException("private-integration-sentinel"); });
        var logger = (Logger) LoggerFactory.getLogger(harness.loggerType());
        var events = new ListAppender<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) { event.prepareForDeferredProcessing(); super.append(event); }
        };
        events.start(); logger.addAppender(events);
        try (var outer = new DiagnosticContext(UUID.randomUUID().toString(), "caller", "foreign-business", "new-instance", "foreign-task").open()) {
            var previous = MDC.getCopyOfContextMap();
            harness.poll().run(); harness.poll().run();
            assertThat(MDC.getCopyOfContextMap()).isEqualTo(previous);
            assertThat(observed).hasSize(2).allSatisfy(context -> expected(context, kind, true));
            assertThat(events.list).filteredOn(event -> event.getLevel() == Level.ERROR).hasSize(2).allSatisfy(event -> {
                expected(event.getMDCPropertyMap(), kind, true);
                assertThat(event.getFormattedMessage()).doesNotContain("private-integration-sentinel");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(events); events.stop(); }
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void missingOriginalRoundDoesNotBorrowCurrentInstance(Kind kind) {
        source(TENANT, false); enqueue(kind);
        expected(context(kind), kind, false);
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void anotherTenantCannotSupplyBusinessFacts(Kind kind) {
        source("tenant-b", true); enqueue(kind); missing(context(kind));
    }

    @Test void notificationMustStillReferenceTheOriginalRecipient() {
        source(TENANT, true); enqueue(Kind.NOTIFICATION);
        jdbc.update("UPDATE notification_inbox SET recipient_id='another-recipient'");
        missing(context(Kind.NOTIFICATION));
    }

    @ParameterizedTest @EnumSource(value = Kind.class, names = {"EMPLOYEE_CALLBACK", "SUPPLIER_CALLBACK"})
    void sameAuthorizationIdOfAnotherPaymentKindCannotReplaceOriginalSource(Kind kind) {
        source(TENANT, true); enqueue(kind);
        var other = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_application VALUES(?,?,'wrong-kind-business',2)", TENANT, other);
        jdbc.update("INSERT INTO " + otherAuthorization(kind) + " VALUES(?,?,?,2)", TENANT, runId.toString(), other);
        expected(context(kind), kind, true);
    }

    @ParameterizedTest @EnumSource(value = Kind.class, names = {"EMPLOYEE_CALLBACK", "SUPPLIER_CALLBACK"})
    void originalAuthorizationRequiresTheSameTenantEvenWhenAnotherKindExists(Kind kind) {
        source(TENANT, true); enqueue(kind);
        jdbc.update("UPDATE " + authorization(kind) + " SET tenant_id='tenant-b'");
        jdbc.update("INSERT INTO " + otherAuthorization(kind) + " VALUES(?,?,?,1)", TENANT, runId.toString(), applicationId.toString());
        missing(context(kind));
    }

    private Map<String, String> context(Kind kind) {
        var observed = new ArrayList<Map<String, String>>();
        worker(kind, invocation -> { observed.add(MDC.getCopyOfContextMap()); return null; }).poll().run();
        assertThat(observed).hasSize(1); return observed.get(0);
    }

    private void missing(Map<String, String> context) {
        assertThat(context).containsEntry("tenantId", TENANT).containsEntry("traceId", traceId)
                .doesNotContainKeys("businessNo", "processInstanceId", "taskId");
    }

    private void expected(Map<String, String> context, Kind kind, boolean hasRound) {
        assertThat(context).containsEntry("tenantId", TENANT).containsEntry("traceId", traceId).containsEntry("businessNo", BUSINESS);
        // 服务任务原生实例已在激活记录中冻结；不需要从当前轮次补造。
        if (hasRound || kind == Kind.SERVICE) assertThat(context).containsEntry("processInstanceId", INSTANCE);
        else assertThat(context).doesNotContainKey("processInstanceId");
        if (kind == Kind.NOTIFICATION) assertThat(context).containsEntry("taskId", TASK);
        else assertThat(context).doesNotContainKey("taskId");
    }

    private void source(String tenant, boolean originalRound) {
        jdbc.update("INSERT INTO approval_application VALUES(?,?,?,2)", tenant, applicationId.toString(), BUSINESS);
        jdbc.update("INSERT INTO approval_submission_round VALUES(?,?,2,'new-instance')", tenant, applicationId.toString());
        if (originalRound) jdbc.update("INSERT INTO approval_submission_round VALUES(?,?,1,?)", tenant, applicationId.toString(), INSTANCE);
        jdbc.update("INSERT INTO notification_inbox VALUES(?,?,'recipient',?,?,1,?)", TENANT, inboxId.toString(), applicationId.toString(), BUSINESS, TASK);
    }

    private void enqueue(Kind kind) {
        String table = switch (kind) {
            case SERVICE -> "service_task_operation"; case SIGNATURE -> "signature_operation"; case NOTIFICATION -> "notification_dispatch";
            case EVENT -> "event_inbox"; case EMPLOYEE_CALLBACK, SUPPLIER_CALLBACK -> "payment_callback";
        };
        if (kind == Kind.EMPLOYEE_CALLBACK || kind == Kind.SUPPLIER_CALLBACK)
            jdbc.update("INSERT INTO " + authorization(kind) + " VALUES(?,?,?,1)", TENANT, runId.toString(), applicationId.toString());
        var now = Timestamp.from(Instant.now().minusSeconds(1));
        jdbc.update("INSERT INTO " + table + "(tenant_id,id,application_id,round_no,process_instance_id,recipient_id,inbox_id,payment_kind,employee_payment_id,supplier_payment_id,trace_id,status,progress,active_guard,created_at,updated_at,received_at,poll_at,next_attempt_at) VALUES(?,?,?,1,?,'recipient',?,?,?,?,?,'PENDING','PENDING',1,?,?,?,?,?)",
                TENANT, runId.toString(), applicationId.toString(), INSTANCE, inboxId.toString(), kind == Kind.SUPPLIER_CALLBACK ? "SUPPLIER" : "EMPLOYEE",
                kind == Kind.EMPLOYEE_CALLBACK ? runId.toString() : null, kind == Kind.SUPPLIER_CALLBACK ? runId.toString() : null, traceId, now, now, now, now, now);
    }

    private String authorization(Kind kind) { return kind == Kind.EMPLOYEE_CALLBACK ? "payment_authorization" : "supplier_payment_authorization"; }
    private String otherAuthorization(Kind kind) { return kind == Kind.EMPLOYEE_CALLBACK ? "supplier_payment_authorization" : "payment_authorization"; }

    private Harness worker(Kind kind, Answer<Object> observation) {
        return switch (kind) {
            case SERVICE -> {
                var service = mock(ServiceTaskOperationService.class); doAnswer(observation).when(service).claim(anyString(), any(), any());
                yield new Harness(new ServiceTaskWorker(new JdbcServiceTaskOperationRepository(jdbc, json), service, mock(ServiceTaskGateway.class))::poll, ServiceTaskWorker.class);
            }
            case SIGNATURE -> {
                var service = mock(SignatureOperationService.class); doAnswer(observation).when(service).claim(anyString(), any(), any());
                yield new Harness(new SignatureWorker(new JdbcSignatureOperationRepository(jdbc, json), mock(JdbcSignatureEvidenceRepository.class), service, mock(SignatureGateway.class))::poll, SignatureWorker.class);
            }
            case NOTIFICATION -> {
                var service = mock(NotificationDeliveryService.class); doAnswer(observation).when(service).claim(any(), any());
                yield new Harness(new NotificationDeliveryWorker(new JdbcNotificationDeliveryStore(jdbc), mock(SmtpNotificationTransport.class), mock(WeComNotificationTransport.class), service)::runOnce, NotificationDeliveryWorker.class);
            }
            case EVENT -> {
                var repository = spy(new JdbcEventInboxRepository(jdbc, json));
                var signal = new EventSignal(1, TENANT, "source", "done", applicationId, 1, "original-wait", "contract", 1);
                var item = EventInboxItem.receive(new ReceivedEvent("event", "1".repeat(64), 1, signal), Instant.now());
                doReturn(item).when(repository).get(TENANT, runId);
                var service = mock(EventInboxService.class); doAnswer(observation).when(service).process(any(), any());
                yield new Harness(new EventInboxWorker(repository, service)::poll, EventInboxWorker.class);
            }
            case EMPLOYEE_CALLBACK, SUPPLIER_CALLBACK -> {
                var repository = spy(new JdbcPaymentCallbackRepository(jdbc, json));
                doAnswer(invocation -> { observation.answer(invocation); return mock(PaymentCallback.class); }).when(repository).get(anyString(), any());
                yield new Harness(new PaymentCallbackWorker(repository, mock(PaymentCallbackService.class))::poll, PaymentCallbackWorker.class);
            }
        };
    }

    /**
     * @author owlzhangfq@gmail.com
     */
    private record Harness(Runnable poll, Class<?> loggerType) { }
    /**
     * @author owlzhangfq@gmail.com
     */
    private enum Kind { SERVICE, SIGNATURE, NOTIFICATION, EVENT, EMPLOYEE_CALLBACK, SUPPLIER_CALLBACK }
}
