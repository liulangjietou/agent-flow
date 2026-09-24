package io.agentflow.approval.operations;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.approval.history.JdbcSubmissionHistoryGapQuery;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static io.agentflow.definition.DefinitionModels.*;
import static io.agentflow.support.MutationRequests.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 运营查询覆盖轮次口径、日期边界、租户权限、真实会签待办和返回上限。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${AGENTFLOW_OPERATIONS_TEST_URL:jdbc:h2:mem:approval-operations;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${AGENTFLOW_OPERATIONS_TEST_USER:sa}",
        "spring.datasource.password=${AGENTFLOW_OPERATIONS_TEST_PASSWORD:}",
        "spring.datasource.driver-class-name=${AGENTFLOW_OPERATIONS_TEST_DRIVER:org.h2.Driver}",
        "agentflow.auth.demo-enabled=true"})
@AutoConfigureMockMvc
class ApprovalOperationsIntegrationTest {
    @Autowired MockMvc mvc;
    @MockitoSpyBean AuthService auth;
    @Autowired JsonUtil json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApprovalOperationsReadPort reader;
    @Autowired DefinitionApplicationService definitions;
    @Autowired TaskService tasks;
    @Autowired RuntimeService runtime;

    @Test
    void countsRoundsInsteadOfCurrentApplicationStateAndDoesNotMutateFacts() throws Exception {
        String key = "metrics-" + UUID.randomUUID(), app = UUID.randomUUID().toString();
        seedApplication("demo", app, key, 2);
        seedRound("demo", app, 1, "RETURNED", "2020-01-01T23:59:59Z", "2020-01-02T00:05:00Z");
        seedRound("demo", app, 2, "APPROVED", "2020-01-02T08:00:00+08:00", "2020-01-02T09:00:00+08:00");
        seed("demo", key, "REJECTED", "2020-01-02T03:00:00Z", "2020-01-02T04:00:00Z");
        seed("demo", key, "WITHDRAWN", "2020-01-03T00:00:00Z", "2020-01-03T02:00:00Z");
        seed("demo", key, "IN_APPROVAL", "2020-01-03T23:59:59Z", null);
        seed("demo", key, "APPROVED", "2020-01-04T00:00:00Z", "2020-01-04T00:01:00Z");
        var before = jdbc.queryForList("SELECT * FROM approval_submission_round WHERE application_id=? ORDER BY round_no", app);
        JsonNode report = report(key);
        JsonNode metrics = report.path("metrics");
        assertThat(metrics.path("submittedRounds").asLong()).isEqualTo(5);
        assertThat(metrics.path("applications").asLong()).isEqualTo(4);
        assertThat(metrics.path("decidedRounds").asLong()).isEqualTo(3);
        assertThat(metrics.path("returnRatePercent").decimalValue()).isEqualByComparingTo("33.3");
        assertThat(metrics.path("averageApprovalSeconds").asLong()).isEqualTo(3600);
        assertThat(metrics.path("durationSamples").asLong()).isEqualTo(1);
        assertThat(report.path("daily").findValuesAsText("submittedRounds")).containsExactly("1", "2", "2");
        assertThat(report.path("processes")).hasSize(1);
        assertThat(report.toString()).doesNotContain("secret-value", "payload", "reason");
        assertThat(jdbc.queryForList("SELECT * FROM approval_submission_round WHERE application_id=? ORDER BY round_no", app)).isEqualTo(before);
    }

