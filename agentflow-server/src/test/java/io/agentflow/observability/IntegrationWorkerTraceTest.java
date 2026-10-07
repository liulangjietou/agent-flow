package io.agentflow.observability;

import io.agentflow.servicetask.*;
import io.agentflow.signature.*;
import io.agentflow.organization.*;
import io.agentflow.notification.*;
import io.agentflow.event.*;
import io.agentflow.finance.callback.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.stubbing.Answer;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 集成工作器验证领取失败、旧记录及线程复用，不把诊断上下文作为授权主体。
 * @author owlzhangfq@gmail.com
 */
class IntegrationWorkerTraceTest {
    private static final UUID FAILED = UUID.randomUUID(), LEGACY = UUID.randomUUID();
    private static final String SOURCE = UUID.randomUUID().toString();
    private static final String PRIVATE = "private-worker-sentinel";

    @ParameterizedTest
    @EnumSource(Kind.class)
    void claimFailureAndLegacyCandidatesKeepIsolatedStableScopes(Kind kind) {
        var observed = new ArrayList<Map<String, String>>();
        Answer<Object> claim = invocation -> {
            observed.add(MDC.getCopyOfContextMap());
            Object id = invocation.getArgument(0) instanceof UUID ? invocation.getArgument(0) : invocation.getArgument(1);
            if (id.equals(FAILED)) throw new IllegalStateException(PRIVATE);
            return switch (kind) {
                case EVENT -> mock(EventInboxItem.class);
                case CALLBACK -> mock(PaymentCallback.class);
                default -> null;
            };
        };
        Harness harness = harness(kind, claim);
        var logger = (Logger) LoggerFactory.getLogger(harness.loggerType());
        var appender = new ListAppender<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing(); super.append(event);
            }
        };
        appender.start(); logger.addAppender(appender);
        try (var outer = new DiagnosticContext(UUID.randomUUID().toString(), "caller-tenant", "foreign-business", "foreign-instance", "foreign-task").open()) {
            var caller = MDC.getCopyOfContextMap();
            harness.poll().run();
            assertThat(MDC.getCopyOfContextMap()).isEqualTo(caller);
            harness.poll().run();
            assertThat(MDC.getCopyOfContextMap()).isEqualTo(caller);
            assertThat(observed).hasSize(6);
            assertThat(observed.subList(0, 3)).isEqualTo(observed.subList(3, 6));
            assertThat(observed.get(0)).containsEntry("traceId", SOURCE).containsEntry("tenantId", "tenant-a");
            assertThat(observed.get(1)).containsEntry("tenantId", "tenant-b");
            assertThat(observed.get(2)).containsEntry("tenantId", "tenant-a");
            for (var context : observed) {
                assertThat(DiagnosticContext.validTrace(context.get("traceId"))).isTrue();
                assertThat(context).doesNotContainKeys("businessNo", "processInstanceId", "taskId");
            }
            assertThat(observed.get(1).get("traceId")).isNotEqualTo(SOURCE).isNotEqualTo(observed.get(2).get("traceId"));
            assertThat(appender.list).filteredOn(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR).hasSize(2).allSatisfy(event -> {
                assertThat(event.getMDCPropertyMap()).containsEntry("traceId", SOURCE).containsEntry("tenantId", "tenant-a");
                assertThat(event.getFormattedMessage()).contains(harness.errorCode()).doesNotContain(PRIVATE);
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(appender); appender.stop(); }
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    private Harness harness(Kind kind, Answer<Object> claim) {
        return switch (kind) {
            case SERVICE -> {
                var runs = mock(JdbcServiceTaskOperationRepository.class); var service = mock(ServiceTaskOperationService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcServiceTaskOperationRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcServiceTaskOperationRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcServiceTaskOperationRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new ServiceTaskWorker(runs, service, mock(ServiceTaskGateway.class))::poll, ServiceTaskWorker.class, "WORKER_FAILURE");
            }
            case SIGNATURE -> {
                var runs = mock(JdbcSignatureOperationRepository.class); var service = mock(SignatureOperationService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcSignatureOperationRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcSignatureOperationRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcSignatureOperationRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new SignatureWorker(runs, mock(JdbcSignatureEvidenceRepository.class), service, mock(SignatureGateway.class))::poll, SignatureWorker.class, "SIGNATURE_WORKER_FAILURE");
            }
            case ORGANIZATION -> {
                var runs = mock(JdbcOrganizationSyncRepository.class); var service = mock(OrganizationSyncService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcOrganizationSyncRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcOrganizationSyncRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcOrganizationSyncRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new OrganizationSyncWorker(runs, service, mock(HttpOrganizationSyncSource.class))::poll, OrganizationSyncWorker.class, "WORKER_FAILURE");
            }
            case NOTIFICATION -> {
                var runs = mock(JdbcNotificationDeliveryStore.class); var service = mock(NotificationDeliveryService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcNotificationDeliveryStore.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcNotificationDeliveryStore.Candidate("tenant-b", LEGACY, null),
                        new JdbcNotificationDeliveryStore.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(any(UUID.class), any(Instant.class));
                yield new Harness(new NotificationDeliveryWorker(runs, mock(SmtpNotificationTransport.class), mock(WeComNotificationTransport.class), service)::runOnce, NotificationDeliveryWorker.class, "NOTIFICATION_WORKER_FAILURE");
            }
            case EVENT -> {
                var runs = mock(EventInboxRepository.class); var service = mock(EventInboxService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new EventInboxRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new EventInboxRepository.Candidate("tenant-b", LEGACY, null),
                        new EventInboxRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(runs).get(anyString(), any(UUID.class));
                yield new Harness(new EventInboxWorker(runs, service)::poll, EventInboxWorker.class, "EVENT_PROCESSING_FAILED");
            }
            case CALLBACK -> {
                var runs = mock(JdbcPaymentCallbackRepository.class); var service = mock(PaymentCallbackService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcPaymentCallbackRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcPaymentCallbackRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcPaymentCallbackRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(runs).get(anyString(), any(UUID.class));
                yield new Harness(new PaymentCallbackWorker(runs, service)::poll, PaymentCallbackWorker.class, "CALLBACK_PROCESSING_FAILED");
            }
        };
    }
    /**
     * @author owlzhangfq@gmail.com
     */
    private record Harness(Runnable poll, Class<?> loggerType, String errorCode) { }
    /**
     * @author owlzhangfq@gmail.com
     */
    private enum Kind { SERVICE, SIGNATURE, ORGANIZATION, NOTIFICATION, EVENT, CALLBACK }
}
