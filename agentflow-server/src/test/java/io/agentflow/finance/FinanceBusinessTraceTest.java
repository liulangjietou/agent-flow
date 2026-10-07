package io.agentflow.finance;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.*;
import io.agentflow.observability.DiagnosticContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

/** 真实扫描 SQL 验证原业务投影与工作器作用域；完整约束和领取规则由财务持久化及 HTTP 集成测试覆盖。 */
class FinanceBusinessTraceTest {
    private static final String TENANT = "tenant-a", BUSINESS = "ORIGINAL-FINANCE", INSTANCE = "original-instance";
    private final UUID applicationId = UUID.randomUUID(), reportId = UUID.randomUUID(), runId = UUID.randomUUID();
    private final String traceId = UUID.randomUUID().toString();
    private final JsonUtil json = new JsonUtil(new ObjectMapper());
    private JdbcTemplate jdbc;

    @BeforeEach void schema() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:finance-business-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE approval_application(tenant_id VARCHAR(64),id VARCHAR(36),business_no VARCHAR(128),round_no INT)");
        jdbc.execute("CREATE TABLE approval_submission_round(tenant_id VARCHAR(64),application_id VARCHAR(36),round_no INT,process_instance_id VARCHAR(128))");
        jdbc.execute("CREATE TABLE expense_report(tenant_id VARCHAR(64),id VARCHAR(36),application_id VARCHAR(36))");
        jdbc.execute("CREATE TABLE payment_authorization(tenant_id VARCHAR(64),id VARCHAR(36),application_id VARCHAR(36),round_no INT,purpose VARCHAR(32),business_id VARCHAR(36))");
        jdbc.execute("CREATE TABLE finance_resource(tenant_id VARCHAR(64),resource_type VARCHAR(32),id VARCHAR(36))");
        for (String table : new String[]{"expense_precheck_job", "budget_operation", "voucher_operation", "payment_operation",
                "voucher_preparation", "payment_execution_request", "payment_payee_review"}) {
            jdbc.execute("CREATE TABLE " + table + "(tenant_id VARCHAR(64),id VARCHAR(36),application_id VARCHAR(36),report_id VARCHAR(36),"
                    + "round_no INT,authorization_id VARCHAR(36),original_authorization_id VARCHAR(36),kind VARCHAR(32),trace_id VARCHAR(36),"
                    + "status VARCHAR(32),created_at TIMESTAMP,updated_at TIMESTAMP,next_attempt_at TIMESTAMP,lease_until TIMESTAMP)");
        }
    }

    @AfterEach void cleanup() { MDC.clear(); jdbc.execute("DROP ALL OBJECTS"); }

    @ParameterizedTest @EnumSource(Kind.class)
    void claimRecoveryAndFailureKeepOriginalFactsAfterTheApplicationAdvances(Kind kind) {
        source(TENANT, true); enqueue(kind);
        var observed = new ArrayList<Map<String, String>>();
        var harness = worker(kind, invocation -> { observed.add(MDC.getCopyOfContextMap()); throw new IllegalStateException("private-finance-sentinel"); });
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
            assertThat(events.list).hasSize(2).allSatisfy(event -> {
                expected(event.getMDCPropertyMap(), kind, true);
                assertThat(event.getFormattedMessage()).doesNotContain("private-finance-sentinel");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(events); events.stop(); }
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @ParameterizedTest @EnumSource(value = Kind.class, names = {"PRECHECK", "BUDGET"}, mode = EnumSource.Mode.EXCLUDE)
    void absentOriginalRoundDoesNotBorrowCurrentInstance(Kind kind) {
        source(TENANT, false); enqueue(kind);
        var observed = new ArrayList<Map<String, String>>();
        worker(kind, invocation -> { observed.add(MDC.getCopyOfContextMap()); return null; }).poll().run();
        assertThat(observed).singleElement().satisfies(context -> expected(context, kind, false));
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void sameIdentifierInAnotherTenantDoesNotSupplyBusinessMetadata(Kind kind) {
        source("tenant-b", true); enqueue(kind);
        assertMissing(workerContext(kind));
    }

    @ParameterizedTest @EnumSource(value = Kind.class, names = {"BUDGET", "PAYMENT", "REQUEST", "PAYEE"})
    void intermediateIndexAlsoRequiresTheQueueTenant(Kind kind) {
        source(TENANT, true); enqueue(kind);
        jdbc.update("UPDATE " + (kind == Kind.BUDGET ? "expense_report" : "payment_authorization") + " SET tenant_id='tenant-b'");
        assertMissing(workerContext(kind));
    }

    private Map<String, String> workerContext(Kind kind) {
        var observed = new ArrayList<Map<String, String>>();
        worker(kind, invocation -> { observed.add(MDC.getCopyOfContextMap()); return null; }).poll().run();
        assertThat(observed).hasSize(1); return observed.get(0);
    }

    private void assertMissing(Map<String, String> context) {
        assertThat(context).containsEntry("tenantId", TENANT).containsEntry("traceId", traceId)
                .doesNotContainKeys("businessNo", "processInstanceId", "taskId");
    }

    private void expected(Map<String, String> context, Kind kind, boolean hasRound) {
        assertThat(context).containsEntry("tenantId", TENANT).containsEntry("traceId", traceId)
                .containsEntry("businessNo", BUSINESS).doesNotContainKey("taskId");
        if (kind != Kind.PRECHECK && kind != Kind.BUDGET && hasRound) assertThat(context).containsEntry("processInstanceId", INSTANCE);
        else assertThat(context).doesNotContainKey("processInstanceId");
    }

    private void source(String tenant, boolean originalRound) {
        jdbc.update("INSERT INTO approval_application VALUES(?,?,?,2)", tenant, applicationId.toString(), BUSINESS);
        jdbc.update("INSERT INTO approval_submission_round VALUES(?,?,2,'new-instance')", tenant, applicationId.toString());
        if (originalRound) jdbc.update("INSERT INTO approval_submission_round VALUES(?,?,1,?)", tenant, applicationId.toString(), INSTANCE);
        jdbc.update("INSERT INTO expense_report VALUES(?,?,?)", TENANT, reportId.toString(), applicationId.toString());
        jdbc.update("INSERT INTO payment_authorization VALUES(?,?,?,1,'EMPLOYEE_ADVANCE',?)", TENANT, runId.toString(), applicationId.toString(), reportId.toString());
    }

    private void enqueue(Kind kind) {
        String table = switch (kind) {
            case PRECHECK -> "expense_precheck_job"; case BUDGET -> "budget_operation"; case VOUCHER -> "voucher_operation";
            case PAYMENT, BALANCE_RECOVERY, VOUCHER_RECOVERY -> "payment_operation";
            case PREPARATION -> "voucher_preparation"; case REQUEST -> "payment_execution_request"; case PAYEE -> "payment_payee_review";
        };
        var now = Timestamp.from(Instant.now().minusSeconds(1));
        String status = kind == Kind.BALANCE_RECOVERY || kind == Kind.VOUCHER_RECOVERY ? "SUCCEEDED" : "QUEUED";
        jdbc.update("INSERT INTO " + table + "(tenant_id,id,application_id,report_id,round_no,authorization_id,original_authorization_id,kind,trace_id,status,created_at,updated_at,next_attempt_at) VALUES(?,?,?,?,1,?,?,'PAYMENT',?,?,?,?,?)",
                TENANT, runId.toString(), applicationId.toString(), reportId.toString(), runId.toString(), runId.toString(), traceId, status, now, now, now);
    }

    private Harness worker(Kind kind, Answer<Object> claim) {
        return switch (kind) {
            case PRECHECK -> {
                var service = mock(ExpensePrecheckService.class); doAnswer(claim).when(service).claim(anyString(), any(), any());
                yield new Harness(new ExpensePrecheckWorker(new JdbcExpensePrecheckRepository(jdbc, json), service, mock(ExpensePrecheckEvaluator.class))::poll, ExpensePrecheckWorker.class);
            }
            case BUDGET -> {
                var service = mock(BudgetOperationService.class); doAnswer(claim).when(service).claim(anyString(), any(), any());
                yield new Harness(new BudgetOperationWorker(new JdbcBudgetOperationRepository(jdbc, json), service, mock(BudgetSystemPort.class))::poll, BudgetOperationWorker.class);
            }
            case VOUCHER -> {
                var service = mock(VoucherOperationService.class); doAnswer(claim).when(service).claim(anyString(), any(), any());
                yield new Harness(new VoucherOperationWorker(new JdbcVoucherOperationRepository(jdbc, json), service, mock(AccountingVoucherPort.class))::poll, VoucherOperationWorker.class);
            }
            case PAYMENT -> {
                var service = mock(PaymentOperationService.class); doAnswer(claim).when(service).claim(anyString(), any(), any());
                yield new Harness(new PaymentOperationWorker(new JdbcPaymentOperationRepository(jdbc, json, new JdbcPaymentAuthorizationRepository(jdbc, json)), service, mock(PaymentAccountsPort.class), mock(PaymentSystemPort.class), mock(AdvanceDisbursementService.class))::poll, PaymentOperationWorker.class);
            }
            case PREPARATION -> {
                var service = mock(VoucherPreparationService.class); doAnswer(claim).when(service).claim(anyString(), any(), any());
                yield new Harness(new VoucherPreparationWorker(new JdbcVoucherPreparationRepository(jdbc, json), service, mock(AccountingPeriodPort.class), mock(AccountMappingPort.class), mock(JdbcPaymentOperationRepository.class), mock(PaymentVoucherRegistration.class))::poll, VoucherPreparationWorker.class);
            }
            case REQUEST -> {
                var service = mock(PaymentExecutionRequestService.class); doAnswer(claim).when(service).claim(anyString(), any(), any());
                yield new Harness(new PaymentExecutionRequestWorker(new JdbcPaymentExecutionRequestRepository(jdbc, json, new JdbcPaymentAuthorizationRepository(jdbc, json)), service, mock(PaymentAccountsPort.class))::poll, PaymentExecutionRequestWorker.class);
            }
            case PAYEE -> {
                var service = mock(PaymentPayeeReviewService.class); doAnswer(claim).when(service).claim(anyString(), any(), any());
                yield new Harness(new PaymentPayeeReviewWorker(new JdbcPaymentPayeeReviewRepository(jdbc, json), service, mock(PaymentAccountsPort.class))::poll, PaymentPayeeReviewWorker.class);
            }
            case BALANCE_RECOVERY -> {
                var service = mock(AdvanceDisbursementService.class); doAnswer(claim).when(service).recover(anyString(), any());
                yield new Harness(new PaymentOperationWorker(new JdbcPaymentOperationRepository(jdbc, json, new JdbcPaymentAuthorizationRepository(jdbc, json)), mock(PaymentOperationService.class), mock(PaymentAccountsPort.class), mock(PaymentSystemPort.class), service)::poll, PaymentOperationWorker.class);
            }
            case VOUCHER_RECOVERY -> {
                var service = mock(PaymentVoucherRegistration.class); doAnswer(claim).when(service).recover(anyString(), any());
                yield new Harness(new VoucherPreparationWorker(mock(JdbcVoucherPreparationRepository.class), mock(VoucherPreparationService.class), mock(AccountingPeriodPort.class), mock(AccountMappingPort.class), new JdbcPaymentOperationRepository(jdbc, json, new JdbcPaymentAuthorizationRepository(jdbc, json)), service)::poll, VoucherPreparationWorker.class);
            }
        };
    }

    private record Harness(Runnable poll, Class<?> loggerType) { }
    private enum Kind { PRECHECK, BUDGET, VOUCHER, PAYMENT, PREPARATION, REQUEST, PAYEE, BALANCE_RECOVERY, VOUCHER_RECOVERY }
}
