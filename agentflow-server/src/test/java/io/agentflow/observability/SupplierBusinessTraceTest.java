package io.agentflow.observability;

import io.agentflow.procurement.*;
import io.agentflow.finance.*;
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
 * 供应商原授权、原轮次及两个本地补齐入口共用真实候选 SQL；不替换核销守卫。
 * @author owlzhangfq@gmail.com
 */
class SupplierBusinessTraceTest {
    private static final String TENANT="tenant-a", BUSINESS="SUPPLIER-ORIGINAL", INSTANCE="original-instance";
    private final UUID application=UUID.randomUUID(), id=UUID.randomUUID(), payment=UUID.randomUUID(), reservation=UUID.randomUUID();
    private final String trace=UUID.randomUUID().toString();
    private final JsonUtil json=new JsonUtil(new ObjectMapper());
    private JdbcTemplate jdbc;

    @BeforeEach void schema() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:supplier-business-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("CREATE TABLE approval_application(tenant_id VARCHAR(64),id VARCHAR(36),business_no VARCHAR(128),round_no INT)");
        jdbc.execute("CREATE TABLE approval_submission_round(tenant_id VARCHAR(64),application_id VARCHAR(36),round_no INT,process_instance_id VARCHAR(128))");
        jdbc.execute("CREATE TABLE supplier_payment_authorization(tenant_id VARCHAR(64),id VARCHAR(36),application_id VARCHAR(36),round_no INT,reservation_id VARCHAR(36))");
        jdbc.execute("CREATE TABLE procurement_payable_reservation(tenant_id VARCHAR(64),id VARCHAR(36),version BIGINT,legal_entity_id VARCHAR(36),supplier_reference VARCHAR(128),payable_reference VARCHAR(128),settled_at TIMESTAMP,adjusted_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE supplier_payment_returns(tenant_id VARCHAR(64),payment_id VARCHAR(36),legal_entity_id VARCHAR(36),supplier_reference VARCHAR(128),payable_reference VARCHAR(128),review_required BOOLEAN,accounting_id VARCHAR(36),accounting_version BIGINT)");
        jdbc.execute("CREATE TABLE supplier_adjustment_completion(tenant_id VARCHAR(64),operation_id VARCHAR(36),operation_version BIGINT,bank_status VARCHAR(32))");
        for(var table:Arrays.stream(Kind.values()).map(kind->kind.table).distinct().toList()) {
            jdbc.execute("CREATE TABLE "+table+"(tenant_id VARCHAR(64),id VARCHAR(36),trace_id VARCHAR(36),application_id VARCHAR(36),round_no INT,authorization_id VARCHAR(36),payment_id VARCHAR(36),reservation_id VARCHAR(36),status VARCHAR(32),created_at TIMESTAMP,updated_at TIMESTAMP,requested_at TIMESTAMP,next_attempt_at TIMESTAMP,lease_until TIMESTAMP,retired_version BIGINT,completed_version BIGINT,active_payment_id VARCHAR(36))");
        }
    }
    @AfterEach void cleanup() { MDC.clear();jdbc.execute("DROP ALL OBJECTS"); }

    @ParameterizedTest @EnumSource(Kind.class)
    void queueAndLocalCompletionKeepTheOriginalApprovalRound(Kind kind) { verify(kind,TENANT,TENANT,true); }

    @ParameterizedTest @EnumSource(Kind.class)
    void missingRoundDoesNotBorrowTheCurrentInstance(Kind kind) { verify(kind,TENANT,TENANT,false); }

    @ParameterizedTest @EnumSource(Kind.class)
    void anotherTenantApplicationCannotSupplyContext(Kind kind) { verify(kind,"tenant-b",TENANT,true); }

    @ParameterizedTest @EnumSource(value=Kind.class,mode=EnumSource.Mode.EXCLUDE,names="REVIEW")
    void anotherTenantAuthorizationCannotSupplyContext(Kind kind) { verify(kind,TENANT,"tenant-b",true); }

    private void verify(Kind kind,String appTenant,String authorizationTenant,boolean originalRound) {
        jdbc.update("INSERT INTO approval_application VALUES(?,?,?,2)",appTenant,application.toString(),BUSINESS);
        jdbc.update("INSERT INTO approval_submission_round VALUES(?,?,2,'current-instance')",appTenant,application.toString());
        if(originalRound) jdbc.update("INSERT INTO approval_submission_round VALUES(?,?,1,?)",appTenant,application.toString(),INSTANCE);
        UUID authorization=kind==Kind.PAYMENT||kind==Kind.HOLD?id:payment;
        jdbc.update("INSERT INTO supplier_payment_authorization VALUES(?,?,?,1,?)",authorizationTenant,authorization.toString(),application.toString(),reservation.toString());
        jdbc.update("INSERT INTO "+kind.table+"(tenant_id,id,trace_id,application_id,round_no,authorization_id,payment_id,reservation_id,status,created_at,updated_at,requested_at,next_attempt_at) VALUES(?,?,?,?,1,?,?,?,?,?,?,?,?)",
                TENANT,id.toString(),trace,application.toString(),authorization.toString(),authorization.toString(),reservation.toString(),kind==Kind.ADJUSTMENT_COMPLETION?"ADJUSTED":kind==Kind.SETTLEMENT_COMPLETION?"SETTLED":"QUEUED",Timestamp.from(Instant.EPOCH),Timestamp.from(Instant.EPOCH),Timestamp.from(Instant.EPOCH),Timestamp.from(Instant.EPOCH));
        if(kind==Kind.SETTLEMENT_COMPLETION) {
            jdbc.update("INSERT INTO procurement_payable_reservation(tenant_id,id,version) VALUES(?,?,1)",TENANT,reservation.toString());
            jdbc.update("INSERT INTO supplier_payment_operation(tenant_id,id,status) VALUES(?,?,'SUCCEEDED')",TENANT,authorization.toString());
        }
        var observed=new ArrayList<Map<String,String>>();Answer<Object> capture=invocation->{observed.add(MDC.getCopyOfContextMap());throw new IllegalStateException("private-supplier-input");};
        var harness=harness(kind,capture);var logger=(Logger)LoggerFactory.getLogger(harness.type());
        var events=new ListAppender<ILoggingEvent>() { @Override protected void append(ILoggingEvent event) { event.prepareForDeferredProcessing();super.append(event); } };
        events.start();logger.addAppender(events);
        try(var outer=new DiagnosticContext(UUID.randomUUID().toString(),"foreign","foreign-business","current-instance","foreign-task").open()) {
            var previous=MDC.getCopyOfContextMap();harness.poll().run();harness.poll().run();assertThat(MDC.getCopyOfContextMap()).isEqualTo(previous);
            boolean bound=TENANT.equals(appTenant)&&TENANT.equals(authorizationTenant);
            assertThat(observed).hasSize(2).allSatisfy(context->{
                assertThat(context).containsEntry("tenantId",TENANT).containsEntry("traceId",trace).doesNotContainKey("taskId");
                if(bound) assertThat(context).containsEntry("businessNo",BUSINESS);else assertThat(context).doesNotContainKey("businessNo");
                if(bound&&originalRound) assertThat(context).containsEntry("processInstanceId",INSTANCE);else assertThat(context).doesNotContainKey("processInstanceId");
            });
            assertThat(events.list.stream().filter(event->event.getLevel()==ch.qos.logback.classic.Level.ERROR).toList()).hasSize(2).allSatisfy(event->{
                assertThat(event.getMDCPropertyMap()).isEqualTo(observed.get(0));assertThat(event.getFormattedMessage()).doesNotContain("private-supplier-input");assertThat(event.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(events);events.stop(); }
    }

    private Harness harness(Kind kind,Answer<Object> capture) {
        return switch(kind) {
            case ADJUSTMENT_PREPARATION -> {
                var runs = spy(new JdbcSupplierAdjustmentPreparationRepository(jdbc,json,mock(JdbcSupplierAdjustmentSources.class))); var service = mock(SupplierAdjustmentPreparationService.class);
                doAnswer(capture).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new SupplierAdjustmentPreparationWorker(runs, service, mock(SupplierAdjustmentEvidenceReader.class))::poll, SupplierAdjustmentPreparationWorker.class);
            }
            case ADJUSTMENT -> {
                var runs = spy(new JdbcSupplierPayableAdjustmentRepository(jdbc,json,mock(JdbcSupplierAdjustmentPreparationRepository.class),mock(JdbcSupplierAdjustmentSources.class))); var service = mock(SupplierAdjustmentService.class);
                doAnswer(capture).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new SupplierAdjustmentWorker(runs, service, mock(SupplierAdjustmentEvidenceReader.class), mock(SupplierPayableAdjustmentPort.class), mock(SupplierPaymentReturnPort.class), mock(SupplierAdjustmentCompletionService.class))::poll, SupplierAdjustmentWorker.class);
            }
            case HOLD -> {
                var runs = spy(new JdbcSupplierPayableHoldRepository(jdbc,json,mock(JdbcSupplierPaymentAuthorizationRepository.class))); var service = mock(SupplierPayableHoldService.class);
                doAnswer(capture).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new SupplierPayableHoldWorker(runs, service, mock(SupplierPayableHoldPort.class))::poll, SupplierPayableHoldWorker.class);
            }
            case REVIEW -> {
                var runs = spy(new JdbcSupplierPayableReviewRepository(jdbc,json,mock(ApprovedSupplierPaymentSources.class),mock(JdbcSupplierPaymentAuthorizationRepository.class))); var service = mock(SupplierPayableReviewService.class);
                doAnswer(capture).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new SupplierPayableReviewWorker(runs, service, mock(ProcurementPayablePort.class))::poll, SupplierPayableReviewWorker.class);
            }
            case REQUEST -> {
                var runs = spy(new JdbcSupplierPaymentExecutionRepository(jdbc,json,mock(JdbcSupplierPayableHoldRepository.class))); var service = mock(SupplierPaymentExecutionService.class);
                doAnswer(capture).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new SupplierPaymentExecutionWorker(runs, service, mock(SupplierPaymentEvidenceReader.class))::poll, SupplierPaymentExecutionWorker.class);
            }
            case RETURN -> {
                var runs = spy(new JdbcSupplierPaymentReturnCheckRepository(jdbc,json)); var service = mock(SupplierPaymentReturnService.class);
                doAnswer(capture).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new SupplierPaymentReturnWorker(runs, service, mock(SupplierPaymentReturnPort.class))::poll, SupplierPaymentReturnWorker.class);
            }
            case PAYMENT -> {
                var runs = spy(new JdbcSupplierPaymentOperationRepository(jdbc,json,mock(JdbcSupplierPaymentExecutionRepository.class),mock(JdbcSupplierPayableHoldRepository.class),mock(JdbcSupplierPaymentAuthorizationRepository.class))); var service = mock(SupplierPaymentService.class);
                doAnswer(capture).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new SupplierPaymentWorker(runs, service, mock(SupplierPaymentEvidenceReader.class), mock(SupplierPaymentPort.class))::poll, SupplierPaymentWorker.class);
            }
            case SETTLEMENT_PREPARATION -> {
                var runs = spy(new JdbcSupplierSettlementPreparationRepository(jdbc,json,mock(JdbcSupplierPaymentOperationRepository.class))); var service = mock(SupplierSettlementPreparationService.class);
                doAnswer(capture).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new SupplierSettlementPreparationWorker(runs, service, mock(SupplierSettlementEvidenceReader.class))::poll, SupplierSettlementPreparationWorker.class);
            }
            case SETTLEMENT -> {
                var runs = spy(new JdbcSupplierPayableSettlementRepository(jdbc,json,mock(JdbcSupplierSettlementPreparationRepository.class),mock(JdbcSupplierPaymentOperationRepository.class),mock(JdbcProcurementPayableReservationRepository.class))); var service = mock(SupplierSettlementService.class);
                doAnswer(capture).when(service).claim(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new SupplierSettlementWorker(runs, service, mock(SupplierSettlementEvidenceReader.class), mock(SupplierPayableSettlementPort.class))::poll, SupplierSettlementWorker.class);
            }
            case ADJUSTMENT_COMPLETION -> {
                var runs = spy(new JdbcSupplierPayableAdjustmentRepository(jdbc,json,mock(JdbcSupplierAdjustmentPreparationRepository.class),mock(JdbcSupplierAdjustmentSources.class))); var service = mock(SupplierAdjustmentService.class);
                doAnswer(capture).when(runs).find(anyString(), any(UUID.class));
                yield new Harness(new SupplierAdjustmentWorker(runs, service, mock(SupplierAdjustmentEvidenceReader.class), mock(SupplierPayableAdjustmentPort.class), mock(SupplierPaymentReturnPort.class), mock(SupplierAdjustmentCompletionService.class))::poll, SupplierAdjustmentWorker.class);
            }
            case SETTLEMENT_COMPLETION -> {
                var runs = spy(new JdbcSupplierPayableSettlementRepository(jdbc,json,mock(JdbcSupplierSettlementPreparationRepository.class),mock(JdbcSupplierPaymentOperationRepository.class),mock(JdbcProcurementPayableReservationRepository.class))); var service = mock(SupplierSettlementService.class);
                doAnswer(capture).when(service).completeLocal(anyString(), any(UUID.class), any(Instant.class));
                yield new Harness(new SupplierSettlementWorker(runs, service, mock(SupplierSettlementEvidenceReader.class), mock(SupplierPayableSettlementPort.class))::poll, SupplierSettlementWorker.class);
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
        ADJUSTMENT_PREPARATION("supplier_adjustment_preparation"),
        ADJUSTMENT("supplier_payable_adjustment_operation"),
        HOLD("supplier_payable_hold_operation"),
        REVIEW("supplier_payable_review"),
        REQUEST("supplier_payment_execution_request"),
        RETURN("supplier_payment_return_check"),
        PAYMENT("supplier_payment_operation"),
        SETTLEMENT_PREPARATION("supplier_settlement_preparation"),
        SETTLEMENT("supplier_payable_settlement_operation"),
        ADJUSTMENT_COMPLETION("supplier_payable_adjustment_operation"),
        SETTLEMENT_COMPLETION("supplier_payable_settlement_operation");
        private final String table;
        Kind(String table) { this.table=table; }
    }
}
