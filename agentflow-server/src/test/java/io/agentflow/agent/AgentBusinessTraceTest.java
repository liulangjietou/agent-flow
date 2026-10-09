package io.agentflow.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentflow.common.JsonUtil;
import io.agentflow.observability.DiagnosticContext;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 使用真实候选 SQL 验证来源投影与工作器作用域；完整表约束由各 Agent 的持久化和业务集成测试覆盖。
 *
 * @author owlzhangfq@gmail.com
 */
class AgentBusinessTraceTest {
    private static final String TENANT = "tenant-a", BUSINESS = "ORIGINAL-BUSINESS", OLD_INSTANCE = "original-instance", OLD_TASK = "original-task";
    private final UUID applicationId = UUID.randomUUID(), runId = UUID.randomUUID();
    private final String traceId = UUID.randomUUID().toString();
    private final JsonUtil json = new JsonUtil(new ObjectMapper());
    private JdbcTemplate jdbc;

    @BeforeEach void schema() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:agent-business-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute(
                "CREATE TABLE approval_application(tenant_id VARCHAR(64),id VARCHAR(36),business_no"
                        + " VARCHAR(128),round_no INT)");
        jdbc.execute(
                "CREATE TABLE approval_submission_round(tenant_id VARCHAR(64),application_id"
                        + " VARCHAR(36),round_no INT,process_instance_id VARCHAR(128))");
        for (String table : List.of("agent_assist_run", "agent_draft_assist_run", "agent_expense_draft_run", "agent_precheck_explanation_run", "agent_expense_risk_run")) {
            jdbc.execute("CREATE TABLE " + table + "(tenant_id VARCHAR(64),id VARCHAR(36),application_id"
                            + " VARCHAR(36),round_no INT,task_id VARCHAR(128),trace_id"
                            + " VARCHAR(36),status VARCHAR(32),created_at TIMESTAMP,lease_until"
                            + " TIMESTAMP)");
        }
        jdbc.execute(
                "CREATE TABLE agent_assist_job(tenant_id VARCHAR(64),run_id VARCHAR(36),task_id"
                        + " VARCHAR(128),trace_id VARCHAR(36),lease_until TIMESTAMP)");
    }

    @AfterEach void cleanup() { MDC.clear(); jdbc.execute("DROP ALL OBJECTS"); }

    @ParameterizedTest @EnumSource(Kind.class)
    void claimAndFailureUseOriginalQueueFactsAfterTheApplicationAdvances(Kind kind) {
        source(TENANT, true); enqueue(kind);
        var seen = new ArrayList<Map<String, String>>();
        var harness = worker(kind, invocation -> { seen.add(MDC.getCopyOfContextMap()); throw new IllegalStateException("private-material-sentinel"); });
        var logger = (Logger) LoggerFactory.getLogger(harness.loggerType());
        var events = new ListAppender<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) { event.prepareForDeferredProcessing(); super.append(event); }
        };
        events.start(); logger.addAppender(events);
        try (var outer = new DiagnosticContext(UUID.randomUUID().toString(), "caller", "foreign-business", "new-instance", "new-task").open()) {
            var previous = MDC.getCopyOfContextMap();
            harness.poll().run(); harness.poll().run();
            assertThat(MDC.getCopyOfContextMap()).isEqualTo(previous);
            assertThat(seen).hasSize(2).allSatisfy(context -> expected(context, kind, true));
            assertThat(events.list).hasSize(2).allSatisfy(event -> {
                expected(event.getMDCPropertyMap(), kind, true);
                assertThat(event.getFormattedMessage()).doesNotContain("private-material-sentinel");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(events); events.stop(); }
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @ParameterizedTest @EnumSource(value = Kind.class, names = {"SUMMARY", "RISK"})
    void missingOriginalRoundNeverFallsBackToTheCurrentRound(Kind kind) {
        source(TENANT, false); enqueue(kind);
        var seen = new ArrayList<Map<String, String>>();
        var harness = worker(kind, invocation -> { seen.add(MDC.getCopyOfContextMap()); return null; });
        harness.poll().run();
        assertThat(seen).singleElement().satisfies(context -> expected(context, kind, false));
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void anotherTenantsSameApplicationIdentifierCannotSupplyBusinessMetadata(Kind kind) {
        source("tenant-b", true); enqueue(kind);
        var seen = new ArrayList<Map<String, String>>();
        var harness = worker(kind, invocation -> { seen.add(MDC.getCopyOfContextMap()); return null; });
        harness.poll().run();
        assertThat(seen).singleElement().satisfies(context -> assertThat(context)
                .containsEntry("tenantId", TENANT).containsEntry("traceId", traceId)
                .doesNotContainKeys("businessNo", "processInstanceId", "taskId"));
    }

    private void expected(Map<String, String> context, Kind kind, boolean originalRoundExists) {
        assertThat(context).containsEntry("tenantId", TENANT).containsEntry("traceId", traceId).containsEntry("businessNo", BUSINESS);
        if (kind == Kind.SUMMARY || kind == Kind.RISK) {
            assertThat(context).containsEntry("taskId", OLD_TASK);
            if (originalRoundExists) assertThat(context).containsEntry("processInstanceId", OLD_INSTANCE);
            else assertThat(context).doesNotContainKey("processInstanceId");
        } else assertThat(context).doesNotContainKeys("processInstanceId", "taskId");
    }

    private void source(String tenant, boolean originalRoundExists) {
        jdbc.update("INSERT INTO approval_application VALUES(?,?,?,2)", tenant, applicationId.toString(), BUSINESS);
        jdbc.update("INSERT INTO approval_submission_round VALUES(?,?,2,'new-instance')", tenant, applicationId.toString());
        if (originalRoundExists) jdbc.update("INSERT INTO approval_submission_round VALUES(?,?,1,?)", tenant, applicationId.toString(), OLD_INSTANCE);
    }

    private void enqueue(Kind kind) {
        String table = switch (kind) {
            case SUMMARY -> "agent_assist_run"; case DRAFT -> "agent_draft_assist_run"; case EXPENSE -> "agent_expense_draft_run";
            case PRECHECK -> "agent_precheck_explanation_run"; case RISK -> "agent_expense_risk_run";
        };
        jdbc.update("INSERT INTO " + table + "(tenant_id,id,application_id,round_no,task_id,trace_id,status,created_at)"
                        + " VALUES(?,?,?,1,?,?,'QUEUED',?)",
                TENANT, runId.toString(), applicationId.toString(), OLD_TASK, traceId, Timestamp.from(Instant.now()));
        if (kind == Kind.SUMMARY) jdbc.update(
                    "INSERT INTO agent_assist_job(tenant_id,run_id,task_id,trace_id)"
                            + " VALUES(?,?,?,?)", TENANT, runId.toString(), OLD_TASK, traceId);
    }

    private Harness worker(Kind kind, Answer<Object> claim) {
        return switch (kind) {
            case SUMMARY -> {
                var service = mock(AssistExecutionService.class); doAnswer(claim).when(service).claim(anyString(), any(), any());
                yield new Harness(new AssistWorker(new JdbcAssistJobRepository(
                                                io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                                        (jdbc).getDataSource(),
                                                        io.agentflow.agent.mapper
                                                                .AssistJobRepositoryMapper.class), json), service, mock(AssistModelPort.class))::poll, AssistWorker.class);
            }
            case DRAFT -> {
                var service = mock(DraftAssistService.class); doAnswer(claim).when(service).claim(anyString(), any(), any());
                yield new Harness(new DraftAssistWorker(new JdbcDraftAssistRunRepository(
                                                io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                                        (jdbc).getDataSource(),
                                                        io.agentflow.agent.mapper
                                                                .DraftAssistRunRepositoryMapper
                                                                .class), json), service, mock(DraftAssistModelPort.class))::poll, DraftAssistWorker.class);
            }
            case EXPENSE -> {
                var service = mock(ExpenseDraftAssistService.class); doAnswer(claim).when(service).claim(anyString(), any(), any());
                yield new Harness(new ExpenseDraftAssistWorker(new JdbcExpenseDraftAssistRepository(
                                                io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                                        (jdbc).getDataSource(),
                                                        io.agentflow.agent.mapper
                                                                .ExpenseDraftAssistRepositoryMapper
                                                                .class), json), service,
                        mock(ExpenseDraftAssistPreparation.class), mock(ExpenseDraftModelPort.class))::poll, ExpenseDraftAssistWorker.class);
            }
            case PRECHECK -> {
                var service = mock(PrecheckExplanationService.class); doAnswer(claim).when(service).claim(anyString(), any(), any());
                yield new Harness(new PrecheckExplanationWorker(new JdbcPrecheckExplanationRepository(
                                                io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                                        (jdbc).getDataSource(),
                                                        io.agentflow.agent.mapper
                                                                .PrecheckExplanationRepositoryMapper
                                                                .class), json), service, mock(PrecheckExplanationModelPort.class))::poll, PrecheckExplanationWorker.class);
            }
            case RISK -> {
                var service = mock(ExpenseRiskService.class); doAnswer(claim).when(service).claim(anyString(), any(), any());
                yield new Harness(new ExpenseRiskWorker(new JdbcExpenseRiskRepository(
                                                io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                                        (jdbc).getDataSource(),
                                                        io.agentflow.agent.mapper
                                                                .ExpenseRiskRepositoryMapper.class), json), service, mock(ExpenseRiskModelPort.class))::poll, ExpenseRiskWorker.class);
            }
        };
    }

    /**
     * @author owlzhangfq@gmail.com
     */
    private record Harness(Runnable poll, Class<?> loggerType) { }
    /**
     * @author owlzhangfq@gmail.com
     */
    private enum Kind { SUMMARY, DRAFT, EXPENSE, PRECHECK, RISK }
}
