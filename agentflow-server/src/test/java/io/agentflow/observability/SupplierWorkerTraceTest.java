package io.agentflow.observability;

import io.agentflow.finance.*;
import io.agentflow.procurement.*;


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

/** 供应商财务队列与本地补齐入口验证领取失败、旧记录及线程复用，不把诊断上下文作为授权主体。 */
class SupplierWorkerTraceTest {
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
            return kind == Kind.ADJUSTMENT_COMPLETION ? java.util.Optional.of(mock(SupplierPayableAdjustmentOperation.class)) : null;
        };
        Harness harness = harness(kind, claim);
        var logger = (Logger) LoggerFactory.getLogger(harness.loggerType());
        var appender = new ListAppender<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing(); super.append(event);
            }
        };
        appender.start(); logger.addAppender(appender);
        try (var outer = new DiagnosticContext(UUID.randomUUID().toString(), "caller-tenant").open()) {
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
            for (var context : observed) assertThat(DiagnosticContext.validTrace(context.get("traceId"))).isTrue();
            assertThat(observed.get(1).get("traceId")).isNotEqualTo(SOURCE).isNotEqualTo(observed.get(2).get("traceId"));
            assertThat(appender.list).hasSize(2).allSatisfy(event -> {
                assertThat(event.getMDCPropertyMap()).containsEntry("traceId", SOURCE).containsEntry("tenantId", "tenant-a");
                assertThat(event.getFormattedMessage()).contains(harness.errorCode()).doesNotContain(PRIVATE);
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(appender); appender.stop(); }
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    private Harness harness(Kind kind, Answer<Object> claim) {
        return switch (kind) {
            case ADJUSTMENT_PREPARATION -> {
                var runs = mock(JdbcSupplierAdjustmentPreparationRepository.class); var service = mock(SupplierAdjustmentPreparationService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcSupplierAdjustmentPreparationRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcSupplierAdjustmentPreparationRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcSupplierAdjustmentPreparationRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new SupplierAdjustmentPreparationWorker(runs, service, mock(SupplierAdjustmentEvidenceReader.class))::poll, SupplierAdjustmentPreparationWorker.class, "SUPPLIER_ADJUSTMENT_PREPARATION_FAILURE");
            }
            case ADJUSTMENT -> {
                var runs = mock(JdbcSupplierPayableAdjustmentRepository.class); var service = mock(SupplierAdjustmentService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcSupplierPayableAdjustmentRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcSupplierPayableAdjustmentRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcSupplierPayableAdjustmentRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new SupplierAdjustmentWorker(runs, service, mock(SupplierAdjustmentEvidenceReader.class), mock(SupplierPayableAdjustmentPort.class), mock(SupplierPaymentReturnPort.class), mock(SupplierAdjustmentCompletionService.class))::poll, SupplierAdjustmentWorker.class, "SUPPLIER_ADJUSTMENT_WORKER_FAILURE");
            }
            case HOLD -> {
                var runs = mock(JdbcSupplierPayableHoldRepository.class); var service = mock(SupplierPayableHoldService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcSupplierPayableHoldRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcSupplierPayableHoldRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcSupplierPayableHoldRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new SupplierPayableHoldWorker(runs, service, mock(SupplierPayableHoldPort.class))::poll, SupplierPayableHoldWorker.class, "HOLD_WORKER_FAILURE");
            }
            case REVIEW -> {
                var runs = mock(JdbcSupplierPayableReviewRepository.class); var service = mock(SupplierPayableReviewService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcSupplierPayableReviewRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcSupplierPayableReviewRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcSupplierPayableReviewRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new SupplierPayableReviewWorker(runs, service, mock(ProcurementPayablePort.class))::poll, SupplierPayableReviewWorker.class, "REVIEW_WORKER_FAILURE");
            }
            case REQUEST -> {
                var runs = mock(JdbcSupplierPaymentExecutionRepository.class); var service = mock(SupplierPaymentExecutionService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcSupplierPaymentExecutionRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcSupplierPaymentExecutionRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcSupplierPaymentExecutionRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new SupplierPaymentExecutionWorker(runs, service, mock(SupplierPaymentEvidenceReader.class))::poll, SupplierPaymentExecutionWorker.class, "SUPPLIER_EXECUTION_WORKER_FAILURE");
            }
            case RETURN -> {
                var runs = mock(JdbcSupplierPaymentReturnCheckRepository.class); var service = mock(SupplierPaymentReturnService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcSupplierPaymentReturnCheckRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcSupplierPaymentReturnCheckRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcSupplierPaymentReturnCheckRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new SupplierPaymentReturnWorker(runs, service, mock(SupplierPaymentReturnPort.class))::poll, SupplierPaymentReturnWorker.class, "SUPPLIER_PAYMENT_RETURN_WORKER_FAILURE");
            }
            case PAYMENT -> {
                var runs = mock(JdbcSupplierPaymentOperationRepository.class); var service = mock(SupplierPaymentService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcSupplierPaymentOperationRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcSupplierPaymentOperationRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcSupplierPaymentOperationRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new SupplierPaymentWorker(runs, service, mock(SupplierPaymentEvidenceReader.class), mock(SupplierPaymentPort.class))::poll, SupplierPaymentWorker.class, "SUPPLIER_PAYMENT_WORKER_FAILURE");
            }
            case SETTLEMENT_PREPARATION -> {
                var runs = mock(JdbcSupplierSettlementPreparationRepository.class); var service = mock(SupplierSettlementPreparationService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcSupplierSettlementPreparationRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcSupplierSettlementPreparationRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcSupplierSettlementPreparationRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new SupplierSettlementPreparationWorker(runs, service, mock(SupplierSettlementEvidenceReader.class))::poll, SupplierSettlementPreparationWorker.class, "SUPPLIER_SETTLEMENT_PREPARATION_FAILURE");
            }
            case SETTLEMENT -> {
                var runs = mock(JdbcSupplierPayableSettlementRepository.class); var service = mock(SupplierSettlementService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcSupplierPayableSettlementRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcSupplierPayableSettlementRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcSupplierPayableSettlementRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new SupplierSettlementWorker(runs, service, mock(SupplierSettlementEvidenceReader.class), mock(SupplierPayableSettlementPort.class))::poll, SupplierSettlementWorker.class, "SUPPLIER_SETTLEMENT_WORKER_FAILURE");
            }
            case ADJUSTMENT_COMPLETION -> {
                var runs = mock(JdbcSupplierPayableAdjustmentRepository.class); var service = mock(SupplierAdjustmentService.class);
                when(runs.awaitingLocalCompletion()).thenReturn(List.of(
                        new JdbcSupplierPayableAdjustmentRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcSupplierPayableAdjustmentRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcSupplierPayableAdjustmentRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(runs).find(anyString(), any(UUID.class));
                yield new Harness(new SupplierAdjustmentWorker(runs, service, mock(SupplierAdjustmentEvidenceReader.class), mock(SupplierPayableAdjustmentPort.class), mock(SupplierPaymentReturnPort.class), mock(SupplierAdjustmentCompletionService.class))::poll, SupplierAdjustmentWorker.class, "SUPPLIER_ADJUSTMENT_COMPLETION_FAILURE");
            }
            case SETTLEMENT_COMPLETION -> {
                var runs = mock(JdbcSupplierPayableSettlementRepository.class); var service = mock(SupplierSettlementService.class);
                when(runs.awaitingLocalCompletion()).thenReturn(List.of(
                        new JdbcSupplierPayableSettlementRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcSupplierPayableSettlementRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcSupplierPayableSettlementRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).completeLocal(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new SupplierSettlementWorker(runs, service, mock(SupplierSettlementEvidenceReader.class), mock(SupplierPayableSettlementPort.class))::poll, SupplierSettlementWorker.class, "SUPPLIER_SETTLEMENT_COMPLETION_FAILURE");
            }
        };
    }
    private record Harness(Runnable poll, Class<?> loggerType, String errorCode) { }
    private enum Kind { ADJUSTMENT_PREPARATION, ADJUSTMENT, HOLD, REVIEW, REQUEST, RETURN, PAYMENT, SETTLEMENT_PREPARATION, SETTLEMENT, ADJUSTMENT_COMPLETION, SETTLEMENT_COMPLETION }
}
