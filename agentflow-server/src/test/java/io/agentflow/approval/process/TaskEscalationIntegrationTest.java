package io.agentflow.approval.process;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.approval.repository.SubprocessCallRepository;
import io.agentflow.calendar.BusinessCalendar;
import io.agentflow.calendar.BusinessCalendarRepository;
import io.agentflow.calendar.CalendarRules;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.definition.DefinitionValidationException;
import io.agentflow.definition.SubprocessPolicy;
import io.agentflow.form.FormSchema;
import io.agentflow.notification.InboxRepository;
import io.agentflow.organization.LocalOrganizationDirectory;
import io.agentflow.organization.OrganizationAssigneeResolver;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flowable.engine.TaskService;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 原任务、日历、暂停、通知与权限的真实事务验收；升级从不生成审批意见。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.sla.reminders-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class TaskEscalationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired DefinitionApplicationService definitions;
    @Autowired BusinessCalendarRepository calendars;
    @Autowired TaskService tasks;
    @Autowired SpringProcessEngineConfiguration engine;
    @Autowired FlowableTaskEscalations escalations;
    @Autowired JdbcTemplate jdbc;
    @Autowired SubprocessCallRepository calls;
    @MockitoSpyBean InboxRepository inbox;
    @MockitoSpyBean LocalOrganizationDirectory directory;
    @MockitoSpyBean OrganizationAssigneeResolver recipients;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry values) {
        values.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_TASK_ESCALATION_URL", "jdbc:h2:mem:task-escalation;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"));
        values.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_TASK_ESCALATION_DRIVER", "org.h2.Driver"));
        values.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_TASK_ESCALATION_USER", "sa"));
        values.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_TASK_ESCALATION_PASSWORD", ""));
    }
    @AfterEach void resetTime() { engine.getClock().reset(); }

    @Test
    void freezesOriginalCalendarAndAudienceAndConcurrentEscalationSendsOnlyOnce() throws Exception {
        var calendar = calendar(); var application = start(publish(calendar, "user:finance")); var task = task(application);
        Instant due = Instant.parse("2026-10-05T10:30:00Z");
        assertThat(tasks.getVariableLocal(task.getId(), TaskEscalationBindings.DUE_AT)).isEqualTo(Date.from(due));
        var original = json.read((String) tasks.getVariableLocal(task.getId(), TaskEscalationBindings.AUDIENCE), OrganizationAssigneeResolver.Selection.class);
        assertThat(original.subjects()).containsExactly("finance");
        calendars.update(calendar.revise("后续改短日历", new CalendarRules("UTC",
                Map.of(DayOfWeek.MONDAY, List.of(new CalendarRules.Period("09:00", "10:00"))), List.of()), 1, "admin", Instant.now()), 1);
        doReturn(new OrganizationAssigneeResolver.Selection(999, "user:finance", List.of("admin"))).when(recipients).resolveEscalation(eq("demo"), anyString(), any());
        act(application, "manager", "TRANSFER", "admin");
        var before = read("/applications/" + application, "alice");
        assertThat(escalations.escalate(task.getId(), due.minusMillis(1))).isFalse();
        assertThat(escalations.candidates(due, null)).anyMatch(c -> c.taskId().equals(task.getId()));
        var executor = Executors.newFixedThreadPool(2); var gate = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> { gate.await(5, TimeUnit.SECONDS); return escalations.escalate(task.getId(), due); });
            var second = executor.submit(() -> { gate.await(5, TimeUnit.SECONDS); return escalations.escalate(task.getId(), due); });
            gate.countDown(); assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        } finally { executor.shutdownNow(); }
        assertThat(escalationRecipients(application)).containsExactly("finance");
        assertThat(read("/applications/" + application, "alice")).isEqualTo(before);
        assertThat(task(application).getAssignee()).isEqualTo("admin");
        assertThat(escalations.candidates(due.plusSeconds(1), null)).noneMatch(c -> c.taskId().equals(task.getId()));
        mvc.perform(get("/api/v1/tasks/" + task.getId()).header("Authorization", token("finance"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/applications/" + application).header("Authorization", token("finance"))).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @CsvSource({"09:30:00,10:15:00,10:45:00", "10:10:00,10:00:00,10:05:00"})
    void pausePreservesSeparateRemainingEscalationTime(String pause, String originalDueTime, String escalationTime) throws Exception {
        String application = start(publish(calendar(), "user:finance")); var task = task(application);
        setTime("2026-10-05T" + pause + "Z"); control(application, "pause");
        assertThat(escalations.candidates(Instant.parse("2026-10-06T12:00:00Z"), null)).noneMatch(c -> c.taskId().equals(task.getId()));
        assertThat(escalations.escalate(task.getId(), Instant.parse("2026-10-06T12:00:00Z"))).isFalse();
        setTime("2026-10-06T09:45:00Z"); control(application, "resume");
        Instant due = Instant.parse("2026-10-06T" + escalationTime + "Z");
        assertThat(tasks.getVariableLocal(task.getId(), TaskEscalationBindings.DUE_AT)).isEqualTo(Date.from(due));
        Instant approvalDue = Instant.parse((pause.equals("09:30:00") ? "2026-10-06T" : "2026-10-05T") + originalDueTime + "Z");
        assertThat(task(application).getDueDate()).isEqualTo(Date.from(approvalDue));
        assertThat(escalations.escalate(task.getId(), due.minusMillis(1))).isFalse();
        assertThat(escalations.escalate(task.getId(), due)).isTrue();
        setTime("2026-10-06T11:00:00Z"); control(application, "pause");
        setTime("2026-10-07T11:00:00Z"); control(application, "resume");
        assertThat(tasks.getVariableLocal(task.getId(), TaskEscalationBindings.DUE_AT)).isEqualTo(Date.from(due));
        assertThat(escalations.escalate(task.getId(), Instant.parse("2026-10-07T12:00:00Z"))).isFalse();
        assertThat(escalationRecipients(application)).containsExactly("finance");
    }

    @Test
    void notificationWriteFailureRollsBackEscalationAndStoppedTaskCannotNotify() throws Exception {
        String application = start(publish(calendar(), "role:FINANCE")); var task = task(application);
        Instant due = Instant.parse("2026-10-05T10:30:00Z");
        doAnswer(invocation -> { invocation.callRealMethod(); throw new IllegalStateException("Escalation inbox unavailable"); }).when(inbox).append(anyString(), any());
        try { assertThatThrownBy(() -> escalations.escalate(task.getId(), due)).isInstanceOf(IllegalStateException.class); }
        finally { doCallRealMethod().when(inbox).append(anyString(), any()); }
        assertThat(escalationRecipients(application)).isEmpty();
        assertThat(tasks.getVariableLocal(task.getId(), TaskEscalationBindings.ESCALATED_AT)).isNull();
        assertThat(tasks.getVariableLocal(task.getId(), TaskEscalationBindings.NOTIFIED_RECIPIENTS)).isNull();
        assertThat(escalations.escalate(task.getId(), due)).isTrue();
        assertThat(escalationRecipients(application)).containsExactlyInAnyOrder("admin", "finance");
        String stopped = start(publish(calendar(), "user:finance")); var previous = task(stopped);
        send("/applications/" + stopped + "/withdraw", "alice", Map.of("expectedVersion", 2, "comment", "停止原任务"), 200);
        assertThat(escalations.escalate(previous.getId(), due)).isFalse();
        assertThat(escalationRecipients(stopped)).isEmpty();
    }

    @Test
    void inactiveOriginalRecipientsAreNotReplacedAndRemainEligibleForALaterScan() throws Exception {
        String application = start(publish(calendar(), "user:finance")); var task = task(application);
        Instant due = Instant.parse("2026-10-05T10:30:00Z");
        doReturn(false).when(directory).activeRecipient("demo", "finance");
        assertThat(escalations.escalate(task.getId(), due)).isFalse();
        assertThat(escalations.candidates(due, null)).anyMatch(c -> c.taskId().equals(task.getId()));
        doCallRealMethod().when(directory).activeRecipient("demo", "finance");
        assertThat(escalations.escalate(task.getId(), due.plusSeconds(1))).isTrue();
        assertThat(escalationRecipients(application)).containsExactly("finance");
    }

    @Test
    void rootPauseFreezesChildEscalationAndRootTerminationStopsIt() throws Exception {
        var child = publish(calendar(), "user:finance");
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("child", "独立子审批", NodeType.SUB_PROCESS, new SubprocessPolicy(child.key(), child.version(), Map.of()).properties()),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "child", ""), new Edge("b", "child", "end", "")));
        var parent = definitions.create("demo", "parent-" + UUID.randomUUID(), "原父流程", graph, new FormSchema(1, List.of()));
        var published = definitions.publish(new Actor("demo", "admin", Set.of("ADMIN")), parent.id(), parent.revision(), "子流程升级验收");
        String root = start(published);
        String childId = calls.findByParentRound("demo", UUID.fromString(root), 1).get(0).childApplicationId().toString();
        var task = task(childId);
        setTime("2026-10-05T10:10:00Z"); control(root, "pause");
        assertThat(escalations.escalate(task.getId(), Instant.parse("2026-10-06T12:00:00Z"))).isFalse();
        setTime("2026-10-06T09:45:00Z"); control(root, "resume");
        Instant due = Instant.parse("2026-10-06T10:05:00Z");
        assertThat(tasks.getVariableLocal(task.getId(), TaskEscalationBindings.DUE_AT)).isEqualTo(Date.from(due));
        assertThat(escalations.escalate(task.getId(), due.minusMillis(1))).isFalse();
        control(root, "terminate");
        assertThat(escalations.escalate(task.getId(), due)).isFalse();
        assertThat(escalationRecipients(childId)).isEmpty();
    }

    @Test
    void newPublishedEscalationDoesNotRetrofitAnOlderVersionAndMissingAudienceBlocksPublish() throws Exception {
        var template = publish(calendar(), "user:finance");
        var oldProperties = new LinkedHashMap<>(template.graph().nodes().get(1).properties());
        oldProperties.remove("escalationWorkingMinutes"); oldProperties.remove("escalationRecipientRule");
        var oldGraph = new Graph(List.of(template.graph().nodes().get(0), new Node("review", "原审批", NodeType.USER_TASK, oldProperties),
                template.graph().nodes().get(2)), template.graph().edges());
        String key = "version-" + UUID.randomUUID();
        var first = definitions.create("demo", key, "旧规则", oldGraph);
        var publisher = new Actor("demo", "admin", Set.of("ADMIN"));
        first = definitions.publish(publisher, first.id(), first.revision(), "无升级原版本");
        var second = definitions.create("demo", key, "新规则", template.graph());
        second = definitions.publish(publisher, second.id(), second.revision(), "显式开启升级");
        String oldApplication = start(first), newApplication = start(second);
        assertThat(tasks.getVariableLocal(task(oldApplication).getId(), TaskEscalationBindings.DUE_AT)).isNull();
        Instant due = Instant.parse("2026-10-05T10:30:00Z");
        assertThat(escalations.escalate(task(oldApplication).getId(), due)).isFalse();
        assertThat(escalations.escalate(task(newApplication).getId(), due)).isTrue();
        assertThat(escalationRecipients(oldApplication)).isEmpty();
        assertThatThrownBy(() -> publish(calendar(), "user:missing-recipient"))
                .isInstanceOfSatisfying(DefinitionValidationException.class,
                        error -> assertThat(error.errors()).containsExactly("ESCALATION_RECIPIENT_UNAVAILABLE:review"));
    }

    private BusinessCalendar calendar() {
        var days = new EnumMap<DayOfWeek, List<CalendarRules.Period>>(DayOfWeek.class);
        for (var day : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) days.put(day, List.of(new CalendarRules.Period("09:00", "17:00")));
        var calendar = BusinessCalendar.create("demo", "escalation-" + UUID.randomUUID(), "升级工作日历", new CalendarRules("UTC", days, List.of()), "admin", Instant.now());
        calendars.create(calendar); return calendar;
    }
    private DefinitionDraft publish(BusinessCalendar calendar, String rule) {
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("review", "原审批节点", NodeType.USER_TASK, Map.of("assigneeRule", "user:manager", "deadlineCalendarId", calendar.id().toString(),
                        "deadlineCalendarRevision", "1", "deadlineWorkingMinutes", "60", "escalationWorkingMinutes", "30", "escalationRecipientRule", rule)),
                new Node("end", "结束", NodeType.END, Map.of())), List.of(new Edge("first", "start", "review", ""), new Edge("last", "review", "end", "")));
        var draft = definitions.create("demo", "escalation-" + UUID.randomUUID(), "升级审批", graph, new FormSchema(1, List.of()));
        return definitions.publish(new Actor("demo", "admin", Set.of("ADMIN")), draft.id(), 0, "验收升级");
    }
    private String start(DefinitionDraft definition) throws Exception {
        setTime("2026-10-05T09:00:00Z");
        var draft = send("/applications", "alice", Map.of("businessNo", "ESC-" + UUID.randomUUID(), "processKey", definition.key(), "definitionVersion", definition.version(),
                "title", "升级实际通知", "payload", Map.of()), 201);
        String id = draft.path("id").asText(); send("/applications/" + id + "/submit", "alice", Map.of("expectedVersion", 1), 200); return id;
    }
    private List<String> escalationRecipients(String id) {
        return jdbc.queryForList("SELECT recipient_id FROM notification_inbox WHERE application_id=? AND kind='TASK_ESCALATED' ORDER BY recipient_id", String.class, id);
    }
    private void control(String id, String action) throws Exception {
        send("/applications/" + id + "/rounds/1/runtime/" + action, "admin", Map.of("expectedVersion", read("/applications/" + id, "alice").path("version").asLong(), "reason", "升级时限验收"), 200);
    }
    private void act(String id, String user, String action, String target) throws Exception {
        send("/tasks/" + task(id).getId() + "/actions", user, Map.of("action", action, "targetUser", target, "expectedVersion", read("/applications/" + id, "alice").path("version").asLong(), "comment", "原任务责任验收"), 200);
    }
    private org.flowable.task.api.Task task(String id) { return tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult(); }
    private JsonNode read(String path, String user) throws Exception {
        var response = mvc.perform(get("/api/v1" + path).header("Authorization", token(user))).andExpect(status().isOk()).andReturn();
        return json.read(response.getResponse().getContentAsString(), JsonNode.class);
    }
    private JsonNode send(String path, String user, Map<String, ?> body, int code) throws Exception {
        var response = mvc.perform(post("/api/v1" + path).header("Authorization", token(user)).header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(json.write(body))).andExpect(status().is(code)).andReturn();
        return json.read(response.getResponse().getContentAsString(), JsonNode.class);
    }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private void setTime(String value) { engine.getClock().setCurrentTime(Date.from(Instant.parse(value))); }
}
