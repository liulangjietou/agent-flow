package io.agentflow.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.approval.process.*;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
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

/**
 * 原生任务与财务约定各自提供真实身份，不借当前轮次或扫描线程的业务上下文。
 * @author owlzhangfq@gmail.com
 */
class DirectSchedulerBusinessTraceTest {
    private static final String TENANT="tenant-a", BUSINESS="DIRECT-ORIGINAL", INSTANCE="original-instance";
    private final UUID application=UUID.randomUUID(), id=UUID.randomUUID(), definition=UUID.randomUUID();
    private final String trace=UUID.randomUUID().toString();
    private final JsonUtil json=new JsonUtil(new ObjectMapper());
    private JdbcTemplate jdbc;

    @BeforeEach void schema() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:direct-business-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("CREATE TABLE approval_application(tenant_id VARCHAR(64),id VARCHAR(36),business_no VARCHAR(128),round_no INT,status VARCHAR(32),process_key VARCHAR(64),definition_version INT)");
        jdbc.execute("CREATE TABLE approval_submission_round(tenant_id VARCHAR(64),application_id VARCHAR(36),round_no INT,process_instance_id VARCHAR(128),status VARCHAR(32))");
        jdbc.execute("CREATE TABLE ACT_RU_TASK(ID_ VARCHAR(64),PROC_INST_ID_ VARCHAR(128),TENANT_ID_ VARCHAR(64),DUE_DATE_ TIMESTAMP,SUSPENSION_STATE_ INT,ASSIGNEE_ VARCHAR(64))");
        jdbc.execute("CREATE TABLE ACT_RU_TIMER_JOB(ID_ VARCHAR(64),PROCESS_INSTANCE_ID_ VARCHAR(128),TENANT_ID_ VARCHAR(64),DUEDATE_ TIMESTAMP)");
        jdbc.execute("CREATE TABLE ACT_RU_VARIABLE(TASK_ID_ VARCHAR(64),NAME_ VARCHAR(64),TYPE_ VARCHAR(32),LONG_ BIGINT)");
        jdbc.execute("CREATE TABLE ACT_RU_IDENTITYLINK(TASK_ID_ VARCHAR(64),TYPE_ VARCHAR(32),USER_ID_ VARCHAR(64))");
        jdbc.execute("CREATE TABLE workflow_execution_origin(tenant_id VARCHAR(64),object_kind VARCHAR(16),object_id VARCHAR(64),trace_id VARCHAR(36))");
        jdbc.execute("CREATE TABLE organization_approval_proxy(tenant_id VARCHAR(64),id VARCHAR(36),principal_id VARCHAR(36),substitute_id VARCHAR(36),definition_id VARCHAR(36),trace_id VARCHAR(36),created_at TIMESTAMP,starts_at TIMESTAMP,ends_at TIMESTAMP,revoked_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE organization_person(tenant_id VARCHAR(64),id VARCHAR(36),subject VARCHAR(64),active BOOLEAN,approval_eligible BOOLEAN)");
        jdbc.execute("CREATE TABLE approval_definition(tenant_id VARCHAR(64),id VARCHAR(36),process_key VARCHAR(64),version INT,status VARCHAR(32))");
        jdbc.execute("CREATE TABLE approval_proxy_notification(tenant_id VARCHAR(64),proxy_id VARCHAR(36),task_id VARCHAR(64),event_version BIGINT)");
        jdbc.execute("CREATE TABLE employee_advance_order(tenant_id VARCHAR(64),advance_id VARCHAR(36),due_on DATE,trace_id VARCHAR(36),employee_id VARCHAR(64),legal_entity_id VARCHAR(36))");
        jdbc.execute("CREATE TABLE advance_request(tenant_id VARCHAR(64),id VARCHAR(36),application_id VARCHAR(36))");
        jdbc.execute("CREATE TABLE advance_overdue_reminder(tenant_id VARCHAR(64),advance_id VARCHAR(36))");
        jdbc.execute("CREATE TABLE payment_authorization(tenant_id VARCHAR(64),id VARCHAR(36),business_type VARCHAR(32),business_id VARCHAR(36),active_business_id VARCHAR(36),purpose VARCHAR(32),status VARCHAR(32),application_id VARCHAR(36),round_no INT)");
        jdbc.execute("CREATE TABLE expense_budget_retention(tenant_id VARCHAR(64),report_id VARCHAR(36),application_id VARCHAR(36),round_no INT,trace_id VARCHAR(36),status VARCHAR(32),expires_at TIMESTAMP)");
    }
    @AfterEach void cleanup() { MDC.clear();jdbc.execute("DROP ALL OBJECTS"); }

    @ParameterizedTest @EnumSource(Kind.class)
    void originalBusinessAndNativeIdentitySurviveRetries(Kind kind) { verify(kind,TENANT,TENANT,true); }
    @ParameterizedTest @EnumSource(value=Kind.class,mode=EnumSource.Mode.EXCLUDE,names="PROXY")
    void missingRoundDoesNotBorrowTheCurrentInstance(Kind kind) { verify(kind,TENANT,TENANT,false); }
    @ParameterizedTest @EnumSource(value=Kind.class,mode=EnumSource.Mode.EXCLUDE,names="PROXY")
    void anotherTenantApplicationCannotSupplyBusinessIdentity(Kind kind) { verify(kind,"foreign",TENANT,true); }
    @ParameterizedTest @EnumSource(value=Kind.class,mode=EnumSource.Mode.EXCLUDE,names="PROXY")
    void anotherTenantRoundCannotSupplyBusinessIdentity(Kind kind) { verify(kind,TENANT,"foreign",true); }

    @Test void proxyCannotBorrowAnotherTenantApplication() {
        seed(Kind.PROXY,"foreign",TENANT,true);assertThat(proxies().candidates(Instant.now(),null)).isEmpty();
    }
    @Test void proxyCannotBorrowAnotherTenantNativeTask() {
        seed(Kind.PROXY,TENANT,TENANT,true);jdbc.update("UPDATE ACT_RU_TASK SET TENANT_ID_='foreign'");
        assertThat(proxies().candidates(Instant.now(),null)).isEmpty();
    }
    @Test void proxyCannotBorrowANewerRound() {
        seed(Kind.PROXY,TENANT,TENANT,false);assertThat(proxies().candidates(Instant.now(),null)).isEmpty();
    }
    @Test void overdueDoesNotBorrowAnotherTenantAuthorization() {
        seed(Kind.OVERDUE,TENANT,TENANT,true);jdbc.update("UPDATE payment_authorization SET tenant_id='foreign'");
        verifyScope(Kind.OVERDUE,true,false);
    }

    private void seed(Kind kind,String appTenant,String roundTenant,boolean originalRound) {
        var at=Timestamp.from(Instant.EPOCH);
        jdbc.update("INSERT INTO approval_application VALUES(?,?,?,?,'IN_APPROVAL','approval',1)",appTenant,application.toString(),BUSINESS,kind==Kind.PROXY?1:2);
        jdbc.update("INSERT INTO approval_submission_round VALUES(?,?,2,'current-instance','IN_APPROVAL')",appTenant,application.toString());
        if(originalRound) jdbc.update("INSERT INTO approval_submission_round VALUES(?,?,1,?,'IN_APPROVAL')",roundTenant,application.toString(),INSTANCE);
        if(kind==Kind.PROXY||kind==Kind.DEADLINE||kind==Kind.ESCALATION) {
            jdbc.update("INSERT INTO ACT_RU_TASK VALUES(?,?,?,?,1,'principal')",id.toString(),INSTANCE,TENANT,at);
            jdbc.update("INSERT INTO ACT_RU_VARIABLE VALUES(?,?,'date',0)",id.toString(),kind==Kind.ESCALATION?TaskEscalationBindings.DUE_AT:FlowableTaskDeadlineListener.CALENDAR_ID);
            jdbc.update("INSERT INTO workflow_execution_origin VALUES(?,'TASK',?,?)",TENANT,id.toString(),trace);
        }
        if(kind==Kind.PROXY) {
            jdbc.update("INSERT INTO organization_person VALUES(?,'principal-id','principal',TRUE,TRUE)",TENANT);
            jdbc.update("INSERT INTO organization_person VALUES(?,'substitute-id','substitute',TRUE,TRUE)",TENANT);
            jdbc.update("INSERT INTO approval_definition VALUES(?,?,'approval',1,'PUBLISHED')",TENANT,definition.toString());
            jdbc.update("INSERT INTO organization_approval_proxy(tenant_id,id,principal_id,substitute_id,definition_id,trace_id,created_at,starts_at,ends_at) VALUES(?,?,'principal-id','substitute-id',?,?,?,?,?)",TENANT,id.toString(),definition.toString(),trace,at,at,Timestamp.from(Instant.now().plusSeconds(3600)));
        }
        if(kind==Kind.TIMER) {
            jdbc.update("INSERT INTO ACT_RU_TIMER_JOB VALUES(?,?,?,?)",id.toString(),INSTANCE,TENANT,at);
            jdbc.update("INSERT INTO workflow_execution_origin VALUES(?,'TIMER',?,?)",TENANT,id.toString(),trace);
        }
        if(kind==Kind.OVERDUE) {
            jdbc.update("INSERT INTO employee_advance_order(tenant_id,advance_id,due_on,trace_id) VALUES(?,?,?,?)",TENANT,id.toString(),LocalDate.EPOCH,trace);
            jdbc.update("INSERT INTO advance_request VALUES(?,?,?)",TENANT,id.toString(),application.toString());
            jdbc.update("INSERT INTO payment_authorization VALUES(?,?,'ADVANCE_REQUEST',?,?,'EMPLOYEE_ADVANCE','EXECUTION_REGISTERED',?,1)",TENANT,UUID.randomUUID().toString(),id.toString(),id.toString(),application.toString());
        }
        if(kind==Kind.RETENTION) jdbc.update("INSERT INTO expense_budget_retention VALUES(?,?,?,1,?,'RETAINED',?)",TENANT,id.toString(),application.toString(),trace,at);
    }
    private void verify(Kind kind,String appTenant,String roundTenant,boolean originalRound) {
        seed(kind,appTenant,roundTenant,originalRound);
        boolean applicationKnown=TENANT.equals(appTenant), roundKnown=originalRound&&TENANT.equals(roundTenant);
        boolean nativeInstance=kind==Kind.DEADLINE||kind==Kind.ESCALATION||kind==Kind.TIMER;
        verifyScope(kind,applicationKnown&&(!nativeInstance||roundKnown),nativeInstance||(applicationKnown&&roundKnown));
    }
    private void verifyScope(Kind kind,boolean businessKnown,boolean instanceKnown) {
        var observed=new ArrayList<Map<String,String>>();Answer<Object> capture=call->{observed.add(MDC.getCopyOfContextMap());throw new IllegalStateException("private-scheduler-input");};
        var harness=harness(kind,capture);var logger=(Logger)LoggerFactory.getLogger(harness.logger());
        var events=new ListAppender<ILoggingEvent>() { @Override protected void append(ILoggingEvent event) { event.prepareForDeferredProcessing();super.append(event); } };
        events.start();logger.addAppender(events);
        try(var outer=new DiagnosticContext(UUID.randomUUID().toString(),"foreign","foreign-business","current-instance","foreign-task").open()) {
            var previous=MDC.getCopyOfContextMap();harness.poll().run();harness.poll().run();assertThat(MDC.getCopyOfContextMap()).isEqualTo(previous);
            assertThat(observed).hasSize(2).allSatisfy(context->{
                assertThat(context).containsEntry("traceId",trace).containsEntry("tenantId",TENANT);
                if(businessKnown) assertThat(context).containsEntry("businessNo",BUSINESS);else assertThat(context).doesNotContainKey("businessNo");
                if(instanceKnown) assertThat(context).containsEntry("processInstanceId",INSTANCE);else assertThat(context).doesNotContainKey("processInstanceId");
                if(kind==Kind.PROXY||kind==Kind.DEADLINE||kind==Kind.ESCALATION) assertThat(context).containsEntry("taskId",id.toString());else assertThat(context).doesNotContainKey("taskId");
            });
            assertThat(events.list.stream().filter(event->event.getLevel()==Level.ERROR).toList()).hasSize(2).allSatisfy(event->{
                assertThat(event.getMDCPropertyMap()).isEqualTo(observed.get(0));assertThat(event.getFormattedMessage()).doesNotContain("private-scheduler-input");assertThat(event.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(events);events.stop(); }
    }
    private FlowableApprovalProxyNotifications proxies() {
        return new FlowableApprovalProxyNotifications(jdbc,null,null,null,null,null,null,null);
    }
    private Harness harness(Kind kind,Answer<Object> capture) {
        return switch(kind) {
            case PROXY -> {
                var service=spy(proxies());doAnswer(capture).when(service).pending(any());
                yield new Harness(new ApprovalProxyNotificationScheduler(service)::poll,ApprovalProxyNotificationScheduler.class);
            }
            case DEADLINE -> {
                var service=spy(new FlowableTaskDeadlineReminders(jdbc,null,null,null,null));doAnswer(capture).when(service).remind(anyString(),any());
                yield new Harness(new TaskDeadlineReminderScheduler(service)::deliver,TaskDeadlineReminderScheduler.class);
            }
            case ESCALATION -> {
                var service=spy(new FlowableTaskEscalations(jdbc,null,null,null,null,null,json));doAnswer(capture).when(service).escalate(anyString(),any());
                yield new Harness(new TaskEscalationScheduler(service)::deliver,TaskEscalationScheduler.class);
            }
            case TIMER -> {
                var service=spy(new TimerWaitService(null,null,null,null,null,null,null,null,null,null,jdbc,null,false));
                doAnswer(capture).when(service).advance(anyString(),any());doNothing().when(service).failed(anyString(),any());
                yield new Harness(new TimerWaitScheduler(service)::dispatch,TimerWaitScheduler.class);
            }
            case OVERDUE -> {
                var service=mock(AdvanceOverdueReminders.class);doAnswer(capture).when(service).remind(any(),any());
                yield new Harness(new AdvanceOverdueScheduling(new JdbcAdvanceOverdueRepository(jdbc),service)::poll,AdvanceOverdueScheduling.class);
            }
            case RETENTION -> {
                var service=mock(ExpenseBudgetRetentionService.class);doAnswer(capture).when(service).process(any(),any());
                yield new Harness(new ExpenseBudgetRetentionScheduling(new JdbcExpenseBudgetRetentionRepository(jdbc,json),service)::poll,ExpenseBudgetRetentionScheduling.class);
            }
        };
    }
    /**
     * @author owlzhangfq@gmail.com
     */
    private record Harness(Runnable poll,Class<?> logger) { }
    /**
     * @author owlzhangfq@gmail.com
     */
    private enum Kind { PROXY, DEADLINE, ESCALATION, TIMER, OVERDUE, RETENTION }
}
