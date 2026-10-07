package io.agentflow.observability;

import io.agentflow.finance.*;
import io.agentflow.expense.*;
import io.agentflow.approval.process.ExpenseBudgetReviewRecovery;


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

/** 费用财务队列、归档与本地恢复入口验证领取失败、旧记录及线程复用，不把诊断上下文作为授权主体。 */
class ExpenseRecoveryWorkerTraceTest {
    private static final UUID FAILED = UUID.randomUUID(), LEGACY = UUID.randomUUID();
    private static final String SOURCE = UUID.randomUUID().toString();
    private static final String PRIVATE = "private-worker-sentinel";

    @ParameterizedTest
    @EnumSource(Kind.class)
    void claimFailureAndLegacyCandidatesKeepIsolatedStableScopes(Kind kind) {
        var observed = new ArrayList<Map<String, String>>();
        java.util.function.Consumer<UUID> enter = id -> {
            observed.add(MDC.getCopyOfContextMap());
            if (id.equals(FAILED)) throw new IllegalStateException(PRIVATE);
        };
        Harness harness = harness(kind, enter);
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

    @org.junit.jupiter.api.Test
    void archiveFailureDoesNotExposePrivateException() {
        var runs = mock(JdbcExpenseArchiveRepository.class); var service = mock(ExpenseArchiveService.class);
        when(runs.candidates(any())).thenReturn(List.of(new JdbcExpenseArchiveRepository.Candidate("tenant-a", FAILED, SOURCE)));
        when(service.prepare("tenant-a", FAILED)).thenThrow(new IllegalStateException(PRIVATE));
        var logger = (Logger) LoggerFactory.getLogger(ExpenseArchiveWorker.class);
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            new ExpenseArchiveWorker(runs, service, mock(ExpenseArchiveFiles.class)).poll();
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).contains("ARCHIVE_FAILURE").doesNotContain(PRIVATE);
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    private Harness harness(Kind kind, java.util.function.Consumer<UUID> enter) {
        return switch (kind) {
            case ARCHIVE -> {
                var runs = mock(JdbcExpenseArchiveRepository.class); var service = mock(ExpenseArchiveService.class);
                when(runs.candidates(any())).thenReturn(List.of(
                        new JdbcExpenseArchiveRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcExpenseArchiveRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcExpenseArchiveRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(invocation -> { enter.accept(invocation.getArgument(1)); return null; }).when(service).prepare(anyString(), any(UUID.class));
                yield new Harness(new ExpenseArchiveWorker(runs, service, mock(ExpenseArchiveFiles.class))::poll, ExpenseArchiveWorker.class, "ARCHIVE_FAILURE");
            }
            case BUDGET_REVIEW -> {
                var runs = mock(JdbcExpenseBudgetReviewRepository.class); var service = mock(ExpenseBudgetReviewRecovery.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcExpenseBudgetReviewRepository.Candidate("tenant-a", FAILED, LEGACY, 1, SOURCE),
                        new JdbcExpenseBudgetReviewRepository.Candidate("tenant-b", LEGACY, LEGACY, 1, null),
                        new JdbcExpenseBudgetReviewRepository.Candidate("tenant-a", LEGACY, LEGACY, 1, PRIVATE)));
                doAnswer(invocation -> { enter.accept(((JdbcExpenseBudgetReviewRepository.Candidate) invocation.getArgument(0)).reportId()); return null; }).when(service).recover(any());
                yield new Harness(new ExpenseBudgetReviewWorker(runs, service)::poll, ExpenseBudgetReviewWorker.class, "RECOVERY_FAILURE");
            }
            case PARTIAL_PREPARATION -> {
                var runs = mock(JdbcExpensePartialPreparationRepository.class); var service = mock(ExpensePartialPreparationService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcExpensePartialPreparationRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcExpensePartialPreparationRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcExpensePartialPreparationRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(invocation -> { enter.accept(invocation.getArgument(1)); return null; }).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new ExpensePartialAdjustmentWorker(mock(JdbcExpensePartialAdjustmentRepository.class), mock(ExpensePartialAdjustmentFinance.class), mock(BudgetConsumptionReductionPort.class), mock(ExpenseAccrualReductionPort.class), mock(ExpensePartialAdjustmentExecution.class), runs, service, mock(ExpensePartialPreparationReader.class))::poll, ExpensePartialAdjustmentWorker.class, "PARTIAL_PREPARATION_WORKER_FAILURE");
            }
            case PARTIAL_BUDGET -> {
                var runs = mock(JdbcExpensePartialAdjustmentRepository.class); var service = mock(ExpensePartialAdjustmentFinance.class);
                when(runs.dueBudget(any(Instant.class))).thenReturn(List.of(
                        new JdbcExpensePartialAdjustmentRepository.Candidate("tenant-a", FAILED, LEGACY, 1, SOURCE),
                        new JdbcExpensePartialAdjustmentRepository.Candidate("tenant-b", LEGACY, LEGACY, 1, null),
                        new JdbcExpensePartialAdjustmentRepository.Candidate("tenant-a", LEGACY, LEGACY, 1, PRIVATE)));
                doAnswer(invocation -> { enter.accept(invocation.getArgument(1)); return null; }).when(service).claimBudget(anyString(), any(UUID.class), any(), any(Instant.class));
                yield new Harness(new ExpensePartialAdjustmentWorker(runs, service, mock(BudgetConsumptionReductionPort.class), mock(ExpenseAccrualReductionPort.class), mock(ExpensePartialAdjustmentExecution.class), mock(JdbcExpensePartialPreparationRepository.class), mock(ExpensePartialPreparationService.class), mock(ExpensePartialPreparationReader.class))::poll, ExpensePartialAdjustmentWorker.class, "PARTIAL_BUDGET_WORKER_FAILURE");
            }
            case PARTIAL_ACCRUAL -> {
                var runs = mock(JdbcExpensePartialAdjustmentRepository.class); var service = mock(ExpensePartialAdjustmentFinance.class);
                when(runs.dueAccrual(any(Instant.class))).thenReturn(List.of(
                        new JdbcExpensePartialAdjustmentRepository.Candidate("tenant-a", FAILED, LEGACY, 1, SOURCE),
                        new JdbcExpensePartialAdjustmentRepository.Candidate("tenant-b", LEGACY, LEGACY, 1, null),
                        new JdbcExpensePartialAdjustmentRepository.Candidate("tenant-a", LEGACY, LEGACY, 1, PRIVATE)));
                doAnswer(invocation -> { enter.accept(invocation.getArgument(1)); return null; }).when(service).claimAccrual(anyString(), any(UUID.class), any(), any(Instant.class));
                yield new Harness(new ExpensePartialAdjustmentWorker(runs, service, mock(BudgetConsumptionReductionPort.class), mock(ExpenseAccrualReductionPort.class), mock(ExpensePartialAdjustmentExecution.class), mock(JdbcExpensePartialPreparationRepository.class), mock(ExpensePartialPreparationService.class), mock(ExpensePartialPreparationReader.class))::poll, ExpensePartialAdjustmentWorker.class, "PARTIAL_ACCRUAL_WORKER_FAILURE");
            }
            case PARTIAL_COMPLETION -> {
                var runs = mock(JdbcExpensePartialAdjustmentRepository.class); var service = mock(ExpensePartialAdjustmentExecution.class);
                when(runs.ready()).thenReturn(List.of(
                        new JdbcExpensePartialAdjustmentRepository.Candidate("tenant-a", FAILED, LEGACY, 1, SOURCE),
                        new JdbcExpensePartialAdjustmentRepository.Candidate("tenant-b", LEGACY, LEGACY, 1, null),
                        new JdbcExpensePartialAdjustmentRepository.Candidate("tenant-a", LEGACY, LEGACY, 1, PRIVATE)));
                doAnswer(invocation -> { enter.accept(((JdbcExpensePartialAdjustmentRepository.Candidate) invocation.getArgument(0)).id()); return null; }).when(service).apply(any());
                yield new Harness(new ExpensePartialAdjustmentWorker(runs, mock(ExpensePartialAdjustmentFinance.class), mock(BudgetConsumptionReductionPort.class), mock(ExpenseAccrualReductionPort.class), service, mock(JdbcExpensePartialPreparationRepository.class), mock(ExpensePartialPreparationService.class), mock(ExpensePartialPreparationReader.class))::poll, ExpensePartialAdjustmentWorker.class, "PARTIAL_RESOURCE_FAILURE");
            }
            case RESOURCE_PREPARATION -> {
                var runs = mock(JdbcExpenseResourceAdjustmentPreparationRepository.class); var service = mock(ExpenseResourceAdjustmentPreparationService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcExpenseResourceAdjustmentPreparationRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcExpenseResourceAdjustmentPreparationRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcExpenseResourceAdjustmentPreparationRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(invocation -> { enter.accept(invocation.getArgument(1)); return null; }).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new ExpenseResourceAdjustmentWorker(runs, service, mock(AccountingPeriodPort.class), mock(JdbcBudgetConsumptionReversalRepository.class), mock(ExpenseResourceAdjustmentBudgetExecution.class), mock(BudgetConsumptionReversalPort.class), mock(JdbcExpenseResourceAdjustmentRepository.class), mock(ExpenseResourceAdjustmentExecution.class))::poll, ExpenseResourceAdjustmentWorker.class, "ADJUSTMENT_PREPARATION_WORKER_FAILURE");
            }
            case RESOURCE_BUDGET -> {
                var runs = mock(JdbcBudgetConsumptionReversalRepository.class); var service = mock(ExpenseResourceAdjustmentBudgetExecution.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcBudgetConsumptionReversalRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcBudgetConsumptionReversalRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcBudgetConsumptionReversalRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(invocation -> { enter.accept(invocation.getArgument(1)); return null; }).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new ExpenseResourceAdjustmentWorker(mock(JdbcExpenseResourceAdjustmentPreparationRepository.class), mock(ExpenseResourceAdjustmentPreparationService.class), mock(AccountingPeriodPort.class), runs, service, mock(BudgetConsumptionReversalPort.class), mock(JdbcExpenseResourceAdjustmentRepository.class), mock(ExpenseResourceAdjustmentExecution.class))::poll, ExpenseResourceAdjustmentWorker.class, "ADJUSTMENT_BUDGET_WORKER_FAILURE");
            }
            case RESOURCE_COMPLETION -> {
                var runs = mock(JdbcExpenseResourceAdjustmentRepository.class); var service = mock(ExpenseResourceAdjustmentExecution.class);
                when(runs.ready()).thenReturn(List.of(
                        new JdbcExpenseResourceAdjustmentRepository.Candidate("tenant-a", FAILED, LEGACY, 1, SOURCE),
                        new JdbcExpenseResourceAdjustmentRepository.Candidate("tenant-b", LEGACY, LEGACY, 1, null),
                        new JdbcExpenseResourceAdjustmentRepository.Candidate("tenant-a", LEGACY, LEGACY, 1, PRIVATE)));
                doAnswer(invocation -> { enter.accept(((JdbcExpenseResourceAdjustmentRepository.Candidate) invocation.getArgument(0)).id()); return null; }).when(service).apply(any());
                yield new Harness(new ExpenseResourceAdjustmentWorker(mock(JdbcExpenseResourceAdjustmentPreparationRepository.class), mock(ExpenseResourceAdjustmentPreparationService.class), mock(AccountingPeriodPort.class), mock(JdbcBudgetConsumptionReversalRepository.class), mock(ExpenseResourceAdjustmentBudgetExecution.class), mock(BudgetConsumptionReversalPort.class), runs, service)::poll, ExpenseResourceAdjustmentWorker.class, "ADJUSTMENT_RESOURCE_FAILURE");
            }
            case SETTLEMENT -> {
                var runs = mock(JdbcExpenseSettlementRepository.class); var service = mock(ExpenseSettlementService.class);
                when(runs.pending()).thenReturn(List.of(
                        new JdbcExpenseSettlementRepository.Candidate("tenant-a", FAILED, 1, SOURCE),
                        new JdbcExpenseSettlementRepository.Candidate("tenant-b", LEGACY, 1, null),
                        new JdbcExpenseSettlementRepository.Candidate("tenant-a", LEGACY, 1, PRIVATE)));
                doAnswer(invocation -> { enter.accept(((JdbcExpenseSettlementRepository.Candidate) invocation.getArgument(0)).reportId()); return null; }).when(service).consume(any());
                yield new Harness(new ExpenseSettlementWorker(runs, mock(ExpenseSettlementRegistration.class), service)::poll, ExpenseSettlementWorker.class, "SETTLEMENT_FAILURE");
            }
            case PAYMENT_RECOVERY -> {
                var runs = mock(JdbcExpenseSettlementRepository.class); var service = mock(ExpenseSettlementRegistration.class);
                when(runs.recoveryCandidates(any())).thenReturn(List.of(
                        new JdbcExpenseSettlementRepository.RecoveryCandidate(JdbcExpenseSettlementRepository.FundingKind.PAYMENT, "tenant-a", FAILED, FAILED, SOURCE),
                        new JdbcExpenseSettlementRepository.RecoveryCandidate(JdbcExpenseSettlementRepository.FundingKind.PAYMENT, "tenant-b", LEGACY, LEGACY, null),
                        new JdbcExpenseSettlementRepository.RecoveryCandidate(JdbcExpenseSettlementRepository.FundingKind.PAYMENT, "tenant-a", LEGACY, LEGACY, PRIVATE)));
                doAnswer(invocation -> { enter.accept(((JdbcExpenseSettlementRepository.RecoveryCandidate) invocation.getArgument(0)).reportId()); return null; }).when(service).recover(any());
                yield new Harness(new ExpenseSettlementWorker(runs, service, mock(ExpenseSettlementService.class))::poll, ExpenseSettlementWorker.class, "SETTLEMENT_FAILURE");
            }
            case VOUCHER_RECOVERY -> {
                var runs = mock(JdbcExpenseSettlementRepository.class); var service = mock(ExpenseSettlementRegistration.class);
                when(runs.recoveryCandidates(any())).thenReturn(List.of(
                        new JdbcExpenseSettlementRepository.RecoveryCandidate(JdbcExpenseSettlementRepository.FundingKind.VOUCHER, "tenant-a", FAILED, FAILED, SOURCE),
                        new JdbcExpenseSettlementRepository.RecoveryCandidate(JdbcExpenseSettlementRepository.FundingKind.VOUCHER, "tenant-b", LEGACY, LEGACY, null),
                        new JdbcExpenseSettlementRepository.RecoveryCandidate(JdbcExpenseSettlementRepository.FundingKind.VOUCHER, "tenant-a", LEGACY, LEGACY, PRIVATE)));
                doAnswer(invocation -> { enter.accept(((JdbcExpenseSettlementRepository.RecoveryCandidate) invocation.getArgument(0)).reportId()); return null; }).when(service).recover(any());
                yield new Harness(new ExpenseSettlementWorker(runs, service, mock(ExpenseSettlementService.class))::poll, ExpenseSettlementWorker.class, "SETTLEMENT_FAILURE");
            }
            case ZERO_RECOVERY -> {
                var runs = mock(JdbcExpenseSettlementRepository.class); var service = mock(ExpenseSettlementRegistration.class);
                when(runs.recoveryCandidates(any())).thenReturn(List.of(
                        new JdbcExpenseSettlementRepository.RecoveryCandidate(JdbcExpenseSettlementRepository.FundingKind.ZERO, "tenant-a", FAILED, FAILED, SOURCE),
                        new JdbcExpenseSettlementRepository.RecoveryCandidate(JdbcExpenseSettlementRepository.FundingKind.ZERO, "tenant-b", LEGACY, LEGACY, null),
                        new JdbcExpenseSettlementRepository.RecoveryCandidate(JdbcExpenseSettlementRepository.FundingKind.ZERO, "tenant-a", LEGACY, LEGACY, PRIVATE)));
                doAnswer(invocation -> { enter.accept(((JdbcExpenseSettlementRepository.RecoveryCandidate) invocation.getArgument(0)).reportId()); return null; }).when(service).recover(any());
                yield new Harness(new ExpenseSettlementWorker(runs, service, mock(ExpenseSettlementService.class))::poll, ExpenseSettlementWorker.class, "SETTLEMENT_FAILURE");
            }
            case REVERSAL_PREPARATION -> {
                var runs = mock(JdbcVoucherReversalPreparationRepository.class); var service = mock(VoucherReversalPreparationService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcVoucherReversalPreparationRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcVoucherReversalPreparationRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcVoucherReversalPreparationRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(invocation -> { enter.accept(invocation.getArgument(1)); return null; }).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new VoucherReversalExecutionWorker(runs, service, mock(AccountingVoucherPort.class), mock(AccountingPeriodPort.class), mock(JdbcVoucherReversalOperationRepository.class), mock(VoucherReversalExecutionService.class), mock(AccountingReversalPort.class))::poll, VoucherReversalExecutionWorker.class, "REVERSAL_PREPARATION_WORKER_FAILURE");
            }
            case REVERSAL -> {
                var runs = mock(JdbcVoucherReversalOperationRepository.class); var service = mock(VoucherReversalExecutionService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcVoucherReversalOperationRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcVoucherReversalOperationRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcVoucherReversalOperationRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(invocation -> { enter.accept(invocation.getArgument(1)); return null; }).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new VoucherReversalExecutionWorker(mock(JdbcVoucherReversalPreparationRepository.class), mock(VoucherReversalPreparationService.class), mock(AccountingVoucherPort.class), mock(AccountingPeriodPort.class), runs, service, mock(AccountingReversalPort.class))::poll, VoucherReversalExecutionWorker.class, "REVERSAL_EXECUTION_WORKER_FAILURE");
            }
        };
    }
    private record Harness(Runnable poll, Class<?> loggerType, String errorCode) { }
    private enum Kind { ARCHIVE, BUDGET_REVIEW, PARTIAL_PREPARATION, PARTIAL_BUDGET, PARTIAL_ACCRUAL, PARTIAL_COMPLETION, RESOURCE_PREPARATION, RESOURCE_BUDGET, RESOURCE_COMPLETION, SETTLEMENT, PAYMENT_RECOVERY, VOUCHER_RECOVERY, ZERO_RECOVERY, REVERSAL_PREPARATION, REVERSAL }
}
