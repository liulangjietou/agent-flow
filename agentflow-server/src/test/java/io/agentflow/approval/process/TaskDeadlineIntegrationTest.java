package io.agentflow.approval.process;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.calendar.BusinessCalendar;
import io.agentflow.calendar.BusinessCalendarRepository;
import io.agentflow.calendar.CalendarRules;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.definition.DefinitionModels;
import io.agentflow.notification.InboxRepository;
import io.agentflow.approval.service.TaskRecipientDirectory;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 在真实引擎任务生命周期中验证固定日历、任务创建起算及转交委派不重置。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.sla.reminders-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class TaskDeadlineIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired DefinitionApplicationService definitions;
    @Autowired BusinessCalendarRepository calendars;
    @Autowired TaskService tasks;
    @Autowired SpringProcessEngineConfiguration engine;
    @Autowired FlowableTaskDeadlineReminders reminders;
    @Autowired JdbcTemplate jdbc;
    @Autowired HistoryService history;
    @Autowired io.agentflow.approval.operations.ApprovalOperationsReadPort operations;
    @MockitoSpyBean InboxRepository inbox;
    @MockitoSpyBean TaskRecipientDirectory recipients;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("agentflow.deadline-test.jdbc-url", "jdbc:h2:mem:task-deadline;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"));
        registry.add("spring.datasource.driver-class-name", () -> System.getProperty("agentflow.deadline-test.jdbc-driver", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getProperty("agentflow.deadline-test.jdbc-user", "sa"));
        registry.add("spring.datasource.password", () -> System.getProperty("agentflow.deadline-test.jdbc-password", ""));
    }

    @AfterEach
    void restoreEngineTime() { engine.getClock().reset(); }

    @Test
    void taskCreationUsesFrozenCalendarAndDelegationAndTransferDoNotRestartTheDeadline() throws Exception {
        var calendar = calendar();
        var definition = publish(calendar);
        setTime("2026-09-25T16:30:00Z");
        var application = createAndSubmit(definition);
        String id = application.path("id").asText();
        var first = task(id);
        Instant due = Instant.parse("2026-09-29T09:30:00Z");
        assertThat(first.getDueDate()).isEqualTo(Date.from(due));
        assertThat(tasks.getVariableLocal(first.getId(), "agentflowDeadlineCalendarRevision")).isEqualTo(1L);
        assertThat(read("/tasks/" + first.getId(), "manager").path("dueAt").asText()).isEqualTo(due.toString());
        var queue = read("/workspace/tasks", "manager").path("items");
        assertThat(queue.findValuesAsText("dueAt")).contains(due.toString());
        var query = new io.agentflow.approval.operations.ApprovalOperationsReadPort.Query(
                LocalDate.parse("2026-09-25"), LocalDate.parse("2026-09-29"), definition.key(), 1L);
        assertThat(operations.read("demo", query, due.minusMillis(1)).overdueTasks()).isZero();
        var overdue = operations.read("demo", query, due);
        assertThat(overdue.overdueTasks()).isEqualTo(1);
        assertThat(overdue.waitingNodes()).hasSize(1).allSatisfy(node -> assertThat(node.overdueTasks()).isEqualTo(1));
        assertThat(overdue.oldestTasks()).hasSize(1).allSatisfy(item -> assertThat(item.dueAt()).isEqualTo(due));
        assertThat(operations.read("foreign", query, due).overdueTasks()).isZero();

        calendars.update(calendar.revise("新版短工时", new CalendarRules("UTC",
                Map.of(DayOfWeek.TUESDAY, List.of(new CalendarRules.Period("12:00", "13:00"))), List.of()),
                1, "admin", Instant.now()), 1);
        setTime("2026-09-29T10:00:00Z");
        act(id, "manager", "DELEGATE", "finance");
        assertThat(task(id).getDueDate()).isEqualTo(Date.from(due));
        act(id, "finance", "RESOLVE", null);
        assertThat(task(id).getDueDate()).isEqualTo(Date.from(due));
        act(id, "manager", "TRANSFER", "admin");
        assertThat(task(id).getDueDate()).isEqualTo(Date.from(due));
        act(id, "admin", "APPROVE", null);
        var next = task(id);
        assertThat(next.getTaskDefinitionKey()).isEqualTo("finance");
        assertThat(next.getDueDate()).isEqualTo(Date.from(Instant.parse("2026-09-29T11:00:00Z")));
        assertThat(tasks.getVariableLocal(next.getId(), "agentflowDeadlineCalendarRevision")).isEqualTo(1L);
        assertThat(history.createHistoricTaskInstanceQuery().taskId(first.getId()).singleResult().getDueDate())
                .isEqualTo(Date.from(due));
    }

    @Test
    void concurrentReminderSendsOnceToCurrentDelegateAndNeverChangesApprovalStatus() throws Exception {
        setTime("2026-09-25T16:30:00Z");
        String id = createAndSubmit(publish(calendar())).path("id").asText();
        var task = task(id);
        Instant due = task.getDueDate().toInstant();
        assertThat(reminders.remind(task.getId(), due.minusMillis(1))).isFalse();
        act(id, "manager", "DELEGATE", "finance");
        var before = read("/applications/" + id, "alice");
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> { ready.await(5, TimeUnit.SECONDS); return reminders.remind(task.getId(), due); });
            var second = pool.submit(() -> { ready.await(5, TimeUnit.SECONDS); return reminders.remind(task.getId(), due); });
            ready.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally { pool.shutdownNow(); }
        assertThat(read("/applications/" + id, "alice")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notification_inbox WHERE application_id=? AND kind='TASK_OVERDUE'",
                String.class, id)).containsExactly("finance");
        assertThat(reminders.candidates(due, null)).noneMatch(candidate -> candidate.taskId().equals(task.getId()));
        assertThat(reminders.remind(task.getId(), due.plusSeconds(60))).isFalse();
    }

    @Test
    void failedDeliveryRollsBackTheMarkerAndRetriesWithoutDuplicates() throws Exception {
        setTime("2026-09-25T16:30:00Z");
        String id = createAndSubmit(publish(calendar())).path("id").asText();
        var task = task(id);
        Instant due = task.getDueDate().toInstant();
        doThrow(new IllegalStateException("Inbox unavailable")).when(inbox).append(anyString(), any());
        try {
            assertThatThrownBy(() -> reminders.remind(task.getId(), due)).isInstanceOf(IllegalStateException.class);
        } finally { doCallRealMethod().when(inbox).append(anyString(), any()); }
        assertThat(tasks.getVariableLocal(task.getId(), "agentflowDeadlineRemindedAt")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE application_id=? AND kind='TASK_OVERDUE'",
                Long.class, id)).isZero();
        assertThat(reminders.remind(task.getId(), due)).isTrue();
        assertThat(reminders.remind(task.getId(), due)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"RETURN", "WITHDRAW"})
    void stoppedRoundCancelsTheOldReminderAndResubmissionStartsANewDeadline(String action) throws Exception {
        setTime("2026-09-25T16:30:00Z");
        String id = createAndSubmit(publish(calendar())).path("id").asText();
        var original = task(id);
        if (action.equals("RETURN")) act(id, "manager", "RETURN", null);
        else send("/applications/" + id + "/withdraw", "alice", Map.of("expectedVersion", 2, "comment", "重新填报"), 200);
        assertThat(reminders.remind(original.getId(), original.getDueDate().toInstant().plusSeconds(1))).isFalse();
        setTime("2026-09-29T10:00:00Z");
        send("/applications/" + id + "/submit", "alice", Map.of("expectedVersion", 3), 200);
        assertThat(task(id).getDueDate()).isEqualTo(Date.from(Instant.parse("2026-09-29T11:00:00Z")));
        assertThat(task(id).getId()).isNotEqualTo(original.getId());
        act(id, "manager", "APPROVE", null);
        var last = task(id);
        act(id, "finance", "APPROVE", null);
        assertThat(reminders.remind(last.getId(), last.getDueDate().toInstant().plusSeconds(1))).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE application_id=? AND kind='TASK_OVERDUE'",
                Long.class, id)).isZero();
    }

    @Test
    void missingCurrentRecipientRemainsRetryableInsteadOfBeingMarkedDelivered() throws Exception {
        setTime("2026-09-25T16:30:00Z");
        String id = createAndSubmit(publish(calendar())).path("id").asText();
        var task = task(id);
        Instant due = task.getDueDate().toInstant();
        doReturn(List.of()).when(recipients).members(anyString(), any(), any());
        try {
            assertThat(reminders.remind(task.getId(), due)).isFalse();
        } finally { doCallRealMethod().when(recipients).members(anyString(), any(), any()); }
        assertThat(tasks.getVariableLocal(task.getId(), "agentflowDeadlineRemindedAt")).isNull();
        assertThat(reminders.candidates(due, null)).anyMatch(candidate -> candidate.taskId().equals(task.getId()));
        assertThat(reminders.remind(task.getId(), due)).isTrue();
    }

    @Test
    void parallelBranchAndEveryCountersignTaskReceiveTheirOwnDeadline() throws Exception {
        var calendar = calendar();
        var properties = new java.util.HashMap<>(approvalNode("finance", calendar).properties());
        properties.put("assigneeRule", "role:FINANCE");
        properties.put("approvalMode", "ALL");
        var graph = new DefinitionModels.Graph(List.of(
                new DefinitionModels.Node("start", "开始", DefinitionModels.NodeType.START, Map.of()),
                new DefinitionModels.Node("fork", "并行", DefinitionModels.NodeType.PARALLEL_GATEWAY, Map.of()),
                approvalNode("manager", calendar),
                new DefinitionModels.Node("finance", "财务会签", DefinitionModels.NodeType.USER_TASK, properties),
                new DefinitionModels.Node("join", "汇合", DefinitionModels.NodeType.PARALLEL_GATEWAY, Map.of()),
                new DefinitionModels.Node("end", "结束", DefinitionModels.NodeType.END, Map.of())), List.of(
                new DefinitionModels.Edge("first", "start", "fork", ""),
                new DefinitionModels.Edge("manager-in", "fork", "manager", ""),
                new DefinitionModels.Edge("finance-in", "fork", "finance", ""),
                new DefinitionModels.Edge("manager-out", "manager", "join", ""),
                new DefinitionModels.Edge("finance-out", "finance", "join", ""),
                new DefinitionModels.Edge("last", "join", "end", "")));
        var draft = definitions.create("demo", "sla-parallel-" + UUID.randomUUID(), "并行会签期限", graph);
        var published = definitions.publish(new Actor("demo", "admin", Set.of("ADMIN")), draft.id(), 0, "发布");
        setTime("2026-09-25T16:30:00Z");
        String id = createAndSubmit(published).path("id").asText();
        var active = tasks.createTaskQuery().processVariableValueEquals("applicationId", id).list();
        assertThat(active).hasSize(3).allSatisfy(task -> {
            assertThat(task.getDueDate()).isEqualTo(Date.from(Instant.parse("2026-09-29T09:30:00Z")));
            assertThat(tasks.getVariableLocal(task.getId(), "agentflowDeadlineStartedAt")).isEqualTo("2026-09-25T16:30:00Z");
        });
        assertThat(active.stream().filter(task -> task.getTaskDefinitionKey().equals("finance")))
                .extracting(org.flowable.task.api.Task::getAssignee).containsExactlyInAnyOrder("admin", "finance");
    }

    private BusinessCalendar calendar() {
        var hours = new EnumMap<DayOfWeek, List<CalendarRules.Period>>(DayOfWeek.class);
        for (DayOfWeek day : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) {
            hours.put(day, List.of(new CalendarRules.Period("09:00", "17:00")));
        }
        var rules = new CalendarRules("UTC", hours,
                List.of(new CalendarRules.DayOverride(LocalDate.parse("2026-09-28"), List.of(), "假日")));
        var calendar = BusinessCalendar.create("demo", "sla-" + UUID.randomUUID(), "审批工时", rules, "admin", Instant.now());
        calendars.create(calendar);
        return calendar;
    }

    private DefinitionModels.DefinitionDraft publish(BusinessCalendar calendar) {
        var graph = new DefinitionModels.Graph(List.of(
                new DefinitionModels.Node("start", "开始", DefinitionModels.NodeType.START, Map.of()),
                approvalNode("manager", calendar), approvalNode("finance", calendar),
                new DefinitionModels.Node("end", "结束", DefinitionModels.NodeType.END, Map.of())), List.of(
                new DefinitionModels.Edge("first", "start", "manager", ""),
                new DefinitionModels.Edge("second", "manager", "finance", ""),
                new DefinitionModels.Edge("last", "finance", "end", "")));
        var draft = definitions.create("demo", "sla-" + UUID.randomUUID(), "实际任务期限", graph);
        return definitions.publish(new Actor("demo", "admin", Set.of("ADMIN")), draft.id(), 0, "期限验收");
    }

    private DefinitionModels.Node approvalNode(String user, BusinessCalendar calendar) {
        return new DefinitionModels.Node(user, user, DefinitionModels.NodeType.USER_TASK,
                Map.of("assigneeRule", "user:" + user, "deadlineCalendarId", calendar.id().toString(),
                        "deadlineCalendarRevision", "1", "deadlineWorkingMinutes", "60"));
    }

    private JsonNode createAndSubmit(DefinitionModels.DefinitionDraft definition) throws Exception {
        var draft = send("/applications", "alice", Map.of("businessNo", "sla-" + UUID.randomUUID(),
                "processKey", definition.key(), "definitionVersion", 1, "title", "期限测试", "payload", Map.of()), 201);
        return send("/applications/" + draft.path("id").asText() + "/submit", "alice", Map.of("expectedVersion", 1), 200);
    }

    private void act(String applicationId, String user, String action, String target) throws Exception {
        var body = new java.util.HashMap<String, Object>();
        body.put("action", action);
        body.put("expectedVersion", read("/applications/" + applicationId, "alice").path("version").asLong());
        body.put("comment", "期限验证");
        if (target != null) body.put("targetUser", target);
        send("/tasks/" + task(applicationId).getId() + "/actions", user, body, 200);
    }

    private org.flowable.task.api.Task task(String applicationId) {
        return tasks.createTaskQuery().processVariableValueEquals("applicationId", applicationId).singleResult();
    }

    private JsonNode read(String path, String user) throws Exception {
        var response = mvc.perform(get("/api/v1" + path).header("Authorization", token(user)))
                .andExpect(status().isOk()).andReturn();
        return json.read(response.getResponse().getContentAsString(), JsonNode.class);
    }

    private JsonNode send(String path, String user, Map<String, ?> body, int expectedStatus) throws Exception {
        var response = mvc.perform(post("/api/v1" + path).header("Authorization", token(user))
                        .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(body))).andExpect(status().is(expectedStatus)).andReturn();
        return json.read(response.getResponse().getContentAsString(), JsonNode.class);
    }

    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private void setTime(String time) { engine.getClock().setCurrentTime(Date.from(Instant.parse(time))); }
}
