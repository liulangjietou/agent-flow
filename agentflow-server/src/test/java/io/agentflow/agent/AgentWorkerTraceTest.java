package io.agentflow.agent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.agentflow.observability.DiagnosticContext;
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
 * 六类工作器验证领取失败、旧记录及线程复用，不把诊断上下文作为授权主体。
 * @author owlzhangfq@gmail.com
 */
class AgentWorkerTraceTest {
    private static final UUID FAILED = UUID.randomUUID(), LEGACY = UUID.randomUUID();
    private static final String SOURCE = UUID.randomUUID().toString();
    private static final String PRIVATE = "private-worker-sentinel";

    @ParameterizedTest
    @EnumSource(Kind.class)
    void claimFailureAndLegacyCandidatesKeepIsolatedStableScopes(Kind kind) {
        var observed = new ArrayList<Map<String, String>>();
        Answer<Object> claim = invocation -> {
            observed.add(MDC.getCopyOfContextMap());
            if (invocation.getArgument(1).equals(FAILED)) throw new IllegalStateException(PRIVATE);
            return null;
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
            assertThat(appender.list).hasSize(2).allSatisfy(event -> {
                assertThat(event.getMDCPropertyMap()).containsEntry("traceId", SOURCE).containsEntry("tenantId", "tenant-a");
                assertThat(event.getFormattedMessage()).contains("WORKER_FAILURE").doesNotContain(PRIVATE);
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(appender); appender.stop(); }
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    private Harness harness(Kind kind, Answer<Object> claim) {
        return switch (kind) {
            case SUMMARY -> {
                var runs = mock(JdbcAssistJobRepository.class); var service = mock(AssistExecutionService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcAssistJobRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcAssistJobRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcAssistJobRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new AssistWorker(runs, service, mock(AssistModelPort.class))::poll, AssistWorker.class);
            }
            case DRAFT -> {
                var runs = mock(JdbcDraftAssistRunRepository.class); var service = mock(DraftAssistService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcDraftAssistRunRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcDraftAssistRunRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcDraftAssistRunRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new DraftAssistWorker(runs, service, mock(DraftAssistModelPort.class))::poll, DraftAssistWorker.class);
            }
            case INVOICE -> {
                var runs = mock(JdbcInvoiceExtractionRunRepository.class); var service = mock(InvoiceExtractionService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcInvoiceExtractionRunRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcInvoiceExtractionRunRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcInvoiceExtractionRunRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new InvoiceExtractionWorker(runs, service, mock(InvoiceExtractionPort.class))::poll, InvoiceExtractionWorker.class);
            }
            case EXPENSE -> {
                var runs = mock(JdbcExpenseDraftAssistRepository.class); var service = mock(ExpenseDraftAssistService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcExpenseDraftAssistRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcExpenseDraftAssistRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcExpenseDraftAssistRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new ExpenseDraftAssistWorker(runs, service, mock(ExpenseDraftAssistPreparation.class),
                        mock(ExpenseDraftModelPort.class))::poll, ExpenseDraftAssistWorker.class);
            }
            case PRECHECK -> {
                var runs = mock(JdbcPrecheckExplanationRepository.class); var service = mock(PrecheckExplanationService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcPrecheckExplanationRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcPrecheckExplanationRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcPrecheckExplanationRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new PrecheckExplanationWorker(runs, service, mock(PrecheckExplanationModelPort.class))::poll, PrecheckExplanationWorker.class);
            }
            case RISK -> {
                var runs = mock(JdbcExpenseRiskRepository.class); var service = mock(ExpenseRiskService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcExpenseRiskRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcExpenseRiskRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcExpenseRiskRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new ExpenseRiskWorker(runs, service, mock(ExpenseRiskModelPort.class))::poll, ExpenseRiskWorker.class);
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
    private enum Kind { SUMMARY, DRAFT, INVOICE, EXPENSE, PRECHECK, RISK }
}
