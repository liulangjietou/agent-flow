package io.agentflow.observability;

import io.agentflow.finance.*;
import io.agentflow.budget.*;
import io.agentflow.procurement.*;

import io.agentflow.expense.*;

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

/** 预算调整、借还款、退票与采购预检工作器验证领取失败、旧记录及线程复用，不把诊断上下文作为授权主体。 */
class FinancialReviewWorkerTraceTest {
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
            case BUDGET_ADJUSTMENT_CHECK -> {
                var runs = mock(JdbcBudgetAdjustmentCheckRepository.class); var service = mock(BudgetAdjustmentCheckService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcBudgetAdjustmentCheckRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcBudgetAdjustmentCheckRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcBudgetAdjustmentCheckRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new BudgetAdjustmentCheckWorker(runs, service, mock(BudgetAdjustmentCheckEvaluator.class))::poll, BudgetAdjustmentCheckWorker.class, "WORKER_FAILURE");
            }
            case BUDGET_ADJUSTMENT_REVIEW -> {
                var runs = mock(JdbcBudgetAdjustmentReviewRepository.class); var service = mock(BudgetAdjustmentReviewService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcBudgetAdjustmentReviewRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcBudgetAdjustmentReviewRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcBudgetAdjustmentReviewRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new BudgetAdjustmentReviewWorker(runs, service, mock(BudgetLedgerPort.class))::poll, BudgetAdjustmentReviewWorker.class, "REVIEW_WORKER_FAILURE");
            }
            case BUDGET_ADJUSTMENT_OPERATION -> {
                var runs = mock(JdbcBudgetAdjustmentOperationRepository.class); var service = mock(BudgetAdjustmentExecutionService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcBudgetAdjustmentOperationRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcBudgetAdjustmentOperationRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcBudgetAdjustmentOperationRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new BudgetAdjustmentExecutionWorker(runs, service, mock(BudgetAdjustmentPort.class))::poll, BudgetAdjustmentExecutionWorker.class, "EXECUTION_WORKER_FAILURE");
            }
            case DISBURSEMENT_RETURN_CHECK -> {
                var runs = mock(JdbcDisbursementReturnCheckRepository.class); var service = mock(AdvanceDisbursementReturnService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcDisbursementReturnCheckRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcDisbursementReturnCheckRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcDisbursementReturnCheckRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new AdvanceDisbursementReturnWorker(runs, service, mock(AdvanceDisbursementReturnPort.class))::poll, AdvanceDisbursementReturnWorker.class, "DISBURSEMENT_RETURN_WORKER_FAILURE");
            }
            case REPAYMENT_REVIEW_CHECK -> {
                var runs = mock(JdbcRepaymentReviewCheckRepository.class); var service = mock(AdvanceRepaymentReviewService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcRepaymentReviewCheckRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcRepaymentReviewCheckRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcRepaymentReviewCheckRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new AdvanceRepaymentReviewWorker(runs, service, mock(AdvanceRepaymentAdjustmentPort.class))::poll, AdvanceRepaymentReviewWorker.class, "REPAYMENT_REVIEW_WORKER_FAILURE");
            }
            case ADVANCE_REPAYMENT_CHECK -> {
                var runs = mock(JdbcAdvanceRepaymentCheckRepository.class); var service = mock(AdvanceRepaymentService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcAdvanceRepaymentCheckRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcAdvanceRepaymentCheckRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcAdvanceRepaymentCheckRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new AdvanceRepaymentWorker(runs, service, mock(AdvanceRepaymentPort.class))::poll, AdvanceRepaymentWorker.class, "REPAYMENT_WORKER_FAILURE");
            }
            case ADVANCE_REQUEST_CHECK -> {
                var runs = mock(JdbcAdvanceRequestCheckRepository.class); var service = mock(AdvanceRequestCheckService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcAdvanceRequestCheckRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcAdvanceRequestCheckRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcAdvanceRequestCheckRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new AdvanceRequestCheckWorker(runs, service, mock(AdvanceRequestCheckEvaluator.class))::poll, AdvanceRequestCheckWorker.class, "WORKER_FAILURE");
            }
            case EXPENSE_PAYMENT_RETURN_CHECK -> {
                var runs = mock(JdbcExpensePaymentReturnCheckRepository.class); var service = mock(ExpensePaymentReturnService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcExpensePaymentReturnCheckRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcExpensePaymentReturnCheckRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcExpensePaymentReturnCheckRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new ExpensePaymentReturnWorker(runs, service, mock(ExpensePaymentReturnPort.class))::poll, ExpensePaymentReturnWorker.class, "EXPENSE_PAYMENT_RETURN_WORKER_FAILURE");
            }
            case EXPENSE_PLAN_CHECK -> {
                var runs = mock(JdbcExpensePlanCheckRepository.class); var service = mock(ExpensePlanCheckService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcExpensePlanCheckRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcExpensePlanCheckRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcExpensePlanCheckRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new ExpensePlanCheckWorker(runs, service, mock(ExpensePlanCheckEvaluator.class))::poll, ExpensePlanCheckWorker.class, "WORKER_FAILURE");
            }
            case VOUCHER_REVERSAL_CHECK -> {
                var runs = mock(JdbcVoucherReversalCheckRepository.class); var service = mock(VoucherReversalService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcVoucherReversalCheckRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcVoucherReversalCheckRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcVoucherReversalCheckRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new VoucherReversalWorker(runs, service, mock(VoucherReversalPort.class))::poll, VoucherReversalWorker.class, "VOUCHER_REVERSAL_WORKER_FAILURE");
            }
            case PROCUREMENT_PAYMENT_CHECK -> {
                var runs = mock(JdbcProcurementPaymentCheckRepository.class); var service = mock(ProcurementPaymentCheckService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcProcurementPaymentCheckRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcProcurementPaymentCheckRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcProcurementPaymentCheckRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new ProcurementPaymentCheckWorker(runs, service, mock(ProcurementPaymentCheckEvaluator.class))::poll, ProcurementPaymentCheckWorker.class, "WORKER_FAILURE");
            }
        };
    }
    private record Harness(Runnable poll, Class<?> loggerType, String errorCode) { }
    private enum Kind { BUDGET_ADJUSTMENT_CHECK, BUDGET_ADJUSTMENT_REVIEW, BUDGET_ADJUSTMENT_OPERATION, DISBURSEMENT_RETURN_CHECK, REPAYMENT_REVIEW_CHECK, ADVANCE_REPAYMENT_CHECK, ADVANCE_REQUEST_CHECK, EXPENSE_PAYMENT_RETURN_CHECK, EXPENSE_PLAN_CHECK, VOUCHER_REVERSAL_CHECK, PROCUREMENT_PAYMENT_CHECK }
}