    @Test
    void rejectsNonAdminAndUntrustedFiltersAndKeepsTenantIsolation() throws Exception {
        String key = "tenant-" + UUID.randomUUID();
        seed("demo", key, "APPROVED", "2020-01-02T00:00:00Z", "2020-01-02T00:01:00Z");
        seed("other", key, "RETURNED", "2020-01-02T00:00:00Z", "2020-01-02T00:02:00Z");
        mvc.perform(get("/api/v1/operations/approvals")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/operations/approvals").header("Authorization", token("finance"))).andExpect(status().isForbidden());
        doReturn(new Actor("demo", "process-only", Set.of("PROCESS_ADMIN"))).when(auth).authenticate("process-only-token");
        mvc.perform(get("/api/v1/operations/approvals").header("Authorization", "Bearer process-only-token")).andExpect(status().isForbidden());
        assertThat(report(key).path("metrics").path("submittedRounds").asLong()).isEqualTo(1);
        assertThat(reader.read("other", query(key), Instant.now()).metrics().returned()).isEqualTo(1);
        for (String query : List.of("tenantId=other", "from=2020-02-30", "from=2020-01-04&to=2020-01-03",
                "from=2018-01-01&to=2020-01-01", "definitionVersion=1", "processKey=x&definitionVersion=0", "to=9999-12-31", "limit=100000")) {
            mvc.perform(get("/api/v1/operations/approvals?" + query).header("Authorization", token("admin")))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_OPERATIONS_QUERY"));
        }
        assertThat(report("missing-' OR '1'='1").path("metrics").path("submittedRounds").asLong()).isZero();
    }

    @Test
    void emptyWindowAndMissingLegacyRoundsAreNotInventedAsZeroPercentOrDuration() throws Exception {
        String key = "empty-" + UUID.randomUUID();
        seedApplication("demo", UUID.randomUUID().toString(), key, 3);
        JsonNode report = report(key);
        assertThat(report.path("metrics").path("submittedRounds").asLong()).isZero();
        assertThat(report.path("metrics").has("returnRatePercent")).isFalse();
        assertThat(report.path("metrics").has("averageApprovalSeconds")).isFalse();
        assertThat(report.path("daily").findValuesAsText("submittedRounds")).containsExactly("0", "0", "0");
        assertThat(report.path("unrecordedHistoricalRounds").asLong()).isEqualTo(3);
        seed("demo", key, "APPROVED", "2020-01-02T01:00:00Z", "2020-01-02T00:00:00Z");
        var metrics = report(key).path("metrics");
        assertThat(metrics.path("approved").asLong()).isEqualTo(1);
        assertThat(metrics.path("durationSamples").asLong()).isZero();
        assertThat(metrics.has("averageApprovalSeconds")).isFalse();
    }

    @Test
    void limitsProcessRowsWithoutTruncatingTotalsOrMixingVersions() {
        String key = "versions-" + UUID.randomUUID();
        for (int version = 1; version <= 52; version++) {
            String app = seed("demo", key, "APPROVED", "2020-01-02T00:00:00Z", "2020-01-02T00:01:00Z");
            jdbc.update("UPDATE approval_application SET definition_version=? WHERE id=?", version, app);
            jdbc.update("UPDATE approval_submission_round SET definition_version=? WHERE application_id=?", version, app);
        }
        var report = reader.read("demo", query(key), Instant.now());
        assertThat(report.metrics().submittedRounds()).isEqualTo(52);
        assertThat(report.processes()).hasSize(50);
        assertThat(report.moreProcesses()).isTrue();
        assertThat(report.processes().get(0).definitionVersion()).isEqualTo(1);
        var filtered = reader.read("demo", new ApprovalOperationsReadPort.Query(query(key).from(), query(key).to(), key, 52L), Instant.now());
        assertThat(filtered.metrics().submittedRounds()).isEqualTo(1);
        assertThat(filtered.processes().get(0).definitionVersion()).isEqualTo(52);
    }

    @Test
    void currentBacklogCountsCountersignMembersAndRejectsCorruptTenantAndRoundBindings() throws Exception {
        String key = "backlog-" + UUID.randomUUID();
        Graph graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("review", "财务会签", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE", "approvalMode", "ALL")),
                new Node("end", "结束", NodeType.END, Map.of())), List.of(new Edge("a", "start", "review", ""), new Edge("b", "review", "end", "")));
        var definition = definitions.create("demo", key, "运营会签验收", graph);
        definitions.publish(new Actor("demo", "admin", Set.of("ADMIN")), definition.id(), 0, "会签积压统计验收");
        String app = json.read(mvc.perform(post("/api/v1/applications").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("processKey", key, "definitionVersion", 1,
                        "businessNo", key, "title", "运营会签验收", "payload", Map.of("secret", "secret-value")))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), JsonNode.class).path("id").asText();
        mvc.perform(post("/api/v1/applications/" + app + "/submit").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}")).andExpect(status().isOk());
        var task = tasks.createTaskQuery().processVariableValueEquals("applicationId", app).list().get(0);
        var report = reader.read("demo", query(key), Instant.now());
        assertThat(report.metrics().submittedRounds()).isZero(); // 日期窗口不控制当前积压。
        assertThat(report.pendingTasks()).isEqualTo(2);
        assertThat(report.waitingNodes()).hasSize(1);
        assertThat(report.waitingNodes().get(0).tasks()).isEqualTo(2);
        assertThat(report.oldestTasks()).extracting(ApprovalOperationsReadPort.WaitingTask::assignee).containsExactlyInAnyOrder("admin", "finance");
        runtime.setVariable(task.getProcessInstanceId(), "roundNo", 9);
        assertThat(reader.read("demo", query(key), Instant.now()).pendingTasks()).isZero();
        runtime.setVariable(task.getProcessInstanceId(), "roundNo", 1);
        runtime.setVariable(task.getProcessInstanceId(), "tenantId", "other");
        assertThat(reader.read("demo", query(key), Instant.now()).pendingTasks()).isZero();
        assertThat(reader.read("other", query(key), Instant.now()).pendingTasks()).isZero();
    }

    @Test
    void readsCurrentTaskSetTwiceAndKeepsTotalBeyondBothDisplayLimits() throws Exception {
        String tenant = "backlog-limit-" + UUID.randomUUID();
        Instant created = Instant.parse("2020-01-01T00:00:00Z");
        for (int group = 1; group <= 22; group++) {
            // 最后一个不可见分组有三项任务，总数不能从返回的 20 行或预读的 21 行累加。
            for (int member = 0; member < (group == 22 ? 3 : 1); member++) {
                String app = UUID.randomUUID().toString();
                seedApplication(tenant, app, "flow-" + group, 1);
                jdbc.update("UPDATE approval_application SET status='IN_APPROVAL',definition_version=? WHERE id=?", group, app);
                var process = runtime.startProcessInstanceByKey("expense-reimbursement",
                        Map.of("applicationId", app, "tenantId", tenant, "roundNo", 1));
                jdbc.update("UPDATE ACT_RU_TASK SET CREATE_TIME_=? WHERE PROC_INST_ID_=?",
                        Timestamp.from(created.plusSeconds(group)), process.getId());
            }
        }
        var statements = new ArrayList<String>();
        var observedJdbc = observedJdbc(statements);
        var observedReader = new JdbcApprovalOperationsReadAdapter(observedJdbc,
                new JdbcSubmissionHistoryGapQuery(observedJdbc, json));
        var report = observedReader.read(tenant, query(""), created.plusSeconds(60));
        assertThat(report.pendingTasks()).isEqualTo(24);
        assertThat(report.waitingNodes()).hasSize(20);
        assertThat(report.waitingNodes()).extracting(ApprovalOperationsReadPort.WaitingNode::tasks).containsOnly(1L);
        assertThat(report.waitingNodes().get(19).processKey()).isEqualTo("flow-20");
        assertThat(report.moreWaitingNodes()).isTrue();
        assertThat(report.oldestTasks()).hasSize(20);
        assertThat(report.moreOldestTasks()).isTrue();
        assertThat(statements.stream().filter(sql -> sql.contains("FROM ACT_RU_TASK")).count())
                .as("current task set reads for grouped nodes and oldest tasks").isEqualTo(2);

        var filtered = reader.read(tenant, new ApprovalOperationsReadPort.Query(query("").from(), query("").to(), "flow-22", 22L), created.plusSeconds(60));
        assertThat(filtered.pendingTasks()).isEqualTo(3);
        assertThat(filtered.waitingNodes()).hasSize(1);
        assertThat(filtered.waitingNodes().get(0).tasks()).isEqualTo(3);
        assertThat(filtered.moreWaitingNodes()).isFalse();
        assertThat(filtered.moreOldestTasks()).isFalse();
        var empty = reader.read(tenant, new ApprovalOperationsReadPort.Query(query("").from(), query("").to(), "flow-22", 21L), created);
        assertThat(empty.pendingTasks()).isZero();
        assertThat(empty.waitingNodes()).isEmpty();
        assertThat(empty.moreWaitingNodes()).isFalse();
    }

    /** 在真实 JDBC 连接上记录准备的 SQL，不替换查询结果或依赖执行耗时断言。 */
    private JdbcTemplate observedJdbc(List<String> statements) throws SQLException {
        var source = mock(javax.sql.DataSource.class);
        when(source.getConnection()).thenAnswer(invocation -> {
            Connection connection = jdbc.getDataSource().getConnection();
            return java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("prepareStatement")) statements.add((String) arguments[0]);
                        try {
                            return method.invoke(connection, arguments);
                        } catch (java.lang.reflect.InvocationTargetException exception) {
                            // 保留驱动原始异常，避免代理包装改变 Spring 的数据库错误处理。
                            throw exception.getCause();
                        }
                    });
        });
        return new JdbcTemplate(source);
    }

    private JsonNode report(String key) throws Exception {
        return json.read(mvc.perform(get("/api/v1/operations/approvals").param("from", "2020-01-01").param("to", "2020-01-03")
                .param("processKey", key).header("Authorization", token("admin"))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
    private ApprovalOperationsReadPort.Query query(String key) {
        return new ApprovalOperationsReadPort.Query(LocalDate.parse("2020-01-01"), LocalDate.parse("2020-01-03"), key, null);
    }
    private String seed(String tenant, String key, String state, String submitted, String completed) {
        String app = UUID.randomUUID().toString();
        seedApplication(tenant, app, key, 1); seedRound(tenant, app, 1, state, submitted, completed); return app;
    }
    private void seedApplication(String tenant, String app, String key, int rounds) {
        jdbc.update("""
                INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version)
                VALUES(?,?,?,?,1,'alice','历史轮次测试','{"secret":"secret-value"}','APPROVED',?,5)
                """, app, tenant, app, key, rounds);
    }
    private void seedRound(String tenant, String app, int round, String state, String submitted, String completed) {
        jdbc.update("""
                INSERT INTO approval_submission_round(tenant_id,application_id,round_no,process_instance_id,definition_version,title,payload_json,submitted_by,submitted_at,status,completed_by,completed_at)
                VALUES(?,?,?,?,1,'历史轮次测试','{"secret":"secret-value"}','alice',?,?,?,?)
                """, tenant, app, round, UUID.randomUUID().toString(), OffsetDateTime.parse(submitted), state,
                completed == null ? null : "finance", completed == null ? null : OffsetDateTime.parse(completed));
    }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
}
