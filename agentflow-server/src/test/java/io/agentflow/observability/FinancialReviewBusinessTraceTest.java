package io.agentflow.observability;

import io.agentflow.budget.*;
import io.agentflow.expense.*;
import io.agentflow.finance.*;
import io.agentflow.procurement.*;
import io.agentflow.common.JsonUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.stubbing.Answer;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 候选 SQL 与实际工作器一起核验原付款、凭证、批准轮次和跨租户隔离。
 * @author owlzhangfq@gmail.com
 */
class FinancialReviewBusinessTraceTest {
    private static final String TENANT="tenant-a", BUSINESS="REVIEW-ORIGINAL", INSTANCE="original-instance";
    private final UUID application=UUID.randomUUID(), id=UUID.randomUUID(), payment=UUID.randomUUID(), advance=UUID.randomUUID(), repayment=UUID.randomUUID(), check=UUID.randomUUID();
    private final String trace=UUID.randomUUID().toString();
    private final JsonUtil json=new JsonUtil(new ObjectMapper());
    private JdbcTemplate jdbc;
    private boolean recoveryFailure;

    @BeforeEach void schema() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:review-business-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("CREATE TABLE approval_application(tenant_id VARCHAR(64),id VARCHAR(36),business_no VARCHAR(128),round_no INT)");
        jdbc.execute("CREATE TABLE approval_submission_round(tenant_id VARCHAR(64),application_id VARCHAR(36),round_no INT,process_instance_id VARCHAR(128))");
        jdbc.execute("CREATE TABLE payment_authorization(tenant_id VARCHAR(64),id VARCHAR(36),application_id VARCHAR(36),round_no INT)");
        jdbc.execute("CREATE TABLE voucher_operation(tenant_id VARCHAR(64),id VARCHAR(36),application_id VARCHAR(36),round_no INT)");
        jdbc.execute("CREATE TABLE advance_repayment(tenant_id VARCHAR(64),id VARCHAR(36),advance_id VARCHAR(36),check_id VARCHAR(36))");
        jdbc.execute("CREATE TABLE advance_repayment_check(tenant_id VARCHAR(64),id VARCHAR(36),advance_id VARCHAR(36),payment_id VARCHAR(36),trace_id VARCHAR(36),status VARCHAR(32),created_at TIMESTAMP,lease_until TIMESTAMP)");
    }
    @AfterEach void cleanup() { MDC.clear(); jdbc.execute("DROP ALL OBJECTS"); }

    @ParameterizedTest @EnumSource(Kind.class)
    void originalFactsSurviveCurrentRoundAdvanceAndFailure(Kind kind) { verify(kind,TENANT,TENANT,true); }

    @ParameterizedTest @EnumSource(Kind.class)
    void missingOriginalRoundNeverBorrowsTheNewInstance(Kind kind) { verify(kind,TENANT,TENANT,false); }

    @ParameterizedTest @EnumSource(Kind.class)
    void anotherTenantApplicationCannotSupplyBusinessContext(Kind kind) { verify(kind,"tenant-b",TENANT,true); }

    @ParameterizedTest @EnumSource(value=Kind.class,names={"DISBURSEMENT_RETURN_CHECK","REPAYMENT_REVIEW_CHECK","ADVANCE_REPAYMENT_CHECK","EXPENSE_PAYMENT_RETURN_CHECK","VOUCHER_REVERSAL_CHECK"})
    void anotherTenantOriginalAuthorizationCannotSupplyContext(Kind kind) { verify(kind,TENANT,"tenant-b",true); }

    @ParameterizedTest @EnumSource(value=Kind.class,names={"BUDGET_ADJUSTMENT_REVIEW","BUDGET_ADJUSTMENT_OPERATION"})
    void failedRecoveryStillLogsTheOriginalApprovedInstance(Kind kind) {
        recoveryFailure=true;
        verify(kind,TENANT,TENANT,true);
    }

    private void verify(Kind kind,String applicationTenant,String sourceTenant,boolean original) {
        jdbc.update("INSERT INTO approval_application VALUES(?,?,?,2)",applicationTenant,application.toString(),BUSINESS);
        jdbc.update("INSERT INTO approval_submission_round VALUES(?,?,2,'current-instance')",applicationTenant,application.toString());
        if(original) jdbc.update("INSERT INTO approval_submission_round VALUES(?,?,1,?)",applicationTenant,application.toString(),INSTANCE);
        jdbc.update("INSERT INTO payment_authorization VALUES(?,?,?,1)",sourceTenant,payment.toString(),application.toString());
        jdbc.update("INSERT INTO voucher_operation VALUES(?,?,?,1)",sourceTenant,payment.toString(),application.toString());
        jdbc.update("INSERT INTO advance_repayment VALUES(?,?,?,?)",sourceTenant,repayment.toString(),advance.toString(),check.toString());
        if(kind==Kind.REPAYMENT_REVIEW_CHECK) jdbc.update("INSERT INTO advance_repayment_check(tenant_id,id,advance_id,payment_id) VALUES(?,?,?,?)",sourceTenant,check.toString(),advance.toString(),payment.toString());
        queue(kind);
        var observed=new ArrayList<Map<String,String>>();
        Answer<Object> capture=invocation->{observed.add(MDC.getCopyOfContextMap());throw new IllegalStateException("private-review-body");};
        var harness=harness(kind,capture);var logger=(Logger)LoggerFactory.getLogger(harness.type());
        var events=new ListAppender<ILoggingEvent>() { @Override protected void append(ILoggingEvent event) { event.prepareForDeferredProcessing();super.append(event); } };
        events.start();logger.addAppender(events);
        try(var outer=new DiagnosticContext(UUID.randomUUID().toString(),"foreign","foreign-business","current-instance","foreign-task").open()) {
            var previous=MDC.getCopyOfContextMap();harness.poll().run();harness.poll().run();assertThat(MDC.getCopyOfContextMap()).isEqualTo(previous);
            boolean bound=TENANT.equals(applicationTenant)&&TENANT.equals(sourceTenant);
            assertThat(observed).hasSize(2).allSatisfy(context->{
                assertThat(context).containsEntry("tenantId",TENANT).containsEntry("traceId",trace).doesNotContainKey("taskId");
                if(bound) assertThat(context).containsEntry("businessNo",BUSINESS);else assertThat(context).doesNotContainKey("businessNo");
                if(bound&&original&&!kind.mode.equals("DRAFT")) assertThat(context).containsEntry("processInstanceId",INSTANCE);
                else assertThat(context).doesNotContainKey("processInstanceId");
            });
            assertThat(events.list.stream().filter(event->event.getLevel()==ch.qos.logback.classic.Level.ERROR).toList()).hasSize(2).allSatisfy(event->{
                assertThat(event.getMDCPropertyMap()).isEqualTo(observed.get(0));assertThat(event.getFormattedMessage()).doesNotContain("private-review-body");assertThat(event.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(events);events.stop(); }
    }

    private void queue(Kind kind) {
        if(kind!=Kind.ADVANCE_REPAYMENT_CHECK) {
            String source=kind.mode.equals("BUDGET")||kind.mode.equals("DRAFT")?"application_id VARCHAR(36),":kind.mode.equals("VOUCHER")?"operation_id VARCHAR(36),":kind.mode.equals("REPAYMENT")?"advance_id VARCHAR(36),repayment_id VARCHAR(36),":"payment_id VARCHAR(36),";
            jdbc.execute("CREATE TABLE "+kind.table+"(tenant_id VARCHAR(64),id VARCHAR(36),trace_id VARCHAR(36),"+source+"status VARCHAR(32),created_at TIMESTAMP,requested_at TIMESTAMP,lease_until TIMESTAMP,next_attempt_at TIMESTAMP,retired_version BIGINT)");
        }
        String column=kind.mode.equals("BUDGET")||kind.mode.equals("DRAFT")?"application_id":kind.mode.equals("VOUCHER")?"operation_id":kind.mode.equals("REPAYMENT")?"repayment_id":"payment_id";
        String value=column.equals("application_id")?application.toString():column.equals("repayment_id")?repayment.toString():payment.toString();
        jdbc.update("INSERT INTO "+kind.table+"(tenant_id,id,trace_id,status,created_at,"+column+") VALUES(?,?,?,'QUEUED',?,?)",TENANT,id.toString(),trace,Timestamp.from(Instant.EPOCH),value);
        if(kind.mode.equals("REPAYMENT")) jdbc.update("UPDATE "+kind.table+" SET advance_id=? WHERE id=?",advance.toString(),id.toString());
        if(kind==Kind.BUDGET_ADJUSTMENT_OPERATION) jdbc.update("UPDATE "+kind.table+" SET next_attempt_at=?",Timestamp.from(Instant.EPOCH));
    }

    private Harness harness(Kind kind,Answer<Object> capture) {
        return switch(kind) {
            case BUDGET_ADJUSTMENT_CHECK -> {
                var runs=new JdbcBudgetAdjustmentCheckRepository(jdbc,json,mock(BudgetAdjustmentRepository.class));var service=mock(BudgetAdjustmentCheckService.class);var port=mock(BudgetAdjustmentCheckEvaluator.class);
                doAnswer(capture).when(service).claim(anyString(),any(UUID.class),any(Instant.class));
                yield new Harness(new BudgetAdjustmentCheckWorker(runs,service,port)::poll,BudgetAdjustmentCheckWorker.class);
            }
            case BUDGET_ADJUSTMENT_REVIEW -> {
                var runs=new JdbcBudgetAdjustmentReviewRepository(jdbc,json,mock(ApprovedBudgetAdjustmentSources.class));var service=mock(BudgetAdjustmentReviewService.class);var port=mock(BudgetLedgerPort.class);
                var claimed=mock(BudgetAdjustmentReview.class,RETURNS_DEEP_STUBS);var source=claimed.input().source();
                when(source.tenantId()).thenReturn(TENANT);when(source.applicationId()).thenReturn(application);when(source.round().roundNo()).thenReturn(1);
                when(service.claim(anyString(),any(UUID.class),any(Instant.class))).thenReturn(claimed);
                if(recoveryFailure) doThrow(new IllegalStateException("private-recovery-body")).when(service).fail(eq(claimed),any(Instant.class));
                doAnswer(capture).when(port).read(anyString(),any(),any());
                yield new Harness(new BudgetAdjustmentReviewWorker(runs,service,port)::poll,BudgetAdjustmentReviewWorker.class);
            }
            case BUDGET_ADJUSTMENT_OPERATION -> {
                var runs=new JdbcBudgetAdjustmentOperationRepository(jdbc,json,mock(ApprovedBudgetAdjustmentSources.class),mock(JdbcBudgetAdjustmentReviewRepository.class));var service=mock(BudgetAdjustmentExecutionService.class);var port=mock(BudgetAdjustmentPort.class);
                var claimed=mock(BudgetAdjustmentOperation.class,RETURNS_DEEP_STUBS);var source=claimed.command().source();
                when(source.tenantId()).thenReturn(TENANT);when(source.applicationId()).thenReturn(application);when(source.round().roundNo()).thenReturn(1);
                when(service.claim(anyString(),any(UUID.class),any(Instant.class))).thenReturn(claimed);
                if(recoveryFailure) doThrow(new IllegalStateException("private-recovery-body")).when(service).fail(eq(claimed),any(Instant.class));
                when(claimed.status()).thenReturn(BudgetAdjustmentOperation.Status.EXECUTING);doAnswer(capture).when(port).execute(any(BudgetAdjustmentCommand.class));
                yield new Harness(new BudgetAdjustmentExecutionWorker(runs,service,port)::poll,BudgetAdjustmentExecutionWorker.class);
            }
            case DISBURSEMENT_RETURN_CHECK -> {
                var runs=new JdbcDisbursementReturnCheckRepository(jdbc,json);var service=mock(AdvanceDisbursementReturnService.class);var port=mock(AdvanceDisbursementReturnPort.class);
                doAnswer(capture).when(service).claim(anyString(),any(UUID.class),any(Instant.class));
                yield new Harness(new AdvanceDisbursementReturnWorker(runs,service,port)::poll,AdvanceDisbursementReturnWorker.class);
            }
            case REPAYMENT_REVIEW_CHECK -> {
                var runs=new JdbcRepaymentReviewCheckRepository(jdbc,json);var service=mock(AdvanceRepaymentReviewService.class);var port=mock(AdvanceRepaymentAdjustmentPort.class);
                doAnswer(capture).when(service).claim(anyString(),any(UUID.class),any(Instant.class));
                yield new Harness(new AdvanceRepaymentReviewWorker(runs,service,port)::poll,AdvanceRepaymentReviewWorker.class);
            }
            case ADVANCE_REPAYMENT_CHECK -> {
                var runs=new JdbcAdvanceRepaymentCheckRepository(jdbc,json);var service=mock(AdvanceRepaymentService.class);var port=mock(AdvanceRepaymentPort.class);
                doAnswer(capture).when(service).claim(anyString(),any(UUID.class),any(Instant.class));
                yield new Harness(new AdvanceRepaymentWorker(runs,service,port)::poll,AdvanceRepaymentWorker.class);
            }
            case ADVANCE_REQUEST_CHECK -> {
                var runs=new JdbcAdvanceRequestCheckRepository(jdbc,json);var service=mock(AdvanceRequestCheckService.class);var port=mock(AdvanceRequestCheckEvaluator.class);
                doAnswer(capture).when(service).claim(anyString(),any(UUID.class),any(Instant.class));
                yield new Harness(new AdvanceRequestCheckWorker(runs,service,port)::poll,AdvanceRequestCheckWorker.class);
            }
            case EXPENSE_PAYMENT_RETURN_CHECK -> {
                var runs=new JdbcExpensePaymentReturnCheckRepository(jdbc,json);var service=mock(ExpensePaymentReturnService.class);var port=mock(ExpensePaymentReturnPort.class);
                doAnswer(capture).when(service).claim(anyString(),any(UUID.class),any(Instant.class));
                yield new Harness(new ExpensePaymentReturnWorker(runs,service,port)::poll,ExpensePaymentReturnWorker.class);
            }
            case EXPENSE_PLAN_CHECK -> {
                var runs=new JdbcExpensePlanCheckRepository(jdbc,json);var service=mock(ExpensePlanCheckService.class);var port=mock(ExpensePlanCheckEvaluator.class);
                doAnswer(capture).when(service).claim(anyString(),any(UUID.class),any(Instant.class));
                yield new Harness(new ExpensePlanCheckWorker(runs,service,port)::poll,ExpensePlanCheckWorker.class);
            }
            case VOUCHER_REVERSAL_CHECK -> {
                var runs=new JdbcVoucherReversalCheckRepository(jdbc,json);var service=mock(VoucherReversalService.class);var port=mock(VoucherReversalPort.class);
                doAnswer(capture).when(service).claim(anyString(),any(UUID.class),any(Instant.class));
                yield new Harness(new VoucherReversalWorker(runs,service,port)::poll,VoucherReversalWorker.class);
            }
            case PROCUREMENT_PAYMENT_CHECK -> {
                var runs=new JdbcProcurementPaymentCheckRepository(jdbc,json);var service=mock(ProcurementPaymentCheckService.class);var port=mock(ProcurementPaymentCheckEvaluator.class);
                doAnswer(capture).when(service).claim(anyString(),any(UUID.class),any(Instant.class));
                yield new Harness(new ProcurementPaymentCheckWorker(runs,service,port)::poll,ProcurementPaymentCheckWorker.class);
            }
        };
    }
    /**
     * @author owlzhangfq@gmail.com
     */
    private record Harness(Runnable poll,Class<?> type) { }
    /**
     * @author owlzhangfq@gmail.com
     */
    private enum Kind {
        BUDGET_ADJUSTMENT_CHECK("budget_adjustment_check_job","DRAFT"),
        BUDGET_ADJUSTMENT_REVIEW("budget_adjustment_review","BUDGET"),
        BUDGET_ADJUSTMENT_OPERATION("budget_adjustment_operation","BUDGET"),
        DISBURSEMENT_RETURN_CHECK("disbursement_return_check","PAYMENT"),
        REPAYMENT_REVIEW_CHECK("repayment_review_check","REPAYMENT"),
        ADVANCE_REPAYMENT_CHECK("advance_repayment_check","PAYMENT"),
        ADVANCE_REQUEST_CHECK("advance_request_check_job","DRAFT"),
        EXPENSE_PAYMENT_RETURN_CHECK("expense_payment_return_check","PAYMENT"),
        EXPENSE_PLAN_CHECK("expense_plan_check_job","DRAFT"),
        VOUCHER_REVERSAL_CHECK("voucher_reversal_check","VOUCHER"),
        PROCUREMENT_PAYMENT_CHECK("procurement_payment_check_job","DRAFT");
        private final String table,mode;
        Kind(String table,String mode) { this.table=table;this.mode=mode; }
    }
}
