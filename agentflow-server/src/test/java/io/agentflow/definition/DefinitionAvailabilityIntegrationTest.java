package io.agentflow.definition;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.calendar.BusinessCalendar;
import io.agentflow.calendar.BusinessCalendarRepository;
import io.agentflow.calendar.CalendarRules;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.notification.NotificationTexts;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 真实数据库验证停用恢复、并发顺序、事务回滚、权限和运行实例不受影响。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = "agentflow.auth.demo-enabled=true")
@AutoConfigureMockMvc
class DefinitionAvailabilityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired DefinitionApplicationService definitions;
    @Autowired BusinessCalendarRepository calendars;
    @MockitoSpyBean DefinitionDraftRepository repository;
    @Autowired DefinitionAvailabilityService availability;
    @Autowired RepositoryService engine;
    @Autowired TaskService tasks;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean JdbcDefinitionAvailabilityRepository history;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("agentflow.availability-test.jdbc-url", "jdbc:h2:mem:definition-availability;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"));
        registry.add("spring.datasource.driver-class-name", () -> System.getProperty("agentflow.availability-test.jdbc-driver", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getProperty("agentflow.availability-test.jdbc-user", "sa"));
        registry.add("spring.datasource.password", () -> System.getProperty("agentflow.availability-test.jdbc-password", ""));
    }

    @Test
    void immutablePublishedVersionRetainsHistoryAndSameKeyReplaysOnce() throws Exception {
        var definition = published(); String key = UUID.randomUUID().toString();
        var first = change(definition, "admin", 1, false, "暂停新发起", key);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(tree(first).path("startEnabled").asBoolean()).isFalse();
        assertThat(tree(first).path("status").asText()).isEqualTo("PUBLISHED");
        var replay = change(definition, "admin", 1, false, "暂停新发起", key);
        assertThat(tree(replay)).isEqualTo(tree(first));
        assertThat(replay.getResponse().getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(change(definition, "admin", 1, true, "改换请求", key).getResponse().getStatus()).isEqualTo(409);
        assertThat(change(definition, "admin", 1, true, "过期页面", UUID.randomUUID().toString()).getResponse().getStatus()).isEqualTo(409);
        assertThat(change(definition, "admin", 2, true, "恢复新发起", UUID.randomUUID().toString()).getResponse().getStatus()).isEqualTo(200);
        assertThat(tree(change(definition, "admin", 1, false, "暂停新发起", key))).isEqualTo(tree(first));
        var saved = repository.findById("demo", definition.id()).orElseThrow();
        assertThat(saved.startEnabled()).isTrue(); assertThat(saved.revision()).isEqualTo(3);
        assertThat(saved.version()).isEqualTo(1); assertThat(saved.graph()).isEqualTo(definition.graph());
        assertThat(repository.nextVersion("demo", definition.key())).isEqualTo(2);
        assertThat(engine.createProcessDefinitionQuery().processDefinitionKey(definition.key()).count()).isEqualTo(1);
        var rows = history.history("demo", definition.id(), Long.MAX_VALUE, 10);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).revision()).isEqualTo(3);
        assertThat(rows.get(0).changedBy()).isEqualTo("admin");
        assertThat(rows.get(0).authorizedRole()).isEqualTo("ADMIN");
        mvc.perform(get(path(definition) + "/availability-history?limit=1").header("Authorization", token("admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("items.length()").value(1))
                .andExpect(jsonPath("nextBeforeRevision").value(3));
        mvc.perform(get(path(definition) + "/availability-history?limit=1&beforeRevision=3").header("Authorization", token("admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("items[0].revision").value(2))
                .andExpect(jsonPath("nextBeforeRevision").doesNotExist());
        mvc.perform(get(path(definition)).header("Authorization", token("alice")))
                .andExpect(status().isOk()).andExpect(jsonPath("version").value(1));
    }

    @Test
    void availabilityAndCopyKeepParallelGraphNotificationsAndFixedCalendarRevision() throws Exception {
        var calendar = BusinessCalendar.create("demo", "availability-" + UUID.randomUUID(), "原工作日历",
                new CalendarRules("UTC", Map.of(DayOfWeek.MONDAY,
                        List.of(new CalendarRules.Period("09:00", "18:00"))), List.of()), "admin", Instant.now());
        calendars.create(calendar);
        var original = graph();
        var graph = new Graph(List.of(original.nodes().get(0),
                new Node("fork", "同时审批", NodeType.PARALLEL_GATEWAY, Map.of()),
                new Node("approve", "固定期限审批", NodeType.USER_TASK, Map.of(
                        "assigneeRule", "role:MANAGER", "deadlineCalendarId", calendar.id().toString(),
                        "deadlineCalendarRevision", "1", "deadlineWorkingMinutes", "480",
                        "businessTag", "保留原属性")),
                new Node("finance", "财务复核", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE")),
                new Node("join", "全部完成", NodeType.PARALLEL_GATEWAY, Map.of()), original.nodes().get(2)),
                List.of(new Edge("first", "start", "fork", ""), new Edge("manager", "fork", "approve", ""),
                        new Edge("finance-route", "fork", "finance", ""), new Edge("manager-done", "approve", "join", ""),
                        new Edge("finance-done", "finance", "join", ""), new Edge("last", "join", "end", "")));
        var notificationTexts = new NotificationTexts("已收到申请", "请补充说明", "已完成审核");
        var draft = definitions.create("demo", "fixed-deadline-" + UUID.randomUUID(), "期限与治理整合", graph, null, notificationTexts);
        var published = definitions.publish(admin(), draft.id(), 0, "明确使用日历第一版");
        calendars.update(calendar.revise("新工作日历", calendar.rules(), 1, "admin", Instant.now()), 1);

        assertThat(change(published, "admin", 1, false, "暂停新发起", UUID.randomUUID().toString())
                .getResponse().getStatus()).isEqualTo(200);
        var disabled = definitions.get("demo", published.id());
        assertThat(disabled.startEnabled()).isFalse();
        assertThat(disabled.graph()).isEqualTo(graph);
        assertThat(disabled.notificationTexts()).isEqualTo(notificationTexts);
        mvc.perform(get(path(disabled)).header("Authorization", token("admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("startEnabled").value(false))
                .andExpect(jsonPath("notificationTexts.approved").value(notificationTexts.approved()));

        // 复制内容继续引用明确的旧日历修订，不继承原发布版本的治理开关。
        var copy = definitions.create("demo", disabled.key(), disabled.name(), disabled.graph(), disabled.formSchema(), disabled.notificationTexts());
        assertThat(copy.startEnabled()).isTrue();
        var next = definitions.publish(admin(), copy.id(), 0, "从停用版本复制配置");
        assertThat(next.version()).isEqualTo(2);
        assertThat(next.graph()).isEqualTo(graph);
        assertThat(definitions.get("demo", next.id()).notificationTexts()).isEqualTo(notificationTexts);
        assertThat(definitions.get("demo", published.id()).startEnabled()).isFalse();

        assertThat(change(published, "admin", 2, true, "恢复原版本", UUID.randomUUID().toString())
                .getResponse().getStatus()).isEqualTo(200);
        var restored = definitions.get("demo", published.id());
        assertThat(restored.graph()).isEqualTo(graph);
        assertThat(restored.notificationTexts()).isEqualTo(notificationTexts);
        assertThat(restored.version()).isEqualTo(1);
        assertThat(restored.revision()).isEqualTo(3);
        assertThat(history.history("demo", published.id(), Long.MAX_VALUE, 10)).hasSize(2);
        assertThat(history.history("demo", next.id(), Long.MAX_VALUE, 10)).isEmpty();
        assertThat(engine.createProcessDefinitionQuery().processDefinitionKey(published.key()).count()).isEqualTo(2);
    }

    @Test
    void permissionsInputsAndTenantBoundaryRejectWithoutStateChanges() throws Exception {
        var definition = published();
        assertThat(change(definition, "alice", 1, false, "越权", UUID.randomUUID().toString()).getResponse().getStatus()).isEqualTo(403);
        mvc.perform(post(path(definition) + "/availability").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startEnabled\":false,\"expectedRevision\":1,\"reason\":\"无登录\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(path(definition) + "/availability-history").header("Authorization", token("alice"))).andExpect(status().isForbidden());
        for (String reason : List.of(" ", "x".repeat(2001))) {
            assertThat(change(definition, "admin", 1, false, reason, UUID.randomUUID().toString()).getResponse().getStatus()).isEqualTo(422);
        }
        for (String query : List.of("limit=101", "limit=0", "beforeRevision=0", "beforeRevision=x", "tenantId=foreign")) {
            mvc.perform(get(path(definition) + "/availability-history?" + query).header("Authorization", token("admin")))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post(path(definition) + "/availability").header("Authorization", token("admin"))
                        .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":1}"))
                .andExpect(status().isBadRequest());
        var foreign = definitions.create("foreign", "foreign-" + UUID.randomUUID(), "其他租户", graph());
        foreign.publish(0, 1); repository.save(foreign);
        assertThat(change(foreign, "admin", 1, false, "越租户", UUID.randomUUID().toString()).getResponse().getStatus()).isEqualTo(404);
        mvc.perform(get(path(foreign) + "/availability-history").header("Authorization", token("admin"))).andExpect(status().isNotFound());
        assertThat(repository.findById("demo", definition.id()).orElseThrow().revision()).isEqualTo(1);
        assertThat(history.history("demo", definition.id(), Long.MAX_VALUE, 10)).isEmpty();
    }

    @Test
    void processAdministratorIsRecordedAndDraftCannotBeDisabled() {
        var draft = definitions.create("demo", "draft-" + UUID.randomUUID(), "草稿", graph());
        assertThatThrownBy(() -> availability.change(admin(), draft.id(), 0, false, "尚未发布"))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("DEFINITION_NOT_PUBLISHED"));
        var definition = published();
        availability.change(new Actor("demo", "designer", Set.of("PROCESS_ADMIN")), definition.id(), 1, false, "流程管理员停用");
        var event = history.history("demo", definition.id(), Long.MAX_VALUE, 10).get(0);
        assertThat(event.authorizedRole()).isEqualTo("PROCESS_ADMIN");
        assertThat(event.changedBy()).isEqualTo("designer");
        assertThat(history.history("foreign", definition.id(), Long.MAX_VALUE, 10)).isEmpty();
    }

    @Test
    void failedHistoryWriteRollsBackVersionAndRetrySucceeds() {
        var definition = published();
        doThrow(new IllegalStateException("History storage unavailable")).when(history).append(any());
        try {
            assertThatThrownBy(() -> availability.change(admin(), definition.id(), 1, false, "事务回滚"))
                    .isInstanceOf(IllegalStateException.class);
        } finally { doCallRealMethod().when(history).append(any()); }
        var saved = repository.findById("demo", definition.id()).orElseThrow();
        assertThat(saved.startEnabled()).isTrue(); assertThat(saved.revision()).isEqualTo(1);
        assertThat(history.history("demo", definition.id(), Long.MAX_VALUE, 10)).isEmpty();
        availability.change(admin(), definition.id(), 1, false, "恢复后重试");
        assertThat(history.history("demo", definition.id(), Long.MAX_VALUE, 10)).hasSize(1);
    }

    @Test
    void concurrentDifferentRequestsHaveExactlyOneWinner() throws Exception {
        var definition = published(); var pool = Executors.newFixedThreadPool(2); var ready = new CountDownLatch(1);
        try {
            var requests = java.util.stream.IntStream.range(0, 2).mapToObj(index -> pool.submit(() -> {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                return change(definition, "admin", 1, false, "并发停用", UUID.randomUUID().toString()).getResponse().getStatus();
            })).toList();
            ready.countDown();
            assertThat(List.of(requests.get(0).get(15, TimeUnit.SECONDS), requests.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        } finally { pool.shutdownNow(); }
        assertThat(history.history("demo", definition.id(), Long.MAX_VALUE, 10)).hasSize(1);
        assertThat(repository.findById("demo", definition.id()).orElseThrow().revision()).isEqualTo(2);
    }

    @Test
    void disabledVersionRemainsVisibleButNewDraftCreationIsRejected() throws Exception {
        var definition = published(); availability.change(admin(), definition.id(), 1, false, "停用发起");
        assertThat(createApplication(definition, "disabled-" + UUID.randomUUID()).getResponse().getStatus()).isEqualTo(409);
        mvc.perform(get(path(definition)).header("Authorization", token("alice"))).andExpect(status().isOk())
                .andExpect(jsonPath("startEnabled").value(false)).andExpect(jsonPath("status").value("PUBLISHED"));
        String search = "/api/v1/process-definitions/search?status=PUBLISHED&processKey=" + definition.key();
        mvc.perform(get(search + "&startEnabled=true").header("Authorization", token("alice")))
                .andExpect(status().isOk()).andExpect(jsonPath("items.length()").value(0));
        mvc.perform(get(search + "&startEnabled=false").header("Authorization", token("admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("items[0].startEnabled").value(false));
        availability.change(admin(), definition.id(), 2, true, "恢复发起");
        assertThat(createApplication(definition, "enabled-" + UUID.randomUUID()).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void definitionLockOrdersDisableBeforeCompetingNewDraft() throws Exception {
        var definition = published(); var pool = Executors.newSingleThreadExecutor();
        var held = new CountDownLatch(1); var release = new CountDownLatch(1); var readerEntered = new CountDownLatch(1);
        try {
            var writer = pool.submit(() -> new TransactionTemplate(transactions).execute(ignored -> {
                repository.lockPublished("demo", definition.key(), 1).orElseThrow();
                availability.change(admin(), definition.id(), 1, false, "并发关闭发起"); held.countDown();
                try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                return null;
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            doAnswer(invocation -> { readerEntered.countDown(); return invocation.callRealMethod(); })
                    .when(repository).lockPublished("demo", definition.key(), 1);
            var executor = Executors.newSingleThreadExecutor();
            try {
                var created = executor.submit(() -> createApplication(definition, "race-" + UUID.randomUUID()).getResponse().getStatus());
                assertThat(readerEntered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(created.isDone()).isFalse();
                release.countDown(); writer.get(10, TimeUnit.SECONDS);
                assertThat(created.get(10, TimeUnit.SECONDS)).isEqualTo(409);
            } finally { executor.shutdownNow(); }
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test
    void disablingNeverSuspendsOrRebindsAnActiveApproval() throws Exception {
        var definition = published(); var application = tree(createApplication(definition, "active-" + UUID.randomUUID()));
        String id = application.path("id").asText();
        mvc.perform(post("/api/v1/applications/" + id + "/submit").header("Authorization", token("alice"))
                        .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("expectedVersion", application.path("version").asLong())))).andExpect(status().isOk());
        var task = tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult();
        availability.change(admin(), definition.id(), 1, false, "后续发起暂停");
        assertThat(tasks.createTaskQuery().taskId(task.getId()).active().count()).isEqualTo(1);
        mvc.perform(post("/api/v1/tasks/" + task.getId() + "/actions").header("Authorization", token("manager"))
                        .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\",\"expectedVersion\":2}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/applications/" + id).header("Authorization", token("alice")))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("APPROVED"));
        mvc.perform(get("/api/v1/applications/" + id + "/rounds/1/diagram").header("Authorization", token("alice")))
                .andExpect(status().isOk());
        assertThat(engine.createProcessDefinitionQuery().processDefinitionKey(definition.key()).active().count()).isEqualTo(1);
    }

    @Test
    void firstSubmissionOfAnExistingDraftMustRecheckDisabledVersion() throws Exception {
        var definition = published(); var application = tree(createApplication(definition, "before-disable-" + UUID.randomUUID()));
        availability.change(admin(), definition.id(), 1, false, "停止后续首轮发起");
        String id = application.path("id").asText();
        mvc.perform(post("/api/v1/applications/" + id + "/submit").header("Authorization", token("alice"))
                        .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("expectedVersion", application.path("version").asLong()))))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("DEFINITION_DISABLED"));
        mvc.perform(get("/api/v1/applications/" + id).header("Authorization", token("alice")))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("DRAFT"))
                .andExpect(jsonPath("version").value(1));
        assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", id).count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"RETURN", "WITHDRAW"})
    void stoppedVersionBlocksResubmissionUntilRestoredWithoutChangingThePreviousRound(String action) throws Exception {
        var definition = published();
        var draft = tree(createApplication(definition, "resubmit-disabled-" + UUID.randomUUID()));
        String id = draft.path("id").asText();
        submitApplication(id, 1, UUID.randomUUID().toString()).andExpect(status().isOk());
        var firstTask = tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult();
        if (action.equals("RETURN")) {
            mvc.perform(post("/api/v1/tasks/" + firstTask.getId() + "/actions")
                            .header("Authorization", token("manager")).header("Idempotency-Key", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.write(Map.of("action", "RETURN", "expectedVersion", 2, "comment", "补正后重提"))))
                    .andExpect(status().isOk());
        } else {
            mvc.perform(post("/api/v1/applications/" + id + "/withdraw")
                            .header("Authorization", token("alice")).header("Idempotency-Key", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.write(Map.of("expectedVersion", 2, "comment", "撤回后重提"))))
                    .andExpect(status().isOk());
        }
        availability.change(admin(), definition.id(), 1, false, "暂停包括重提在内的后续提交");
        var before = application(id);
        var previousRounds = applicationRounds(id);
        Long auditCount = jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=?", Long.class, id);
        Long notificationCount = jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE application_id=?", Long.class, id);
        String key = UUID.randomUUID().toString();
        long version = before.path("version").asLong();

        submitApplication(id, version, key).andExpect(status().isConflict())
                .andExpect(jsonPath("code").value("DEFINITION_DISABLED"));

        assertThat(application(id)).isEqualTo(before);
        assertThat(applicationRounds(id)).isEqualTo(previousRounds);
        assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", id).count()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=?", Long.class, id)).isEqualTo(auditCount);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE application_id=?", Long.class, id)).isEqualTo(notificationCount);

        availability.change(admin(), definition.id(), 2, true, "恢复原版本重提");
        var resubmitted = submitApplication(id, version, key).andExpect(status().isOk())
                .andExpect(jsonPath("roundNo").value(2)).andExpect(jsonPath("definitionVersion").value(1)).andReturn();
        var nextTask = tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult();
        assertThat(nextTask.getProcessDefinitionId()).isEqualTo(firstTask.getProcessDefinitionId());
        assertThat(nextTask.getProcessInstanceId()).isNotEqualTo(firstTask.getProcessInstanceId());
        assertThat(applicationRounds(id)).hasSize(2);
        assertThat(applicationRounds(id).get(0)).isEqualTo(previousRounds.get(0));

        // 停用不能改写已成功请求的回放，也不能因此启动第三轮。
        availability.change(admin(), definition.id(), 3, false, "再次暂停提交");
        var replay = submitApplication(id, version, key).andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true")).andReturn();
        assertThat(tree(replay)).isEqualTo(tree(resubmitted));
        assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", id).count()).isEqualTo(1);
        assertThat(applicationRounds(id)).hasSize(2);
    }

    private org.springframework.test.web.servlet.ResultActions submitApplication(String id, long version, String key) throws Exception {
        return mvc.perform(post("/api/v1/applications/" + id + "/submit").header("Authorization", token("alice"))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("expectedVersion", version))));
    }

    private JsonNode application(String id) throws Exception {
        return tree(mvc.perform(get("/api/v1/applications/" + id).header("Authorization", token("alice")))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode applicationRounds(String id) throws Exception {
        return tree(mvc.perform(get("/api/v1/applications/" + id + "/rounds").header("Authorization", token("alice")))
                .andExpect(status().isOk()).andReturn());
    }

    @Test
    void sameNamedTenantVersionCannotDisablePreviouslyBoundBundledDraft() throws Exception {
        var response = mvc.perform(post("/api/v1/applications").header("Authorization", token("alice"))
                        .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("businessNo", "bundled-" + UUID.randomUUID(), "processKey", "expense-reimbursement",
                                "definitionVersion", 1, "title", "原内置版本", "payload", Map.of("amount", "100")))))
                .andExpect(status().isCreated()).andReturn();
        String id = tree(response).path("id").asText();
        var tenantDraft = definitions.create("demo", "expense-reimbursement", "同名租户定义", graph());
        var published = definitions.publish(admin(), tenantDraft.id(), 0, "新建租户版本");
        availability.change(admin(), published.id(), 1, false, "仅停用租户版本");
        mvc.perform(post("/api/v1/applications/" + id + "/submit").header("Authorization", token("alice"))
                        .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}")).andExpect(status().isOk());
        var task = tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult();
        var bound = engine.createProcessDefinitionQuery().processDefinitionId(task.getProcessDefinitionId()).singleResult();
        assertThat(bound.getTenantId()).isNullOrEmpty();
        assertThat(repository.findById("demo", published.id()).orElseThrow().startEnabled()).isFalse();
        mvc.perform(post("/api/v1/applications/" + id + "/withdraw").header("Authorization", token("alice"))
                        .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2,\"comment\":\"内置申请撤回\"}"))
                .andExpect(status().isOk());
        submitApplication(id, 3, UUID.randomUUID().toString()).andExpect(status().isOk())
                .andExpect(jsonPath("roundNo").value(2));
        var resubmittedTask = tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult();
        assertThat(resubmittedTask.getProcessDefinitionId()).isEqualTo(bound.getId());
    }

    private DefinitionDraft published() {
        var draft = definitions.create("demo", "availability-" + UUID.randomUUID(), "版本治理验证", graph());
        return definitions.publish(admin(), draft.id(), 0, "初始版本");
    }
    private Graph graph() {
        return new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("approve", "经理审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:MANAGER")),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("first", "start", "approve", ""), new Edge("last", "approve", "end", "")));
    }
    private MvcResult createApplication(DefinitionDraft definition, String number) throws Exception {
        return mvc.perform(post("/api/v1/applications").header("Authorization", token("alice"))
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("businessNo", number, "processKey", definition.key(), "definitionVersion", 1,
                        "title", "治理验证", "payload", Map.of("amount", "100"))))).andReturn();
    }
    private MvcResult change(DefinitionDraft definition, String user, long revision, boolean enabled, String reason, String key) throws Exception {
        return mvc.perform(post(path(definition) + "/availability").header("Authorization", token(user))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("startEnabled", enabled, "expectedRevision", revision, "reason", reason,
                        "changedBy", "forged", "authorizedRole", "forged")))).andReturn();
    }
    private String path(DefinitionDraft definition) { return "/api/v1/process-definitions/" + definition.id(); }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private Actor admin() { return new Actor("demo", "admin", Set.of("ADMIN")); }
    private JsonNode tree(MvcResult result) { return json.read(new String(result.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8), JsonNode.class); }
}
