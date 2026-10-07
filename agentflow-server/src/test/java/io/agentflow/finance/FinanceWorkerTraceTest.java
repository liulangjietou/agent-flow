package io.agentflow.finance;

import io.agentflow.expense.*;

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

/** 财务工作器及两类恢复入口验证领取失败、旧记录及线程复用，不把诊断上下文作为授权主体。 */
class FinanceWorkerTraceTest {
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
            case PRECHECK -> {
                var runs = mock(JdbcExpensePrecheckRepository.class); var service = mock(ExpensePrecheckService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcExpensePrecheckRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcExpensePrecheckRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcExpensePrecheckRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new ExpensePrecheckWorker(runs, service, mock(ExpensePrecheckEvaluator.class))::poll, ExpensePrecheckWorker.class, "WORKER_FAILURE");
            }
            case INVOICE -> {
                var runs = mock(JdbcInvoiceVerificationRepository.class); var service = mock(InvoiceVerificationService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcInvoiceVerificationRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcInvoiceVerificationRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcInvoiceVerificationRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new InvoiceVerificationWorker(runs, service, mock(JdbcInvoiceOriginalRepository.class), mock(InvoiceOriginalFiles.class), mock(InvoiceVerificationPort.class), mock(FinanceGatewayConfiguration.class))::poll, InvoiceVerificationWorker.class, "WORKER_FAILURE");
            }
            case BUDGET -> {
                var runs = mock(JdbcBudgetOperationRepository.class); var service = mock(BudgetOperationService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcBudgetOperationRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcBudgetOperationRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcBudgetOperationRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new BudgetOperationWorker(runs, service, mock(BudgetSystemPort.class))::poll, BudgetOperationWorker.class, "WORKER_FAILURE");
            }
            case VOUCHER -> {
                var runs = mock(JdbcVoucherOperationRepository.class); var service = mock(VoucherOperationService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcVoucherOperationRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcVoucherOperationRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcVoucherOperationRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new VoucherOperationWorker(runs, service, mock(AccountingVoucherPort.class))::poll, VoucherOperationWorker.class, "WORKER_FAILURE");
            }
            case PAYMENT -> {
                var runs = mock(JdbcPaymentOperationRepository.class); var service = mock(PaymentOperationService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcPaymentOperationRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcPaymentOperationRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcPaymentOperationRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new PaymentOperationWorker(runs, service, mock(PaymentAccountsPort.class), mock(PaymentSystemPort.class), mock(AdvanceDisbursementService.class))::poll, PaymentOperationWorker.class, "WORKER_FAILURE");
            }
            case PREPARATION -> {
                var runs = mock(JdbcVoucherPreparationRepository.class); var service = mock(VoucherPreparationService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcVoucherPreparationRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcVoucherPreparationRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcVoucherPreparationRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new VoucherPreparationWorker(runs, service, mock(AccountingPeriodPort.class), mock(AccountMappingPort.class), mock(JdbcPaymentOperationRepository.class), mock(PaymentVoucherRegistration.class))::poll, VoucherPreparationWorker.class, "WORKER_FAILURE");
            }
            case REQUEST -> {
                var runs = mock(JdbcPaymentExecutionRequestRepository.class); var service = mock(PaymentExecutionRequestService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcPaymentExecutionRequestRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcPaymentExecutionRequestRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcPaymentExecutionRequestRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new PaymentExecutionRequestWorker(runs, service, mock(PaymentAccountsPort.class))::poll, PaymentExecutionRequestWorker.class, "WORKER_FAILURE");
            }
            case PAYEE -> {
                var runs = mock(JdbcPaymentPayeeReviewRepository.class); var service = mock(PaymentPayeeReviewService.class);
                when(runs.due(any(Instant.class))).thenReturn(List.of(
                        new JdbcPaymentPayeeReviewRepository.Candidate("tenant-a", FAILED, SOURCE),
                        new JdbcPaymentPayeeReviewRepository.Candidate("tenant-b", LEGACY, null),
                        new JdbcPaymentPayeeReviewRepository.Candidate("tenant-a", LEGACY, PRIVATE)));
                doAnswer(claim).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new PaymentPayeeReviewWorker(runs, service, mock(PaymentAccountsPort.class))::poll, PaymentPayeeReviewWorker.class, "WORKER_FAILURE");
            }
            case BALANCE_RECOVERY -> {
                var payments = mock(JdbcPaymentOperationRepository.class); var recovery = mock(AdvanceDisbursementService.class);
                when(payments.missingAdvanceBalances()).thenReturn(paymentCandidates());
                doAnswer(claim).when(recovery).recover(anyString(), any(UUID.class));
                yield new Harness(new PaymentOperationWorker(payments, mock(PaymentOperationService.class), mock(PaymentAccountsPort.class),
                        mock(PaymentSystemPort.class), recovery)::poll, PaymentOperationWorker.class, "SETTLEMENT_FAILURE");
            }
            case VOUCHER_RECOVERY -> {
                var payments = mock(JdbcPaymentOperationRepository.class); var recovery = mock(PaymentVoucherRegistration.class);
                when(payments.missingVoucherPreparations()).thenReturn(paymentCandidates());
                doAnswer(claim).when(recovery).recover(anyString(), any(UUID.class));
                yield new Harness(new VoucherPreparationWorker(mock(JdbcVoucherPreparationRepository.class), mock(VoucherPreparationService.class),
                        mock(AccountingPeriodPort.class), mock(AccountMappingPort.class), payments, recovery)::poll,
                        VoucherPreparationWorker.class, "REGISTRATION_FAILURE");
            }
        };
    }

    private List<JdbcPaymentOperationRepository.Candidate> paymentCandidates() {
        return List.of(new JdbcPaymentOperationRepository.Candidate("tenant-a", FAILED, SOURCE),
                new JdbcPaymentOperationRepository.Candidate("tenant-b", LEGACY, null),
                new JdbcPaymentOperationRepository.Candidate("tenant-a", LEGACY, PRIVATE));
    }
    private record Harness(Runnable poll, Class<?> loggerType, String errorCode) { }
    private enum Kind { PRECHECK, INVOICE, BUDGET, VOUCHER, PAYMENT, PREPARATION, REQUEST, PAYEE, BALANCE_RECOVERY, VOUCHER_RECOVERY }
}
