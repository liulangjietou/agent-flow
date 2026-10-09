package io.agentflow.approval.operations;
import static io.agentflow.definition.DefinitionModels.*;
import static io.agentflow.support.MutationRequests.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;

import io.agentflow.approval.history.JdbcSubmissionHistoryGapQuery;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;

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

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 运营查询覆盖轮次口径、日期边界、租户权限、真实会签待办和返回上限。
 *
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${AGENTFLOW_OPERATIONS_TEST_URL:jdbc:h2:mem:approval-operations;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${AGENTFLOW_OPERATIONS_TEST_USER:sa}",
        "spring.datasource.password=${AGENTFLOW_OPERATIONS_TEST_PASSWORD:}",
        "spring.datasource.driver-class-name=${AGENTFLOW_OPERATIONS_TEST_DRIVER:org.h2.Driver}",
        "agentflow.auth.demo-enabled=true"})
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class ApprovalOperationsIntegrationTest {
    @Autowired MockMvc mvc;
    @MockitoSpyBean AuthService auth;
    @Autowired JsonUtil json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApprovalOperationsReadPort reader;
    @Autowired DefinitionApplicationService definitions;
    @Autowired TaskService tasks;
    @Autowired RuntimeService runtime;
    @Autowired org.flowable.spring.SpringProcessEngineConfiguration engine;
    @Autowired io.agentflow.calendar.BusinessCalendarRepository calendars;

    @Test
    void outcomeMetricsUseHistoricalCohortAndNeverReadNotificationOrModelBodies() throws Exception {
        String key = "quality-" + UUID.randomUUID();
        String app = seed("demo", key, "RETURNED", "2020-01-01T00:00:00Z", "2020-01-01T01:00:00Z");
        organization("demo", app, 1, "研发%_!部");
        seedRound("demo", app, 2, "IN_APPROVAL", "2020-01-04T00:00:00Z", null);
        organization("demo", app, 2, "另一部门");
        jdbc.update("UPDATE approval_application SET round_no=2 WHERE id=?", app);
        String recovered = delivery("demo", app, 1, "ACCEPTED", "EMAIL");
        jdbc.update(
                "INSERT INTO"
                    + " notification_delivery_event(delivery_id,version,status,attempts,cycle_attempts,occurred_at)"
                    + " VALUES(?,1,'RETRY_WAIT',1,1,?)",
                recovered, Timestamp.from(Instant.parse("2020-01-05T00:00:00Z")));
        jdbc.update(
                "INSERT INTO"
                    + " notification_delivery_event(delivery_id,version,status,attempts,cycle_attempts,occurred_at)"
                    + " VALUES(?,2,'FAILED',3,3,?)",
                recovered, Timestamp.from(Instant.parse("2020-01-05T00:01:00Z")));
        for (String status : List.of("FAILED", "RETRY_WAIT", "UNKNOWN", "SUPPRESSED", "PENDING", "IN_FLIGHT")) delivery("demo", app, 1, status, "ENTERPRISE_IM");
        for (String status : List.of("ADOPTED", "ADOPTED", "DISMISSED", "COMPLETED", "FAILED", "QUEUED", "RUNNING")) assist("demo", app, 1, status);
        delivery("demo", app, 2, "FAILED", "EMAIL"); assist("demo", app, 2, "ADOPTED");
        delivery("foreign", app, 1, "FAILED", "EMAIL"); assist("foreign", app, 1, "ADOPTED");
        var before = jdbc.queryForList("SELECT * FROM notification_dispatch ORDER BY id");
        var report = report(key, "%_!");
        var notifications = report.path("notifications");
        assertThat(notifications.path("deliveries").asLong()).isEqualTo(7);
        for (String field : List.of("accepted", "failed", "retryWaiting", "unknown", "suppressed", "pending", "inFlight", "previouslyFailed"))
            assertThat(notifications.path(field).asLong()).as(field).isEqualTo(1);
        var agent = report.path("agent");
        assertThat(agent.path("runs").asLong()).isEqualTo(7);
        assertThat(agent.path("adopted").asLong()).isEqualTo(2);
        assertThat(agent.path("dismissed").asLong()).isEqualTo(1);
        assertThat(agent.path("reviewedRuns").asLong()).isEqualTo(3);
        assertThat(agent.path("adoptionRatePercent").decimalValue()).isEqualByComparingTo("66.7");
        for (String field : List.of("awaitingReview", "failed", "queued", "running")) assertThat(agent.path(field).asLong()).as(field).isEqualTo(1);
        assertThat(report.toString()).doesNotContain("sensitive-body", "recipient@example.invalid", "state_json", "context_json");
        assertThat(report(key, "另一部门").path("notifications").path("deliveries").asLong()).isZero();
        assertThat(report(key, "不存在").path("agent").has("adoptionRatePercent")).isFalse();
        assertThat(jdbc.queryForList("SELECT * FROM notification_dispatch ORDER BY id")).isEqualTo(before);
    }

    @Test
    void historicalSlaDistinguishesDecisionsCancellationsMissingAndInvalidTimes() throws Exception {
        String key = "sla-history-" + UUID.randomUUID();
        String app = seed("demo", key, "APPROVED", "2020-01-01T00:00:00Z", "2020-01-01T01:00:00Z");
        historicalTask(app, "2020-01-01T00:05:00Z", "2020-01-01T00:10:00Z", null, "APPROVE");
        historicalTask(app, "2020-01-01T00:10:00Z", "2020-01-01T00:10:00Z", null, "APPROVE");
        historicalTask(app, "2020-01-01T00:11:00Z", "2020-01-01T00:10:00Z", "RETURN by finance", "RETURN");
        historicalTask(app, "2020-01-01T00:11:00Z", "2020-01-01T00:10:00Z", "REJECT by finance", "REJECT");
        historicalTask(app, "2020-01-01T00:11:00Z", "2020-01-01T00:10:00Z", "RETURN by finance", null);
        historicalTask(app, "2020-01-01T00:11:00Z", null, null, "APPROVE");
        historicalTask(app, "2019-12-31T23:59:00Z", "2020-01-01T00:10:00Z", null, "APPROVE");
        historicalTask(app, "2020-01-01T00:11:00Z", "2020-01-01T00:10:00Z", null, null);
        historicalTask(app, null, "2020-01-01T00:10:00Z", null, null);
        var sla = report(key).path("sla");
        assertThat(sla.path("decidedTasks").asLong()).isEqualTo(6);
        assertThat(sla.path("timedTasks").asLong()).isEqualTo(4);
        assertThat(sla.path("violatedTasks").asLong()).isEqualTo(2);
        assertThat(sla.path("violationRatePercent").decimalValue()).isEqualByComparingTo("50.0");
        for (String field : List.of("withoutDeadlineTasks", "invalidTimingTasks", "cancelledTasks", "unfinishedTasks", "unrecordedDecisionTasks"))
            assertThat(sla.path(field).asLong()).as(field).isEqualTo(1);
        String instance = jdbc.queryForObject(
                        "SELECT process_instance_id FROM approval_submission_round WHERE"
                                + " application_id=?", String.class, app);
        jdbc.update(
                "UPDATE ACT_HI_VARINST SET TEXT_='foreign' WHERE PROC_INST_ID_=? AND"
                        + " NAME_='tenantId'", instance);
        var invalidBinding = report(key).path("sla");
        assertThat(invalidBinding.path("decidedTasks").asLong()).isZero();
        assertThat(invalidBinding.has("violationRatePercent")).isFalse();
        assertThat(invalidBinding.path("unverifiedRounds").asLong()).isEqualTo(1);
    }

    @Test
    void realReturnAndRejectCountTheDecisionButNotCancelledCountersignPeers() throws Exception {
        try {
            for (String action : List.of("RETURN", "REJECT")) {
                engineTime("2020-01-01T09:00:00Z");
                var flow = timedCountersign();
                engineTime("2020-01-01T09:11:00Z");
                liveAction(flow.get("app"), "finance", action);
                var sla = report(flow.get("key")).path("sla");
                assertThat(sla.path("decidedTasks").asLong()).isEqualTo(1);
                assertThat(sla.path("timedTasks").asLong()).as("sla=%s, tasks=%s", sla, jdbc.queryForList(
                                        "SELECT"
                                            + " h.START_TIME_,h.END_TIME_,h.DUE_DATE_,h.DELETE_REASON_"
                                            + " FROM ACT_HI_TASKINST h JOIN"
                                            + " approval_submission_round r ON"
                                            + " r.process_instance_id=h.PROC_INST_ID_ WHERE"
                                            + " r.application_id=?", flow.get("app"))).isEqualTo(1);
                assertThat(sla.path("violatedTasks").asLong()).isEqualTo(1);
                assertThat(sla.path("cancelledTasks").asLong()).isEqualTo(1);
                assertThat(sla.path("unrecordedDecisionTasks").asLong()).isZero();
            }
        } finally { engine.getClock().reset(); }
    }

    @Test
    void realPauseResumeUsesTheRetainedTaskDeadlineAndPartialApprovalSample() throws Exception {
        try {
            engineTime("2020-01-01T09:00:00Z");
            var flow = timedCountersign(); String app = flow.get("app");
            engineTime("2020-01-01T09:05:00Z"); control(app, "pause");
            assertThat(report(flow.get("key")).path("sla").path("unfinishedTasks").asLong()).isEqualTo(2);
            assertThat(report(flow.get("key")).path("sla").has("violationRatePercent")).isFalse();
            engineTime("2020-01-01T10:05:00Z"); control(app, "resume");
            assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", app).list())
                    .allSatisfy(task -> assertThat(task.getDueDate().toInstant()).isEqualTo(Instant.parse("2020-01-01T10:10:00Z")));
            engineTime("2020-01-01T10:09:00Z"); liveAction(app, "finance", "APPROVE");
            var partial = report(flow.get("key")).path("sla");
            assertThat(partial.path("timedTasks").asLong()).isEqualTo(1);
            assertThat(partial.path("violatedTasks").asLong()).isZero();
            assertThat(partial.path("violationRatePercent").decimalValue()).isEqualByComparingTo("0.0");
            assertThat(partial.path("unfinishedTasks").asLong()).isEqualTo(1);
            engineTime("2020-01-01T10:11:00Z"); liveAction(app, "admin", "APPROVE");
            assertThat(report(flow.get("key")).path("sla").path("violationRatePercent").decimalValue()).isEqualByComparingTo("50.0");
        } finally { engine.getClock().reset(); }
    }

    private Map<String, String> timedCountersign() throws Exception {
        var hours = new java.util.EnumMap<java.time.DayOfWeek, List<io.agentflow.calendar.CalendarRules.Period>>(java.time.DayOfWeek.class);
        for (var day : java.time.DayOfWeek.values()) hours.put(day, List.of(new io.agentflow.calendar.CalendarRules.Period("09:00", "17:00")));
        var calendar = io.agentflow.calendar.BusinessCalendar.create("demo", "metrics-" + UUID.randomUUID(), "统计日历",
                new io.agentflow.calendar.CalendarRules("UTC", hours, List.of()), "admin", Instant.now());
        calendars.create(calendar);
        String key = "metrics-live-" + UUID.randomUUID();
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("review", "财务会签", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE", "approvalMode", "ALL",
                        "deadlineCalendarId", calendar.id().toString(), "deadlineCalendarRevision", "1", "deadlineWorkingMinutes", "10")),
                new Node("end", "结束", NodeType.END, Map.of())), List.of(new Edge("a", "start", "review", ""), new Edge("b", "review", "end", "")));
        var definition = definitions.create("demo", key, "历史办理统计", graph);
        definitions.publish(new Actor("demo", "admin", Set.of("ADMIN")), definition.id(), 0, "历史办理验收");
        String app = json.read(mvc.perform(post("/api/v1/applications").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("processKey", key, "definitionVersion", 1,
                        "businessNo", key, "title", "历史办理验收", "payload", Map.of()))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), JsonNode.class).path("id").asText();
        mvc.perform(post("/api/v1/applications/" + app + "/submit").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}")).andExpect(status().isOk());
        // 只调整提交窗口夹具；任务期限及后续办理均来自真实引擎和公开接口。
        jdbc.update("UPDATE approval_submission_round SET submitted_at=? WHERE application_id=?", Timestamp.from(Instant.parse("2020-01-01T09:00:00Z")), app);
        assertThat(jdbc.queryForList(
                                "SELECT h.DUE_DATE_ FROM ACT_HI_TASKINST h JOIN"
                                        + " approval_submission_round r ON"
                                        + " r.process_instance_id=h.PROC_INST_ID_ WHERE"
                                        + " r.application_id=?", Timestamp.class, app))
                .hasSize(2).allSatisfy(due -> assertThat(due.toInstant()).isEqualTo(Instant.parse("2020-01-01T09:10:00Z")));
        return Map.of("app", app, "key", key);
    }

    private void liveAction(String app, String user, String action) throws Exception {
        var task = tasks.createTaskQuery().processVariableValueEquals("applicationId", app).taskAssignee(user).singleResult();
        long version = jdbc.queryForObject("SELECT version FROM approval_application WHERE id=?", Long.class, app);
        mvc.perform(post("/api/v1/tasks/" + task.getId() + "/actions").header("Authorization", token(user))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("expectedVersion", version, "action", action, "comment", "统计验证"))))
                .andExpect(status().isOk());
    }

    private void control(String app, String action) throws Exception {
        long version = jdbc.queryForObject("SELECT version FROM approval_application WHERE id=?", Long.class, app);
        mvc.perform(post("/api/v1/applications/" + app + "/rounds/1/runtime/" + action).header("Authorization", token("admin"))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("expectedVersion", version, "reason", "统计验证"))))
                .andExpect(status().isOk());
    }
    private void engineTime(String time) { engine.getClock().setCurrentTime(java.util.Date.from(Instant.parse(time))); }

    private String delivery(String tenant, String app, int round, String status, String channel) {
        String inbox = UUID.randomUUID().toString(), id = UUID.randomUUID().toString();
        jdbc.update(
                """
INSERT INTO notification_inbox(id,tenant_id,recipient_id,event_key,application_id,title,business_no,kind,actor_id,round_no,created_at)
VALUES(?,?,'recipient@example.invalid',?,?,'sensitive-body',?,'APPROVED','actor',?,?)
""", inbox, tenant, inbox, app, app, round, Timestamp.from(Instant.parse("2020-01-05T00:00:00Z")));
        jdbc.update(
                """
INSERT INTO notification_dispatch(id,tenant_id,recipient_id,inbox_id,channel,consent_generation,status,created_at,updated_at)
VALUES(?,?,'recipient@example.invalid',?,?,1,?,?,?)
""", id, tenant, inbox, channel, status, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        return id;
    }

    private void assist(String tenant, String app, int round, String status) {
        int version = switch (status) { case "QUEUED" -> 1; case "RUNNING" -> 2; case "COMPLETED", "FAILED" -> 3; default -> 4; };
        jdbc.update(
                """
INSERT INTO agent_assist_run(id,tenant_id,application_id,application_version,round_no,status,version,context_json,state_json,created_at)
VALUES(?,?,?,1,?,?,?, 'sensitive-body','sensitive-body',?)
""", UUID.randomUUID().toString(), tenant, app, round, status, version, Timestamp.from(Instant.now()));
    }

    private void historicalTask(String app, String completed, String due, String deleted, String decision) {
        String instance = jdbc.queryForObject(
                        "SELECT process_instance_id FROM approval_submission_round WHERE"
                                + " application_id=?", String.class, app);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM ACT_HI_VARINST WHERE PROC_INST_ID_=?", Long.class, instance) == 0) {
            for (var entry : Map.of("tenantId", "demo", "applicationId", app).entrySet())
                jdbc.update(
                        "INSERT INTO"
                            + " ACT_HI_VARINST(ID_,PROC_INST_ID_,EXECUTION_ID_,NAME_,VAR_TYPE_,TEXT_)"
                            + " VALUES(?,?,?,?,'string',?)",
                        UUID.randomUUID().toString(), instance, instance, entry.getKey(), entry.getValue());
            jdbc.update(
                    "INSERT INTO"
                        + " ACT_HI_VARINST(ID_,PROC_INST_ID_,EXECUTION_ID_,NAME_,VAR_TYPE_,LONG_)"
                        + " VALUES(?,?,?,'roundNo','integer',1)",
                    UUID.randomUUID().toString(), instance, instance);
        }
        String task = UUID.randomUUID().toString();
        jdbc.update(
                """
INSERT INTO ACT_HI_TASKINST(ID_,PROC_INST_ID_,TENANT_ID_,START_TIME_,END_TIME_,DUE_DATE_,DELETE_REASON_)
VALUES(?,?,'demo',?,?,?,?)
""", task, instance, Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")),
                completed == null ? null : Timestamp.from(Instant.parse(completed)), due == null ? null : Timestamp.from(Instant.parse(due)), deleted);
        if (decision != null) jdbc.update(
                    """
INSERT INTO audit_event(id,tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,application_id,action,actor_id,payload_json,occurred_at)
VALUES(?,'demo',?,'Task',?,1,?,?,'finance','sensitive-body',?)
""", UUID.randomUUID().toString(), UUID.randomUUID().toString(), task, app, decision, Timestamp.from(Instant.now()));
    }

    @Test
    void organizationMetricsFollowEachHistoricalRoundAndKeepUnknownHistoryExplicit() throws Exception {
        String key = "round-organization-" + UUID.randomUUID(), app = UUID.randomUUID().toString();
        seedApplication("demo", app, key, 2);
        seedRound("demo", app, 1, "RETURNED", "2020-01-01T12:00:00Z", "2020-01-01T13:00:00Z");
        seedRound("demo", app, 2, "APPROVED", "2020-01-02T12:00:00Z", "2020-01-02T13:00:00Z");
        organization("demo", app, 1, "研发%_!部");
        organization("demo", app, 2, "财务部");
        String other = seed("demo", key, "APPROVED", "2020-01-02T14:00:00Z", "2020-01-02T15:00:00Z");
        organization("demo", other, 1, "研发%_!部");
        seed("demo", key, "APPROVED", "2020-01-03T12:00:00Z", "2020-01-03T13:00:00Z");
        seedApplication("demo", UUID.randomUUID().toString(), key, 2);
        String foreign = seed("foreign", key, "RETURNED", "2020-01-01T12:00:00Z", "2020-01-01T13:00:00Z");
        organization("foreign", foreign, 1, "研发%_!部");
        var before = jdbc.queryForList(
                        "SELECT * FROM approval_submission_round WHERE application_id=? ORDER BY"
                                + " round_no", app);
        var report = report(key, "%_!");
        var metrics = report.path("metrics");
        assertThat(report.path("organization").asText()).isEqualTo("%_!");
        assertThat(metrics.path("submittedRounds").asLong()).isEqualTo(2);
        assertThat(metrics.path("applications").asLong()).isEqualTo(2);
        assertThat(metrics.path("returned").asLong()).isEqualTo(1);
        assertThat(metrics.path("returnRatePercent").decimalValue()).isEqualByComparingTo("50.0");
        assertThat(report.path("daily").findValuesAsText("submittedRounds")).containsExactly("1", "1", "0");
        assertThat(report.path("processes").get(0).path("metrics").path("submittedRounds").asLong()).isEqualTo(2);
        assertThat(report.path("unrecordedHistoricalRounds").asLong()).isEqualTo(2);
        assertThat(report.toString()).doesNotContain("secret-value", "payload", "appointmentId");
        assertThat(report(key, "财务").path("metrics").path("submittedRounds").asLong()).isEqualTo(1);
        assertThat(report(key, "reviewer").path("metrics").path("submittedRounds").asLong()).isEqualTo(3);
        assertThat(report(key, "验收法人").path("metrics").path("submittedRounds").asLong()).isEqualTo(3);
        var empty = report(key, "不存在的组织");
        assertThat(empty.path("metrics").path("submittedRounds").asLong()).isZero();
        assertThat(empty.path("unrecordedHistoricalRounds").asLong()).isEqualTo(2);
        assertThat(jdbc.queryForList(
                                "SELECT * FROM approval_submission_round WHERE application_id=?"
                                        + " ORDER BY round_no", app)).isEqualTo(before);
    }

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
        var before = jdbc.queryForList(
                        "SELECT * FROM approval_submission_round WHERE application_id=? ORDER BY"
                                + " round_no", app);
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
        assertThat(jdbc.queryForList(
                                "SELECT * FROM approval_submission_round WHERE application_id=?"
                                        + " ORDER BY round_no", app)).isEqualTo(before);
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
            jdbc.update(
                    "UPDATE approval_submission_round SET definition_version=? WHERE"
                            + " application_id=?", version, app);
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
        organization("demo", app, 1, "实际在审部门");
        var liveTasks = tasks.createTaskQuery().processInstanceId(task.getProcessInstanceId()).list();
        tasks.setDueDate(liveTasks.get(0).getId(), java.util.Date.from(Instant.parse("2000-01-01T00:00:00Z")));
        tasks.setDueDate(liveTasks.get(1).getId(), java.util.Date.from(Instant.parse("2100-01-01T00:00:00Z")));
        var filtered = report(key, "实际在审");
        assertThat(filtered.path("metrics").path("submittedRounds").asLong()).isZero();
        assertThat(filtered.path("pendingTasks").asLong()).isEqualTo(2);
        assertThat(filtered.path("overdueTasks").asLong()).isEqualTo(1);
        assertThat(filtered.path("waitingNodes").get(0).path("tasks").asLong()).isEqualTo(2);
        assertThat(filtered.path("oldestTasks")).hasSize(2);
        assertThat(report(key, "其他部门").path("pendingTasks").asLong()).isZero();
        runtime.setVariable(task.getProcessInstanceId(), "roundNo", 9);
        assertThat(reader.read("demo", query(key), Instant.now()).pendingTasks()).isZero();
        assertThat(report(key, "实际在审").path("pendingTasks").asLong()).isZero();
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
                jdbc.update(
                        "UPDATE approval_application SET status='IN_APPROVAL',definition_version=?"
                                + " WHERE id=?", group, app);
                var process = runtime.startProcessInstanceByKey("expense-reimbursement",
                        Map.of("applicationId", app, "tenantId", tenant, "roundNo", 1));
                jdbc.update("UPDATE ACT_RU_TASK SET CREATE_TIME_=? WHERE PROC_INST_ID_=?",
                        Timestamp.from(created.plusSeconds(group)), process.getId());
            }
        }
        var statements = new ArrayList<String>();
        var observedJdbc = observedJdbc(statements);
        var observedReader = new JdbcApprovalOperationsReadAdapter(
                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                (observedJdbc).getDataSource(),
                                io.agentflow.approval.operations.mapper
                                        .ApprovalOperationsReadAdapterMapper.class),
                        new JdbcSubmissionHistoryGapQuery(
                                io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                        (observedJdbc).getDataSource(),
                                        io.agentflow.approval.history.mapper
                                                .SubmissionHistoryGapQueryMapper.class), json));
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
        return report(key, "");
    }
    private JsonNode report(String key, String organization) throws Exception {
        return json.read(mvc.perform(get("/api/v1/operations/approvals").param("from", "2020-01-01").param("to", "2020-01-03")
                .param("processKey", key).param("organization", organization).header("Authorization", token("admin"))).andExpect(status().isOk())
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
        jdbc.update(
                """
INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version)
VALUES(?,?,?,?,1,'alice','历史轮次测试','{"secret":"secret-value"}','APPROVED',?,5)
""", app, tenant, app, key, rounds);
    }
    private void seedRound(String tenant, String app, int round, String state, String submitted, String completed) {
        jdbc.update(
                """
INSERT INTO approval_submission_round(tenant_id,application_id,round_no,process_instance_id,definition_version,title,payload_json,submitted_by,submitted_at,status,completed_by,completed_at)
VALUES(?,?,?,?,1,'历史轮次测试','{"secret":"secret-value"}','alice',?,?,?,?)
""", tenant, app, round, UUID.randomUUID().toString(), OffsetDateTime.parse(submitted), state,
                completed == null ? null : "finance", completed == null ? null : OffsetDateTime.parse(completed));
    }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }

    private void organization(String tenant, String app, int round, String department) {
        var snapshot = new io.agentflow.organization.InitiatorContext(UUID.randomUUID(), UUID.randomUUID(), "alice", 1,
                UUID.randomUUID(), "验收法人", UUID.randomUUID(), department, UUID.randomUUID(), "Reviewer");
        jdbc.update(
                """
UPDATE approval_submission_round SET initiator_context_json=?,initiator_legal_entity_name=?,initiator_department_name=?,initiator_position_name=?
WHERE tenant_id=? AND application_id=? AND round_no=?
""", json.write(snapshot), snapshot.legalEntityName(), snapshot.departmentName(), snapshot.positionName(), tenant, app, round);
    }
}
