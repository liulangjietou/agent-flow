package io.agentflow.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.agentflow.approval.process.*;
import io.agentflow.expense.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 旧候选也须隔离来源；单笔、失败记录和扫描异常均不能输出敏感异常正文。
 * @author owlzhangfq@gmail.com
 */
class DirectSchedulerTraceTest {
    private static final UUID FAILED = UUID.fromString("5608d7ab-ed2f-45ae-8369-57988f2b8663");
    private static final UUID NEXT = UUID.fromString("59b0dc8f-e394-460a-b7c5-9cd2f047a4ea");
    private static final String PRIVATE = "private-synthetic-account-and-form-body";

    @ParameterizedTest @EnumSource(Kind.class)
    void legacyCandidatesHaveStableScopesAndPrivateFailuresStayInsideThem(Kind kind) {
        var observed = new ArrayList<Map<String, String>>();
        var harness = harness(kind, false, id -> {
            observed.add(MDC.getCopyOfContextMap());
            if (id.equals(FAILED.toString())) throw new IllegalStateException(PRIVATE);
        });
        var logger = (Logger) LoggerFactory.getLogger(harness.logger());
        var appender = appender(); logger.addAppender(appender);
        String caller = UUID.randomUUID().toString();
        try (var outer = new DiagnosticContext(caller, "caller-tenant").open()) {
            var previous = MDC.getCopyOfContextMap();
            harness.poll().run(); harness.poll().run();
            assertThat(MDC.getCopyOfContextMap()).isEqualTo(previous);
            assertThat(observed).hasSize(4);
            assertSoftly(softly -> {
                softly.assertThat(observed.subList(0, 2)).isEqualTo(observed.subList(2, 4));
                for (var context : observed) {
                    softly.assertThat(DiagnosticContext.validTrace(context.get("traceId"))).isTrue();
                    softly.assertThat(context.get("traceId")).isNotEqualTo(caller);
                    softly.assertThat(context.get("tenantId")).isNotEqualTo("caller-tenant");
                }
                softly.assertThat(observed.get(0).get("traceId")).isNotEqualTo(observed.get(1).get("traceId"));
                softly.assertThat(appender.list).hasSize(kind == Kind.TIMER ? 4 : 2);
                for (var event : appender.list) {
                    softly.assertThat(event.getFormattedMessage()).doesNotContain(PRIVATE);
                    softly.assertThat(event.getThrowableProxy()).isNull();
                    softly.assertThat(event.getMDCPropertyMap().get("traceId")).isEqualTo(observed.get(0).get("traceId"));
                }
            });
        } finally { logger.detachAppender(appender); appender.stop(); }
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void persistedSourceIsRestoredBeforeEnteringBusinessService(Kind kind) {
        String trace = UUID.randomUUID().toString(); var observed = new ArrayList<Map<String, String>>();
        var harness = harness(kind, false, ignored -> observed.add(MDC.getCopyOfContextMap()), trace);
        try (var scope = new DiagnosticContext(UUID.randomUUID().toString(), "caller-tenant").open()) {
            var previous = MDC.getCopyOfContextMap(); harness.poll().run();
            assertThat(observed).hasSize(2).allSatisfy(context -> {
                assertThat(context.get("traceId")).isEqualTo(trace);
                assertThat(context.get("tenantId")).isIn("tenant-a", "tenant-b");
            });
            assertThat(MDC.getCopyOfContextMap()).isEqualTo(previous);
        }
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void scanFailureDoesNotLogPrivateException(Kind kind) {
        var harness = harness(kind, true, ignored -> fail("Scan failure must not execute a candidate"));
        var logger = (Logger) LoggerFactory.getLogger(harness.logger()); var appender = appender(); logger.addAppender(appender);
        try {
            harness.poll().run();
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).contains("errorCode=").doesNotContain(PRIVATE);
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    private ListAppender<ILoggingEvent> appender() {
        var appender = new ListAppender<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) { event.prepareForDeferredProcessing(); super.append(event); }
        };
        appender.start(); return appender;
    }

    private Harness harness(Kind kind, boolean scanFailure, Consumer<String> enter) {
        return harness(kind, scanFailure, enter, null);
    }

    private Harness harness(Kind kind, boolean scanFailure, Consumer<String> enter, String trace) {
        return switch (kind) {
            case PROXY -> {
                var service = mock(FlowableApprovalProxyNotifications.class);
                if (scanFailure) when(service.candidates(any(), any())).thenThrow(new IllegalStateException(PRIVATE));
                else when(service.candidates(any(), any())).thenReturn(List.of(
                        new FlowableApprovalProxyNotifications.Candidate("tenant-a", FAILED, FAILED, FAILED.toString(), trace),
                        new FlowableApprovalProxyNotifications.Candidate("tenant-b", NEXT, NEXT, NEXT.toString(), trace)));
                doAnswer(call -> { enter.accept(((FlowableApprovalProxyNotifications.Candidate) call.getArgument(0)).proxyId().toString()); return false; }).when(service).pending(any());
                yield new Harness(new ApprovalProxyNotificationScheduler(service)::poll, ApprovalProxyNotificationScheduler.class);
            }
            case DEADLINE -> {
                var service = mock(FlowableTaskDeadlineReminders.class);
                if (scanFailure) when(service.candidates(any(), any())).thenThrow(new IllegalStateException(PRIVATE));
                else when(service.candidates(any(), any())).thenReturn(List.of(new FlowableTaskDeadlineReminders.Candidate(FAILED.toString(), Instant.EPOCH, "tenant-a", trace), new FlowableTaskDeadlineReminders.Candidate(NEXT.toString(), Instant.EPOCH, "tenant-b", trace)));
                doAnswer(call -> { enter.accept(call.getArgument(0)); return false; }).when(service).remind(anyString(), any());
                yield new Harness(new TaskDeadlineReminderScheduler(service)::deliver, TaskDeadlineReminderScheduler.class);
            }
            case ESCALATION -> {
                var service = mock(FlowableTaskEscalations.class);
                if (scanFailure) when(service.candidates(any(), any())).thenThrow(new IllegalStateException(PRIVATE));
                else when(service.candidates(any(), any())).thenReturn(List.of(new FlowableTaskEscalations.Candidate(FAILED.toString(), Instant.EPOCH, "tenant-a", trace), new FlowableTaskEscalations.Candidate(NEXT.toString(), Instant.EPOCH, "tenant-b", trace)));
                doAnswer(call -> { enter.accept(call.getArgument(0)); return false; }).when(service).escalate(anyString(), any());
                yield new Harness(new TaskEscalationScheduler(service)::deliver, TaskEscalationScheduler.class);
            }
            case TIMER -> {
                var service = mock(TimerWaitService.class);
                if (scanFailure) when(service.candidates(any(), any())).thenThrow(new IllegalStateException(PRIVATE));
                else when(service.candidates(any(), any())).thenReturn(List.of(new TimerWaitService.Candidate(FAILED.toString(), Instant.EPOCH, "tenant-a", trace), new TimerWaitService.Candidate(NEXT.toString(), Instant.EPOCH, "tenant-b", trace)));
                doAnswer(call -> { enter.accept(call.getArgument(0)); return false; }).when(service).advance(anyString(), any());
                doThrow(new IllegalStateException(PRIVATE)).when(service).failed(anyString(), any());
                yield new Harness(new TimerWaitScheduler(service)::dispatch, TimerWaitScheduler.class);
            }
            case OVERDUE -> {
                var repository = mock(JdbcAdvanceOverdueRepository.class); var service = mock(AdvanceOverdueReminders.class);
                if (scanFailure) when(repository.candidates(any(), any())).thenThrow(new IllegalStateException(PRIVATE));
                else when(repository.candidates(any(), any())).thenReturn(List.of(new JdbcAdvanceOverdueRepository.Candidate("tenant-a", FAILED, LocalDate.EPOCH, trace), new JdbcAdvanceOverdueRepository.Candidate("tenant-b", NEXT, LocalDate.EPOCH, trace)));
                doAnswer(call -> { enter.accept(((JdbcAdvanceOverdueRepository.Candidate) call.getArgument(0)).id().toString()); return false; }).when(service).remind(any(), any());
                yield new Harness(new AdvanceOverdueScheduling(repository, service)::poll, AdvanceOverdueScheduling.class);
            }
            case RETENTION -> {
                var repository = mock(JdbcExpenseBudgetRetentionRepository.class); var service = mock(ExpenseBudgetRetentionService.class);
                if (scanFailure) when(repository.candidates(any(), any())).thenThrow(new IllegalStateException(PRIVATE));
                else when(repository.candidates(any(), any())).thenReturn(List.of(new JdbcExpenseBudgetRetentionRepository.Candidate("tenant-a", FAILED, 1, trace), new JdbcExpenseBudgetRetentionRepository.Candidate("tenant-b", NEXT, 1, trace)));
                doAnswer(call -> { enter.accept(((JdbcExpenseBudgetRetentionRepository.Candidate) call.getArgument(0)).reportId().toString()); return null; }).when(service).process(any(), any());
                yield new Harness(new ExpenseBudgetRetentionScheduling(repository, service)::poll, ExpenseBudgetRetentionScheduling.class);
            }
        };
    }

    private record Harness(Runnable poll, Class<?> logger) { }
    private enum Kind { PROXY, DEADLINE, ESCALATION, TIMER, OVERDUE, RETENTION }
}
