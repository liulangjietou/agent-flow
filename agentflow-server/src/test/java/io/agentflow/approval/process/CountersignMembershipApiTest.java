package io.agentflow.approval.process;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.approval.service.TaskAuditPort;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import io.agentflow.notification.InboxRepository;
import io.agentflow.organization.LocalOrganizationDirectory;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 以实际认证、HTTP、引擎和事务验证增减责任，重点覆盖授权、竞态、历史和原请求恢复。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = "agentflow.auth.demo-enabled=true")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class CountersignMembershipApiTest {
    @Autowired MockMvc mvc;
    @MockitoSpyBean AuthService auth;
    @Autowired JsonUtil json;
    @Autowired DefinitionApplicationService definitions;
    @Autowired TaskService tasks;
    @Autowired RuntimeService runtime;
    @Autowired HistoryService history;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.flowable.spring.SpringProcessEngineConfiguration engine;
    @Autowired io.agentflow.calendar.BusinessCalendarRepository calendars;
    @MockitoSpyBean LocalOrganizationDirectory directory;
    @MockitoSpyBean TaskAuditPort audit;
    @MockitoSpyBean InboxRepository inbox;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_COUNTERSIGN_API_URL", "jdbc:h2:mem:countersign-membership-api;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_COUNTERSIGN_API_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_COUNTERSIGN_API_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_COUNTERSIGN_API_PASSWORD", ""));
    }

    @Test
    void additionCreatesRequiredTaskOneVersionNamedAuditAndOnlyNewRecipientNotification() throws Exception {
        String app = submit("role:FINANCE", "ALL", null, Map.of());
        Task source = task(app, "finance");
        var before = view(source, "finance", 200);
        assertThat(before.path("total").asInt()).isEqualTo(2);
        assertThat(before.path("additions").toString()).contains("bob").doesNotContain("finance", "admin");
        assertThat(version(app)).isEqualTo(2);
        assertThat(count("audit_event", app)).isEqualTo(2);
        var result = changed(source, "finance", add("bob", 2), 200);
        assertThat(result.path("applicationVersion").asLong()).isEqualTo(3);
        assertThat(result.path("totalBefore").asInt()).isEqualTo(2);
        assertThat(result.path("totalAfter").asInt()).isEqualTo(3);
        assertThat(result.path("completed").asInt()).isZero();
        assertThat(task(app, "bob").getId()).isEqualTo(result.path("targetTaskId").asText());
        assertThat(view(source, "finance", 200).path("originalMembers")).isEqualTo(before.path("originalMembers"));
        var event = json.read(jdbc.queryForObject("SELECT payload_json FROM audit_event WHERE event_id=?", String.class, result.path("auditEventId").asText()), JsonNode.class);
        assertThat(event.path("action").asText()).isEqualTo("ADD_SIGNER");
        assertThat(event.path("actor").asText()).isEqualTo("finance");
        assertThat(event.at("/membershipChange/targetTaskId").asText()).isEqualTo(task(app, "bob").getId());
        assertThat(event.at("/membershipChange/executionId").asText()).isEqualTo(before.path("executionId").asText());
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notification_inbox WHERE application_id=? AND kind='TASK_PENDING' ORDER BY recipient_id", String.class, app))
                .containsExactly("admin", "bob", "finance");
        String businessNo = jdbc.queryForObject("SELECT business_no FROM approval_application WHERE id=?", String.class, app);
        var handled = read("/api/v1/workspace/handled?action=ADD_SIGNER&q=" + businessNo, "finance", 200).path("items");
        assertThat(handled).hasSize(1);
        assertThat(handled.get(0).path("targetUser").asText()).isEqualTo("bob");
        assertThat(read("/api/v1/workspace/handled?action=ADD_SIGNER&q=" + businessNo, "bob", 200).path("items")).isEmpty();
        act(source, "finance", "APPROVE", 3, null, 200);
        act(task(app, "admin"), "admin", "APPROVE", 4, null, 200);
        assertThat(pending(app)).extracting(Task::getAssignee).containsExactly("bob");
        act(task(app, "bob"), "bob", "APPROVE", 5, null, 200);
        assertThat(applicationStatus(app)).isEqualTo("APPROVED");
        var events = read("/api/v1/applications/" + app + "/audit?action=ADD_SIGNER", "finance", 200).path("items");
        var membership = java.util.stream.StreamSupport.stream(events.spliterator(), false)
                .filter(item -> "ADD_SIGNER".equals(item.path("action").asText())).findFirst().orElseThrow();
        assertThat(membership.path("membershipChange")).isEqualTo(event.path("membershipChange"));
        assertThat(events).hasSize(1);
        assertThat(read("/api/v1/operations/audit?applicationId=" + app + "&action=ADD_SIGNER", "admin", 200).path("items")).hasSize(1);
    }

    @Test
    void removalKeepsCompletedOpinionAndReaddingUsesNewTaskAndNotification() throws Exception {
        String app = submit(); Task finance = task(app, "finance"), admin = task(app, "admin");
        act(finance, "finance", "APPROVE", 2, null, 200);
        var opinion = history.createHistoricTaskInstanceQuery().taskId(finance.getId()).singleResult();
        var added = changed(admin, "admin", add("bob", 3), 200);
        String removedId = added.path("targetTaskId").asText();
        var removed = changed(admin, "admin", remove(removedId, 4), 200);
        assertThat(removed.path("completed").asInt()).isEqualTo(1);
        assertThat(removed.path("totalAfter").asInt()).isEqualTo(2);
        assertThat(history.createHistoricTaskInstanceQuery().taskId(removedId).singleResult().getDeleteReason()).isNotBlank();
        assertThat(history.createHistoricTaskInstanceQuery().taskId(finance.getId()).singleResult().getEndTime()).isEqualTo(opinion.getEndTime());
        assertThat(tasks.getTaskComments(finance.getId())).hasSize(1);
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notification_inbox WHERE application_id=? AND kind='TASK_COUNTERSIGN_REMOVED'", String.class, app)).containsExactly("bob");
        String businessNo = jdbc.queryForObject("SELECT business_no FROM approval_application WHERE id=?", String.class, app);
        assertThat(read("/api/v1/workspace/handled?action=REMOVE_SIGNER&q=" + businessNo, "admin", 200).path("items")).hasSize(1);
        var again = changed(admin, "admin", add("bob", 5), 200);
        assertThat(again.path("targetTaskId").asText()).isNotEqualTo(removedId);
        assertThat(view(admin, "admin", 200).path("completedUsers").toString()).isEqualTo("[\"finance\"]");
        act(admin, "admin", "APPROVE", 6, null, 200);
        assertThat(applicationStatus(app)).isEqualTo("IN_APPROVAL");
    }

    @Test
    void administratorOutsiderApplicantAndForeignTenantCannotChangeSomeoneElsesTask() throws Exception {
        String app = submit("user:finance", "ALL", null, Map.of()); Task source = task(app, "finance");
        for (String user : List.of("admin", "alice", "bob", "cashier")) {
            view(source, user, 403); changed(source, user, add("manager", 2), 403);
        }
        doReturn(new Actor("foreign", "finance", Set.of("APPROVER", "ADMIN"))).when(auth).authenticate("foreign-membership");
        mvc.perform(get(path(source, "countersign-members")).header("Authorization", "Bearer foreign-membership")).andExpect(status().isNotFound());
        mvc.perform(post(path(source, "countersign-changes")).header("Authorization", "Bearer foreign-membership")
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(json.write(add("manager", 2))))
                .andExpect(status().isNotFound());
        assertThat(version(app)).isEqualTo(2); assertThat(pending(app)).hasSize(1);
    }

    @Test
    void inputRejectsUnknownBindingsAmbiguousTargetsBlankReasonAndQueryOverrides() throws Exception {
        String app = submit(); Task source = task(app, "finance");
        for (String body : List.of(
                "{\"action\":\"ADD\",\"targetUser\":\"bob\",\"reason\":\"\",\"expectedVersion\":2}",
                "{\"action\":\"ADD\",\"targetUser\":\"bob\",\"targetTaskId\":\"x\",\"reason\":\"a\",\"expectedVersion\":2}",
                "{\"action\":\"REMOVE\",\"targetUser\":\"bob\",\"reason\":\"a\",\"expectedVersion\":2}",
                "{\"action\":\"ADD\",\"targetUser\":\"bob\",\"reason\":\"a\",\"expectedVersion\":0}",
                "{\"action\":\"ADD\",\"targetUser\":\"bob\",\"reason\":\"a\",\"expectedVersion\":2,\"applicationId\":\"x\"}",
                "{\"action\":\"APPROVE\",\"reason\":\"a\",\"expectedVersion\":2}")) {
            send(path(source, "countersign-changes"), "finance", body, UUID.randomUUID().toString(), 400);
        }
        send(path(source, "countersign-changes") + "?tenantId=demo", "finance", json.write(add("bob", 2)), UUID.randomUUID().toString(), 400);
        read(path(source, "countersign-members") + "?roundNo=1", "finance", 400);
        assertThat(version(app)).isEqualTo(2); assertThat(pending(app)).hasSize(2);
    }

    @Test
    void directoryEligibilityIsRecheckedAfterSelectionAndBeforeReceiptReplay() throws Exception {
        String app = submit(); Task source = task(app, "finance");
        view(source, "finance", 200);
        doReturn(List.of("finance", "admin")).when(directory).approvers("demo");
        changed(source, "finance", add("bob", 2), 422);
        String key = UUID.randomUUID().toString();
        send(path(source, "countersign-changes"), "finance", json.write(remove(task(app, "admin").getId(), 2)), key, 200);
        doReturn(false).when(directory).eligible("demo", "finance");
        send(path(source, "countersign-changes"), "finance", json.write(remove(taskIdFromHistory(app, "admin"), 2)), key, 403);
        assertThat(version(app)).isEqualTo(3); assertThat(pending(app)).hasSize(1);
    }

    @Test
    void staleVersionAndDuplicateResponsibilityDoNotChangeEngineOrApplication() throws Exception {
        String app = submit(); Task source = task(app, "finance");
        changed(source, "finance", add("bob", 1), 409);
        changed(source, "finance", add("admin", 2), 409);
        changed(source, "finance", add("finance", 2), 409);
        changed(source, "finance", remove(source.getId(), 2), 409);
        changed(source, "finance", remove("unknown", 2), 409);
        assertThat(version(app)).isEqualTo(2); assertThat(pending(app)).hasSize(2);
        assertThat(count("audit_event", app)).isEqualTo(2);
    }

    @Test
    void delegatedActorAndDelegatedRemovalTargetMustResolveAssistanceFirst() throws Exception {
        String app = submit(); Task source = task(app, "finance"), other = task(app, "admin");
        act(source, "finance", "DELEGATE", 2, "bob", 200);
        var view = view(source, "bob", 200);
        assertThat(view.path("canChange").asBoolean()).isFalse();
        assertThat(view.path("canAdd").asBoolean()).isFalse();
        assertThat(view.path("additions")).isEmpty();
        changed(source, "bob", add("manager", 3), 409);
        changed(other, "admin", remove(source.getId(), 3), 409);
        act(source, "bob", "RESOLVE", 3, null, 200);
        changed(source, "finance", add("manager", 4), 200);
    }

    @Test
    void successfulOriginalReceiptReplaysAfterSourceCompletionWithoutRepeatedEffects() throws Exception {
        String app = submit(); Task source = task(app, "finance"); String key = UUID.randomUUID().toString();
        String body = json.write(add("bob", 2));
        var first = send(path(source, "countersign-changes"), "finance", body, key, 200);
        act(source, "finance", "APPROVE", 3, null, 200);
        var replay = send(path(source, "countersign-changes"), "finance", body, key, 200);
        assertThat(replay.getResponse().getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(replay.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        send(path(source, "countersign-changes"), "finance", json.write(add("manager", 2)), key, 409);
        assertThat(version(app)).isEqualTo(4);
        assertThat(pending(app)).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action='ADD_SIGNER'", Integer.class, app)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADD", "REMOVE"})
    void auditFailureRollsBackActualEngineTasksHistoryVersionAndIdempotency(String action) throws Exception {
        String app = submit(); Task source = task(app, "finance"); var before = view(source, "finance", 200);
        long historyCount = history.createHistoricTaskInstanceQuery().processInstanceId(source.getProcessInstanceId()).count();
        var body = action.equals("ADD") ? add("bob", 2) : remove(task(app, "admin").getId(), 2);
        doAnswer(call -> { call.callRealMethod(); throw new DomainException("DEPENDENCY_UNAVAILABLE", "Audit failed after insert"); }).when(audit).record(any());
        changed(source, "finance", body, 503);
        assertThat(view(source, "finance", 200)).isEqualTo(before);
        assertThat(history.createHistoricTaskInstanceQuery().processInstanceId(source.getProcessInstanceId()).count()).isEqualTo(historyCount);
        assertThat(count("audit_event", app)).isEqualTo(2); assertThat(count("notification_inbox", app)).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADD", "REMOVE"})
    void notificationFailureRollsBackAuditAndBothMembershipDirections(String action) throws Exception {
        String app = submit(); Task source = task(app, "finance"); var before = view(source, "finance", 200);
        var body = action.equals("ADD") ? add("bob", 2) : remove(task(app, "admin").getId(), 2);
        String key = UUID.randomUUID().toString();
        doAnswer(call -> { call.callRealMethod(); throw new DomainException("DEPENDENCY_UNAVAILABLE", "Notification failed after insert"); }).when(inbox).append(anyString(), any());
        send(path(source, "countersign-changes"), "finance", json.write(body), key, 503);
        assertThat(view(source, "finance", 200)).isEqualTo(before);
        assertThat(count("audit_event", app)).isEqualTo(2); assertThat(count("notification_inbox", app)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_idempotency WHERE tenant_id='demo' AND idempotency_key=?", Integer.class, key)).isZero();
    }

    @Test
    void mismatchedRoundOrDefinitionAndSingleTaskFailClosed() throws Exception {
        String app = submit(); Task source = task(app, "finance");
        runtime.setVariable(source.getProcessInstanceId(), "roundNo", 2);
        view(source, "finance", 409); changed(source, "finance", add("bob", 2), 409);
        runtime.setVariable(source.getProcessInstanceId(), "roundNo", 1);
        String definition = jdbc.queryForObject("SELECT runtime_definition_id FROM approval_application WHERE id=?", String.class, app);
        jdbc.update("UPDATE approval_application SET runtime_definition_id='wrong-definition' WHERE id=?", app);
        changed(source, "finance", add("bob", 2), 409);
        jdbc.update("UPDATE approval_application SET runtime_definition_id=? WHERE id=?", definition, app);
        String single = submit("user:finance", "SINGLE", null, Map.of());
        changed(task(single, "finance"), "finance", add("bob", 2), 409);
        assertThat(version(app)).isEqualTo(2);
    }

    @Test
    void twoConcurrentAdditionsWithSameVersionHaveOneAtomicWinner() throws Exception {
        String app = submit(); Task source = task(app, "finance");
        var ready = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { ready.await(); return sendAny(source, "finance", add("bob", 2)); });
            var second = pool.submit(() -> { ready.await(); return sendAny(source, "finance", add("manager", 2)); });
            ready.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        } finally { pool.shutdownNow(); }
        assertThat(version(app)).isEqualTo(3); assertThat(pending(app)).hasSize(3);
        assertThat(count("audit_event", app)).isEqualTo(3); assertThat(count("notification_inbox", app)).isEqualTo(4);
    }

    @Test
    void approvalCompetingWithAdditionCannotCommitTwoChangesAtOneVersion() throws Exception {
        String app = submit(); Task source = task(app, "finance"), other = task(app, "admin");
        var ready = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var add = pool.submit(() -> { ready.await(); return sendAny(source, "finance", add("bob", 2)); });
            var approve = pool.submit(() -> { ready.await(); return mvc.perform(post(path(other, "actions"))
                    .header("Authorization", token("admin")).header("Idempotency-Key", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("action", "APPROVE", "expectedVersion", 2))))
                    .andReturn().getResponse().getStatus(); });
            ready.countDown();
            assertThat(List.of(add.get(20, TimeUnit.SECONDS), approve.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        } finally { pool.shutdownNow(); }
        assertThat(version(app)).isEqualTo(3); assertThat(count("audit_event", app)).isEqualTo(3);
        assertThat(view(source, "finance", 200).path("total").asInt()).isIn(2, 3);
    }

    @Test
    void addedMemberGetsOnlyCurrentNodeFieldPermissionsAndNoDraftOrOtherRoundGrant() throws Exception {
        var schema = new FormSchema(1, List.of(
                new FormSchema.Field("secret", "敏感", FormSchema.FieldType.TEXT, false, null, null, null, null, null, null, null, true, Map.of("review", FieldVisibility.MASKED)),
                new FormSchema.Field("hidden", "隐藏", FormSchema.FieldType.TEXT, false, null, null, null, null, null, null, null, false, Map.of("review", FieldVisibility.HIDDEN))));
        String app = submit("role:FINANCE", "ALL", schema, Map.of("secret", "private-value", "hidden", "hidden-value"));
        Task source = task(app, "finance");
        read("/api/v1/applications/" + app, "bob", 404);
        changed(source, "finance", add("bob", 2), 200);
        for (String user : List.of("bob", "admin")) {
            var detail = read("/api/v1/applications/" + app, user, 200);
            assertThat(detail.at("/payload/secret").asText()).isEqualTo("已脱敏");
            assertThat(detail.toString()).doesNotContain("private-value", "hidden-value");
        }
        act(source, "finance", "RETURN", 3, null, 200);
        var returned = read("/api/v1/applications/" + app, "bob", 200);
        assertThat(returned.toString()).doesNotContain("private-value", "hidden-value");
        send("/api/v1/applications/" + app + "/submit", "alice", "{\"expectedVersion\":4}", UUID.randomUUID().toString(), 200);
        view(task(app, "finance"), "bob", 403);
        assertThat(pending(app)).extracting(Task::getAssignee).doesNotContain("bob");
    }

    @Test
    void addedTaskStartsItsOwnDeadlineWhileExistingDeadlineAndCalendarSnapshotStayFixed() throws Exception {
        var rules = new io.agentflow.calendar.CalendarRules("UTC", Map.of(java.time.DayOfWeek.TUESDAY,
                List.of(new io.agentflow.calendar.CalendarRules.Period("09:00", "17:00"))), List.of());
        var calendar = io.agentflow.calendar.BusinessCalendar.create("demo", "membership-calendar-" + UUID.randomUUID(), "会签工时", rules, "admin", java.time.Instant.now());
        calendars.create(calendar);
        try {
            engine.getClock().setCurrentTime(java.util.Date.from(java.time.Instant.parse("2026-09-29T09:00:00Z")));
            String app = submit("role:FINANCE", "ALL", null, Map.of(), Map.of("deadlineCalendarId", calendar.id().toString(), "deadlineCalendarRevision", "1", "deadlineWorkingMinutes", "60"));
            Task source = task(app, "finance");
            assertThat(source.getDueDate()).isEqualTo(java.util.Date.from(java.time.Instant.parse("2026-09-29T10:00:00Z")));
            engine.getClock().setCurrentTime(java.util.Date.from(java.time.Instant.parse("2026-09-29T11:00:00Z")));
            changed(source, "finance", add("bob", 2), 200);
            assertThat(task(app, "finance").getDueDate()).isEqualTo(source.getDueDate());
            assertThat(task(app, "admin").getDueDate()).isEqualTo(source.getDueDate());
            assertThat(task(app, "bob").getDueDate()).isEqualTo(java.util.Date.from(java.time.Instant.parse("2026-09-29T12:00:00Z")));
            assertThat(tasks.getVariableLocal(task(app, "bob").getId(), "agentflowDeadlineCalendarRevision")).isEqualTo(1L);
        } finally { engine.getClock().reset(); }
    }

    private String submit() throws Exception { return submit("role:FINANCE", "ALL", null, Map.of()); }
    private String submit(String rule, String mode, FormSchema schema, Map<String, Object> payload) throws Exception {
        return submit(rule, mode, schema, payload, Map.of());
    }
    private String submit(String rule, String mode, FormSchema schema, Map<String, Object> payload, Map<String, String> deadline) throws Exception {
        String key = "membership-api-" + UUID.randomUUID();
        var properties = new java.util.LinkedHashMap<>(deadline); properties.put("assigneeRule", rule); properties.put("approvalMode", mode);
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("review", "财务会签", NodeType.USER_TASK, properties),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("begin", "start", "review", ""), new Edge("finish", "review", "end", "")));
        var definition = definitions.create("demo", key, "会签公开办理验收", graph, schema, null);
        definitions.publish(new Actor("demo", "admin", Set.of("ADMIN")), definition.id(), 0, "验证增减责任");
        var created = send("/api/v1/applications", "alice", json.write(Map.of("businessNo", key, "processKey", key, "definitionVersion", 1, "title", "会签增减", "payload", payload)), UUID.randomUUID().toString(), 201);
        String id = json.read(created.getResponse().getContentAsString(), JsonNode.class).path("id").asText();
        send("/api/v1/applications/" + id + "/submit", "alice", "{\"expectedVersion\":1}", UUID.randomUUID().toString(), 200);
        return id;
    }
    private static Map<String, Object> add(String user, long version) { return Map.of("action", "ADD", "targetUser", user, "reason", "新增必要复核", "expectedVersion", version); }
    private static Map<String, Object> remove(String taskId, long version) { return Map.of("action", "REMOVE", "targetTaskId", taskId, "reason", "职责范围调整", "expectedVersion", version); }
    private static String path(Task task, String suffix) { return "/api/v1/tasks/" + task.getId() + "/" + suffix; }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private List<Task> pending(String app) { return tasks.createTaskQuery().processVariableValueEquals("applicationId", app).list(); }
    private Task task(String app, String user) { return pending(app).stream().filter(task -> user.equals(task.getAssignee())).findFirst().orElseThrow(); }
    private String taskIdFromHistory(String app, String user) { return history.createHistoricTaskInstanceQuery().processVariableValueEquals("applicationId", app).taskAssignee(user).singleResult().getId(); }
    private long version(String app) { return jdbc.queryForObject("SELECT version FROM approval_application WHERE id=?", Long.class, app); }
    private String applicationStatus(String app) { return jdbc.queryForObject("SELECT status FROM approval_application WHERE id=?", String.class, app); }
    private int count(String table, String app) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE application_id=?", Integer.class, app); }
    private JsonNode view(Task task, String user, int status) throws Exception { return read(path(task, "countersign-members"), user, status); }
    private JsonNode read(String path, String user, int expected) throws Exception {
        return json.read(mvc.perform(get(path).header("Authorization", token(user))).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
    private JsonNode changed(Task task, String user, Map<String, Object> input, int expected) throws Exception {
        return json.read(send(path(task, "countersign-changes"), user, json.write(input), UUID.randomUUID().toString(), expected).getResponse().getContentAsString(), JsonNode.class);
    }
    private MvcResult send(String path, String user, String body, String key, int expected) throws Exception {
        return mvc.perform(post(path).header("Authorization", token(user)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(expected)).andReturn();
    }
    private int sendAny(Task task, String user, Map<String, Object> body) throws Exception {
        return mvc.perform(post(path(task, "countersign-changes")).header("Authorization", token(user)).header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(json.write(body))).andReturn().getResponse().getStatus();
    }
    private void act(Task task, String user, String action, long version, String target, int expected) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("action", action); body.put("expectedVersion", version); body.put("comment", "保留原处理意见");
        if (target != null) body.put("targetUser", target);
        send(path(task, "actions"), user, json.write(body), UUID.randomUUID().toString(), expected);
    }
}
