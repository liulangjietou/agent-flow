package io.agentflow.observability;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentflow.approval.process.ExpenseBudgetReviewRecovery;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.*;
import io.agentflow.finance.*;

import org.junit.jupiter.api.*;
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

/**
 * 真实扫描 SQL 与工作器共同验证原报销轮次；当前轮次和其他租户不能替代持久来源。
 *
 * @author owlzhangfq@gmail.com
 */
class ExpenseRecoveryBusinessTraceTest {
    private static final String TENANT="tenant-a", BUSINESS="EXPENSE-ORIGINAL", INSTANCE="original-instance";
    private final UUID application=UUID.randomUUID(), report=UUID.randomUUID(), id=UUID.randomUUID(), origin=UUID.randomUUID(), command=UUID.randomUUID();
    private final String trace=UUID.randomUUID().toString();
    private final JsonUtil json=new JsonUtil(new ObjectMapper());
    private JdbcTemplate jdbc;

    @BeforeEach void schema() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:recovery-business-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute(
                "CREATE TABLE approval_application(tenant_id VARCHAR(64),id VARCHAR(36),business_no"
                        + " VARCHAR(128),round_no INT,status VARCHAR(32))");
        jdbc.execute(
                "CREATE TABLE approval_submission_round(tenant_id VARCHAR(64),application_id"
                        + " VARCHAR(36),round_no INT,process_instance_id VARCHAR(128))");
        jdbc.execute(
                "CREATE TABLE expense_report(tenant_id VARCHAR(64),id VARCHAR(36),application_id"
                        + " VARCHAR(36))");
        jdbc.execute(
                "CREATE TABLE expense_archive(tenant_id VARCHAR(64),report_id VARCHAR(36),round_no"
                        + " INT,archived_at TIMESTAMP)");
        jdbc.execute(
                "CREATE TABLE expense_partial_adjustment_operation(tenant_id VARCHAR(64),id"
                        + " VARCHAR(36),adjustment_id VARCHAR(36),trace_id VARCHAR(36))");
        for(var table:List.of("expense_settlement","expense_budget_review","budget_operation","expense_partial_adjustment_preparation",
                "expense_partial_adjustment","expense_resource_adjustment_preparation","expense_resource_adjustment",
                "budget_consumption_reversal_operation","payment_operation","payment_authorization","voucher_operation",
                "voucher_preparation","voucher_reversal_preparation","voucher_reversal_operation")) {
            jdbc.execute("CREATE TABLE "+table+ "(tenant_id VARCHAR(64),id VARCHAR(36),report_id"
                            + " VARCHAR(36),business_id VARCHAR(36),application_id"
                            + " VARCHAR(36),round_no INT,version BIGINT,trace_id VARCHAR(36),status"
                            + " VARCHAR(32),kind VARCHAR(32),purpose VARCHAR(32),adjustment_id"
                            + " VARCHAR(36),operation_id VARCHAR(36),consumption_id"
                            + " VARCHAR(36),budget_operation_id VARCHAR(36),accrual_operation_id"
                            + " VARCHAR(36),original_operation_id"
                            + " VARCHAR(36),authorized_operation_id VARCHAR(36),budget_node_id"
                            + " VARCHAR(64),automatic_audit_id VARCHAR(36),budget_status"
                            + " VARCHAR(32),accrual_status VARCHAR(32),created_at"
                            + " TIMESTAMP,updated_at TIMESTAMP,next_attempt_at"
                            + " TIMESTAMP,next_check_at TIMESTAMP,lease_until"
                            + " TIMESTAMP,budget_next_at TIMESTAMP,accrual_next_at"
                            + " TIMESTAMP,budget_lease_until TIMESTAMP,accrual_lease_until"
                            + " TIMESTAMP,completed_at TIMESTAMP,retired_at TIMESTAMP)");
        }
    }
    @AfterEach void cleanup() { MDC.clear();jdbc.execute("DROP ALL OBJECTS"); }

    @ParameterizedTest @EnumSource(Kind.class)
    void originalRoundRemainsStableAcrossWorkerRetries(Kind kind) { verify(kind,TENANT,TENANT,true); }
    @ParameterizedTest @EnumSource(Kind.class)
    void missingOriginalRoundDoesNotBorrowAnotherInstance(Kind kind) { verify(kind,TENANT,TENANT,false); }
    @ParameterizedTest @EnumSource(value=Kind.class,mode=EnumSource.Mode.EXCLUDE,names="BUDGET_REVIEW")
    void anotherTenantApplicationCannotSupplyContext(Kind kind) { verify(kind,"tenant-b",TENANT,true); }
    @ParameterizedTest @EnumSource(value=Kind.class,names={"PARTIAL_PREPARATION","PARTIAL_BUDGET","PARTIAL_ACCRUAL","PARTIAL_COMPLETION","RESOURCE_PREPARATION","RESOURCE_BUDGET","RESOURCE_COMPLETION","REVERSAL_PREPARATION","REVERSAL"})
    void anotherTenantOriginalSourceCannotSupplyContext(Kind kind) { verify(kind,TENANT,"tenant-b",true); }

    @Test void budgetReviewKeepsCurrentRoundEligibilityGuard() {
        seed(Kind.BUDGET_REVIEW,TENANT,TENANT,true);
        jdbc.update("UPDATE approval_application SET round_no=2");
        assertThat(budgetReviews().due(Instant.now())).isEmpty();
    }
    @Test void budgetReviewCannotUseAnotherTenantApplication() {
        seed(Kind.BUDGET_REVIEW,"tenant-b",TENANT,true);
        assertThat(budgetReviews().due(Instant.now())).isEmpty();
    }
    @Test void paymentRecoveryCannotUseAnotherTenantAuthorization() {
        seed(Kind.PAYMENT_RECOVERY,TENANT,TENANT,true);
        jdbc.update("UPDATE payment_authorization SET tenant_id='tenant-b'");
        assertThat(new JdbcExpenseSettlementRepository(
                                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                                (jdbc).getDataSource(),
                                                io.agentflow.expense.mapper
                                                        .ExpenseSettlementRepositoryMapper.class),json).recoveryCandidates(null)).isEmpty();
    }
    private JdbcExpenseBudgetReviewRepository budgetReviews() {
        return new JdbcExpenseBudgetReviewRepository(
                io.agentflow.mybatis.MyBatisTestSupport.mapper(
                        (jdbc).getDataSource(),
                        io.agentflow.expense.mapper.ExpenseBudgetReviewRepositoryMapper.class),json,mock(JdbcBudgetOperationRepository.class),mock(JdbcExpenseSubmissionControlRepository.class),mock(JdbcExpensePrecheckRepository.class));
    }
    private void seed(Kind kind,String appTenant,String sourceTenant,boolean originalRound) {
        jdbc.update("INSERT INTO approval_application VALUES(?,?,?,?,'IN_APPROVAL')",appTenant,application.toString(),BUSINESS,kind==Kind.BUDGET_REVIEW?1:2);
        jdbc.update("INSERT INTO approval_submission_round VALUES(?,?,2,'current-instance')",appTenant,application.toString());
        if(originalRound) jdbc.update("INSERT INTO approval_submission_round VALUES(?,?,1,?)",appTenant,application.toString(),INSTANCE);
        jdbc.update("INSERT INTO expense_report VALUES(?,?,?)",sourceTenant,report.toString(),application.toString());
        switch(kind) {
            case ARCHIVE,SETTLEMENT -> row("expense_settlement",TENANT,id,kind==Kind.ARCHIVE?"SETTLED":"QUEUED");
            case BUDGET_REVIEW -> {
                row("expense_budget_review",TENANT,id,"WAITING_BUDGET"); row("budget_operation",TENANT,command,"APPLIED");
                jdbc.update("UPDATE expense_budget_review SET trace_id=?,original_operation_id=?",UUID.randomUUID().toString(),command.toString());
            }
            case PARTIAL_PREPARATION -> {
                row("expense_partial_adjustment_preparation",TENANT,id,"QUEUED");row("expense_partial_adjustment",sourceTenant,origin,"PREPARING");
                jdbc.update("UPDATE expense_partial_adjustment_preparation SET adjustment_id=?",origin.toString());
            }
            case PARTIAL_BUDGET,PARTIAL_ACCRUAL,PARTIAL_COMPLETION -> {
                row("expense_partial_adjustment",TENANT,id,kind==Kind.PARTIAL_COMPLETION?"READY":"RUNNING");
                if(kind!=Kind.PARTIAL_COMPLETION) {
                    String side=kind==Kind.PARTIAL_BUDGET?"budget":"accrual";
                    jdbc.update("UPDATE expense_partial_adjustment SET trace_id=?,"+side+"_operation_id=?,"+side+"_status='QUEUED',"+side+"_next_at=?",UUID.randomUUID().toString(),command.toString(),Timestamp.from(Instant.EPOCH));
                    jdbc.update("INSERT INTO expense_partial_adjustment_operation VALUES(?,?,?,?)",TENANT,command.toString(),id.toString(),trace);
                }
            }
            case RESOURCE_PREPARATION -> {
                row("expense_resource_adjustment_preparation",TENANT,id,"QUEUED");row("expense_settlement",sourceTenant,origin,"SETTLED");
                jdbc.update("UPDATE expense_resource_adjustment_preparation SET consumption_id=?",command.toString());
                jdbc.update("UPDATE expense_settlement SET budget_operation_id=?",command.toString());
            }
            case RESOURCE_BUDGET -> { row("budget_consumption_reversal_operation",TENANT,id,"QUEUED");row("expense_resource_adjustment",sourceTenant,id,"WAITING_BUDGET"); }
            case RESOURCE_COMPLETION -> row("expense_resource_adjustment",TENANT,id,"READY");
            case PAYMENT_RECOVERY -> { row("payment_operation",TENANT,id,"SUCCEEDED");row("payment_authorization",TENANT,id,"AUTHORIZED");jdbc.update("UPDATE payment_authorization SET purpose='EXPENSE_REIMBURSEMENT'"); }
            case VOUCHER_RECOVERY -> row("voucher_operation",TENANT,id,"POSTED");
            case ZERO_RECOVERY -> row("voucher_preparation",TENANT,id,"NOT_REQUIRED");
            case REVERSAL_PREPARATION,REVERSAL -> {
                String table=kind==Kind.REVERSAL_PREPARATION?"voucher_reversal_preparation":"voucher_reversal_operation";
                row(table,TENANT,id,"QUEUED");row("voucher_operation",sourceTenant,origin,"POSTED");
                jdbc.update("UPDATE "+table+" SET operation_id=?",origin.toString());
            }
        }
    }
    private void row(String table,String tenant,UUID identity,String status) {
        var at=Timestamp.from(Instant.EPOCH);
        jdbc.update("INSERT INTO "+table+ "(tenant_id,id,report_id,business_id,application_id,round_no,version,trace_id,status,kind,created_at,updated_at,next_attempt_at,next_check_at)"
                        + " VALUES(?,?,?,?,?,1,1,?,?,'EXPENSE_ACCRUAL',?,?,?,?)",
                tenant,identity.toString(),report.toString(),report.toString(),application.toString(),trace,status,at,at,at,at);
    }
    private void verify(Kind kind,String appTenant,String sourceTenant,boolean originalRound) {
        seed(kind,appTenant,sourceTenant,originalRound);
        var observed=new ArrayList<Map<String,String>>();Answer<Object> capture=invocation->{observed.add(MDC.getCopyOfContextMap());throw new IllegalStateException("private-recovery-input");};
        var harness=harness(kind,capture);var logger=(Logger)LoggerFactory.getLogger(harness.loggerType());
        var events=new ListAppender<ILoggingEvent>() { @Override protected void append(ILoggingEvent event) { event.prepareForDeferredProcessing();super.append(event); } };
        events.start();logger.addAppender(events);
        try(var outer=new DiagnosticContext(UUID.randomUUID().toString(),"foreign","foreign-business","current-instance","foreign-task").open()) {
            var previous=MDC.getCopyOfContextMap();harness.poll().run();harness.poll().run();assertThat(MDC.getCopyOfContextMap()).isEqualTo(previous);
            boolean bound=TENANT.equals(appTenant)&&TENANT.equals(sourceTenant);
            assertThat(observed).hasSize(2).allSatisfy(context->{
                assertThat(context).containsEntry("tenantId",TENANT).containsEntry("traceId",trace).doesNotContainKey("taskId");
                if(bound) assertThat(context).containsEntry("businessNo",BUSINESS);else assertThat(context).doesNotContainKey("businessNo");
                if(bound&&originalRound) assertThat(context).containsEntry("processInstanceId",INSTANCE);else assertThat(context).doesNotContainKey("processInstanceId");
            });
            assertThat(events.list.stream().filter(event->event.getLevel()==ch.qos.logback.classic.Level.ERROR).toList()).hasSize(2).allSatisfy(event->{
                assertThat(event.getMDCPropertyMap()).isEqualTo(observed.get(0));assertThat(event.getFormattedMessage()).contains(harness.errorCode()).doesNotContain("private-recovery-input");assertThat(event.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(events);events.stop(); }
    }
    private Harness harness(Kind kind,Answer<Object> capture) {
        return switch(kind) {
            case ARCHIVE -> {
                var runs = mock(JdbcExpenseArchiveRepository.class); var service = mock(ExpenseArchiveService.class);
                var scanned = new JdbcExpenseArchiveRepository(
                                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                                (jdbc).getDataSource(),
                                                io.agentflow.expense.mapper
                                                        .ExpenseArchiveRepositoryMapper.class),json).candidates(null);
                assertThat(scanned).hasSize(1);
                when(runs.candidates(any())).thenReturn(scanned);
                doAnswer(capture).when(service).prepare(anyString(), any(UUID.class));
                yield new Harness(new ExpenseArchiveWorker(runs, service, mock(ExpenseArchiveFiles.class))::poll, ExpenseArchiveWorker.class, "ARCHIVE_FAILURE");
            }
            case BUDGET_REVIEW -> {
                var runs = mock(JdbcExpenseBudgetReviewRepository.class); var service = mock(ExpenseBudgetReviewRecovery.class);
                var scanned = new JdbcExpenseBudgetReviewRepository(
                                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                                (jdbc).getDataSource(),
                                                io.agentflow.expense.mapper
                                                        .ExpenseBudgetReviewRepositoryMapper.class),json,mock(JdbcBudgetOperationRepository.class),mock(JdbcExpenseSubmissionControlRepository.class),mock(JdbcExpensePrecheckRepository.class)).due(Instant.now());
                assertThat(scanned).hasSize(1);
                when(runs.due(any(Instant.class))).thenReturn(scanned);
                doAnswer(capture).when(service).recover(any());
                yield new Harness(new ExpenseBudgetReviewWorker(runs, service)::poll, ExpenseBudgetReviewWorker.class, "RECOVERY_FAILURE");
            }
            case PARTIAL_PREPARATION -> {
                var runs = mock(JdbcExpensePartialPreparationRepository.class); var service = mock(ExpensePartialPreparationService.class);
                var scanned = new JdbcExpensePartialPreparationRepository(
                                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                                (jdbc).getDataSource(),
                                                io.agentflow.expense.mapper
                                                        .ExpensePartialPreparationRepositoryMapper
                                                        .class),json,mock(ExpenseReportRepository.class),mock(JdbcExpensePartialAdjustmentRepository.class),mock(ExpensePartialAdjustmentSources.class)).due(Instant.now());
                assertThat(scanned).hasSize(1);
                when(runs.due(any(Instant.class))).thenReturn(scanned);
                doAnswer(capture).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new ExpensePartialAdjustmentWorker(mock(JdbcExpensePartialAdjustmentRepository.class), mock(ExpensePartialAdjustmentFinance.class), mock(BudgetConsumptionReductionPort.class), mock(ExpenseAccrualReductionPort.class), mock(ExpensePartialAdjustmentExecution.class), runs, service, mock(ExpensePartialPreparationReader.class))::poll, ExpensePartialAdjustmentWorker.class, "PARTIAL_PREPARATION_WORKER_FAILURE");
            }
            case PARTIAL_BUDGET -> {
                var runs = mock(JdbcExpensePartialAdjustmentRepository.class); var service = mock(ExpensePartialAdjustmentFinance.class);
                var scanned = new JdbcExpensePartialAdjustmentRepository(
                                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                                (jdbc).getDataSource(),
                                                io.agentflow.expense.mapper
                                                        .ExpensePartialAdjustmentRepositoryMapper
                                                        .class),json,mock(ExpenseReportRepository.class),mock(ExpensePartialAdjustmentSources.class),mock(ExpensePartialAdjustmentGuard.class),mock(JdbcExpensePartialDisputeRepository.class)).dueBudget(Instant.now());
                assertThat(scanned).hasSize(1);
                when(runs.dueBudget(any(Instant.class))).thenReturn(scanned);
                doAnswer(capture).when(service).claimBudget(anyString(), any(UUID.class), eq(command), any(Instant.class));
                yield new Harness(new ExpensePartialAdjustmentWorker(runs, service, mock(BudgetConsumptionReductionPort.class), mock(ExpenseAccrualReductionPort.class), mock(ExpensePartialAdjustmentExecution.class), mock(JdbcExpensePartialPreparationRepository.class), mock(ExpensePartialPreparationService.class), mock(ExpensePartialPreparationReader.class))::poll, ExpensePartialAdjustmentWorker.class, "PARTIAL_BUDGET_WORKER_FAILURE");
            }
            case PARTIAL_ACCRUAL -> {
                var runs = mock(JdbcExpensePartialAdjustmentRepository.class); var service = mock(ExpensePartialAdjustmentFinance.class);
                var scanned = new JdbcExpensePartialAdjustmentRepository(
                                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                                (jdbc).getDataSource(),
                                                io.agentflow.expense.mapper
                                                        .ExpensePartialAdjustmentRepositoryMapper
                                                        .class),json,mock(ExpenseReportRepository.class),mock(ExpensePartialAdjustmentSources.class),mock(ExpensePartialAdjustmentGuard.class),mock(JdbcExpensePartialDisputeRepository.class)).dueAccrual(Instant.now());
                assertThat(scanned).hasSize(1);
                when(runs.dueAccrual(any(Instant.class))).thenReturn(scanned);
                doAnswer(capture).when(service).claimAccrual(anyString(), any(UUID.class), eq(command), any(Instant.class));
                yield new Harness(new ExpensePartialAdjustmentWorker(runs, service, mock(BudgetConsumptionReductionPort.class), mock(ExpenseAccrualReductionPort.class), mock(ExpensePartialAdjustmentExecution.class), mock(JdbcExpensePartialPreparationRepository.class), mock(ExpensePartialPreparationService.class), mock(ExpensePartialPreparationReader.class))::poll, ExpensePartialAdjustmentWorker.class, "PARTIAL_ACCRUAL_WORKER_FAILURE");
            }
            case PARTIAL_COMPLETION -> {
                var runs = mock(JdbcExpensePartialAdjustmentRepository.class); var service = mock(ExpensePartialAdjustmentExecution.class);
                var scanned = new JdbcExpensePartialAdjustmentRepository(
                                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                                (jdbc).getDataSource(),
                                                io.agentflow.expense.mapper
                                                        .ExpensePartialAdjustmentRepositoryMapper
                                                        .class),json,mock(ExpenseReportRepository.class),mock(ExpensePartialAdjustmentSources.class),mock(ExpensePartialAdjustmentGuard.class),mock(JdbcExpensePartialDisputeRepository.class)).ready();
                assertThat(scanned).hasSize(1);
                when(runs.ready()).thenReturn(scanned);
                doAnswer(capture).when(service).apply(any());
                yield new Harness(new ExpensePartialAdjustmentWorker(runs, mock(ExpensePartialAdjustmentFinance.class), mock(BudgetConsumptionReductionPort.class), mock(ExpenseAccrualReductionPort.class), service, mock(JdbcExpensePartialPreparationRepository.class), mock(ExpensePartialPreparationService.class), mock(ExpensePartialPreparationReader.class))::poll, ExpensePartialAdjustmentWorker.class, "PARTIAL_RESOURCE_FAILURE");
            }
            case RESOURCE_PREPARATION -> {
                var runs = mock(JdbcExpenseResourceAdjustmentPreparationRepository.class); var service = mock(ExpenseResourceAdjustmentPreparationService.class);
                var scanned = new JdbcExpenseResourceAdjustmentPreparationRepository(
                                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                                (jdbc).getDataSource(),
                                                io.agentflow.expense.mapper
                                                        .ExpenseResourceAdjustmentPreparationRepositoryMapper
                                                        .class),json,mock(ExpenseResourceAdjustmentSources.class),mock(ExpensePartialAdjustmentGuard.class)).due(Instant.now());
                assertThat(scanned).hasSize(1);
                when(runs.due(any(Instant.class))).thenReturn(scanned);
                doAnswer(capture).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new ExpenseResourceAdjustmentWorker(runs, service, mock(AccountingPeriodPort.class), mock(JdbcBudgetConsumptionReversalRepository.class), mock(ExpenseResourceAdjustmentBudgetExecution.class), mock(BudgetConsumptionReversalPort.class), mock(JdbcExpenseResourceAdjustmentRepository.class), mock(ExpenseResourceAdjustmentExecution.class))::poll, ExpenseResourceAdjustmentWorker.class, "ADJUSTMENT_PREPARATION_WORKER_FAILURE");
            }
            case RESOURCE_BUDGET -> {
                var runs = mock(JdbcBudgetConsumptionReversalRepository.class); var service = mock(ExpenseResourceAdjustmentBudgetExecution.class);
                var scanned = new JdbcBudgetConsumptionReversalRepository(
                                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                                (jdbc).getDataSource(),
                                                io.agentflow.finance.mapper
                                                        .BudgetConsumptionReversalRepositoryMapper
                                                        .class),json).due(Instant.now());
                assertThat(scanned).hasSize(1);
                when(runs.due(any(Instant.class))).thenReturn(scanned);
                doAnswer(capture).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new ExpenseResourceAdjustmentWorker(mock(JdbcExpenseResourceAdjustmentPreparationRepository.class), mock(ExpenseResourceAdjustmentPreparationService.class), mock(AccountingPeriodPort.class), runs, service, mock(BudgetConsumptionReversalPort.class), mock(JdbcExpenseResourceAdjustmentRepository.class), mock(ExpenseResourceAdjustmentExecution.class))::poll, ExpenseResourceAdjustmentWorker.class, "ADJUSTMENT_BUDGET_WORKER_FAILURE");
            }
            case RESOURCE_COMPLETION -> {
                var runs = mock(JdbcExpenseResourceAdjustmentRepository.class); var service = mock(ExpenseResourceAdjustmentExecution.class);
                var scanned = new JdbcExpenseResourceAdjustmentRepository(
                                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                                (jdbc).getDataSource(),
                                                io.agentflow.expense.mapper
                                                        .ExpenseResourceAdjustmentRepositoryMapper
                                                        .class),json,mock(JdbcExpenseResourceAdjustmentPreparationRepository.class),mock(JdbcBudgetConsumptionReversalRepository.class),mock(ExpenseReportRepository.class),mock(ExpensePartialAdjustmentGuard.class)).ready();
                assertThat(scanned).hasSize(1);
                when(runs.ready()).thenReturn(scanned);
                doAnswer(capture).when(service).apply(any());
                yield new Harness(new ExpenseResourceAdjustmentWorker(mock(JdbcExpenseResourceAdjustmentPreparationRepository.class), mock(ExpenseResourceAdjustmentPreparationService.class), mock(AccountingPeriodPort.class), mock(JdbcBudgetConsumptionReversalRepository.class), mock(ExpenseResourceAdjustmentBudgetExecution.class), mock(BudgetConsumptionReversalPort.class), runs, service)::poll, ExpenseResourceAdjustmentWorker.class, "ADJUSTMENT_RESOURCE_FAILURE");
            }
            case SETTLEMENT -> {
                var runs = mock(JdbcExpenseSettlementRepository.class); var service = mock(ExpenseSettlementService.class);
                var scanned = new JdbcExpenseSettlementRepository(
                                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                                (jdbc).getDataSource(),
                                                io.agentflow.expense.mapper
                                                        .ExpenseSettlementRepositoryMapper.class),json).pending();
                assertThat(scanned).hasSize(1);
                when(runs.pending()).thenReturn(scanned);
                doAnswer(capture).when(service).consume(any());
                yield new Harness(new ExpenseSettlementWorker(runs, mock(ExpenseSettlementRegistration.class), service)::poll, ExpenseSettlementWorker.class, "SETTLEMENT_FAILURE");
            }
            case PAYMENT_RECOVERY -> {
                var runs = mock(JdbcExpenseSettlementRepository.class); var service = mock(ExpenseSettlementRegistration.class);
                var scanned = new JdbcExpenseSettlementRepository(
                                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                                (jdbc).getDataSource(),
                                                io.agentflow.expense.mapper
                                                        .ExpenseSettlementRepositoryMapper.class),json).recoveryCandidates(null);
                assertThat(scanned).hasSize(1);
                when(runs.recoveryCandidates(any())).thenReturn(scanned);
                doAnswer(capture).when(service).recover(any());
                yield new Harness(new ExpenseSettlementWorker(runs, service, mock(ExpenseSettlementService.class))::poll, ExpenseSettlementWorker.class, "SETTLEMENT_FAILURE");
            }
            case VOUCHER_RECOVERY -> {
                var runs = mock(JdbcExpenseSettlementRepository.class); var service = mock(ExpenseSettlementRegistration.class);
                var scanned = new JdbcExpenseSettlementRepository(
                                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                                (jdbc).getDataSource(),
                                                io.agentflow.expense.mapper
                                                        .ExpenseSettlementRepositoryMapper.class),json).recoveryCandidates(null);
                assertThat(scanned).hasSize(1);
                when(runs.recoveryCandidates(any())).thenReturn(scanned);
                doAnswer(capture).when(service).recover(any());
                yield new Harness(new ExpenseSettlementWorker(runs, service, mock(ExpenseSettlementService.class))::poll, ExpenseSettlementWorker.class, "SETTLEMENT_FAILURE");
            }
            case ZERO_RECOVERY -> {
                var runs = mock(JdbcExpenseSettlementRepository.class); var service = mock(ExpenseSettlementRegistration.class);
                var scanned = new JdbcExpenseSettlementRepository(
                                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                                (jdbc).getDataSource(),
                                                io.agentflow.expense.mapper
                                                        .ExpenseSettlementRepositoryMapper.class),json).recoveryCandidates(null);
                assertThat(scanned).hasSize(1);
                when(runs.recoveryCandidates(any())).thenReturn(scanned);
                doAnswer(capture).when(service).recover(any());
                yield new Harness(new ExpenseSettlementWorker(runs, service, mock(ExpenseSettlementService.class))::poll, ExpenseSettlementWorker.class, "SETTLEMENT_FAILURE");
            }
            case REVERSAL_PREPARATION -> {
                var runs = mock(JdbcVoucherReversalPreparationRepository.class); var service = mock(VoucherReversalPreparationService.class);
                var scanned = new JdbcVoucherReversalPreparationRepository(
                                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                                (jdbc).getDataSource(),
                                                io.agentflow.finance.mapper
                                                        .VoucherReversalPreparationRepositoryMapper
                                                        .class),json,mock(ExpensePartialAdjustmentGuard.class)).due(Instant.now());
                assertThat(scanned).hasSize(1);
                when(runs.due(any(Instant.class))).thenReturn(scanned);
                doAnswer(capture).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new VoucherReversalExecutionWorker(runs, service, mock(AccountingVoucherPort.class), mock(AccountingPeriodPort.class), mock(JdbcVoucherReversalOperationRepository.class), mock(VoucherReversalExecutionService.class), mock(AccountingReversalPort.class))::poll, VoucherReversalExecutionWorker.class, "REVERSAL_PREPARATION_WORKER_FAILURE");
            }
            case REVERSAL -> {
                var runs = mock(JdbcVoucherReversalOperationRepository.class); var service = mock(VoucherReversalExecutionService.class);
                var scanned = new JdbcVoucherReversalOperationRepository(
                                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                                (jdbc).getDataSource(),
                                                io.agentflow.finance.mapper
                                                        .VoucherReversalOperationRepositoryMapper
                                                        .class),json,mock(JdbcVoucherReversalPreparationRepository.class),mock(JdbcVoucherOperationRepository.class),mock(ExpensePartialAdjustmentGuard.class)).due(Instant.now());
                assertThat(scanned).hasSize(1);
                when(runs.due(any(Instant.class))).thenReturn(scanned);
                doAnswer(capture).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new VoucherReversalExecutionWorker(mock(JdbcVoucherReversalPreparationRepository.class), mock(VoucherReversalPreparationService.class), mock(AccountingVoucherPort.class), mock(AccountingPeriodPort.class), runs, service, mock(AccountingReversalPort.class))::poll, VoucherReversalExecutionWorker.class, "REVERSAL_EXECUTION_WORKER_FAILURE");
            }
        };
    }
    /**
     * @author owlzhangfq@gmail.com
     */
    private record Harness(Runnable poll,Class<?> loggerType,String errorCode) { }
    /**
     * @author owlzhangfq@gmail.com
     */
    private enum Kind { ARCHIVE, BUDGET_REVIEW, PARTIAL_PREPARATION, PARTIAL_BUDGET, PARTIAL_ACCRUAL, PARTIAL_COMPLETION, RESOURCE_PREPARATION, RESOURCE_BUDGET, RESOURCE_COMPLETION, SETTLEMENT, PAYMENT_RECOVERY, VOUCHER_RECOVERY, ZERO_RECOVERY, REVERSAL_PREPARATION, REVERSAL }
}
