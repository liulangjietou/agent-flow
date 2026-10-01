package io.agentflow.approval;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.approval.process.FlowableTaskDeadlineReminders;
import io.agentflow.approval.process.InstanceControlService;
import io.agentflow.approval.process.TimerWaitService;
import io.agentflow.auth.AuthService;
import io.agentflow.calendar.BusinessCalendar;
import io.agentflow.calendar.BusinessCalendarRepository;
import io.agentflow.calendar.CalendarRules;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import io.agentflow.notification.InboxMessage;
import io.agentflow.notification.InboxRepository;
import org.flowable.engine.ManagementService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static io.agentflow.definition.DefinitionModels.*;
import static io.agentflow.support.MutationRequests.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 实际实例暂停和 SLA 接续的事务、权限、幂等、轮次及定时器验证；H2/PostgreSQL 共用。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.timers.enabled=false", "agentflow.sla.reminders-enabled=false",
        "agentflow.webhooks.worker-enabled=false", "agentflow.webhooks.targets.pause.tenant-id=demo",
        "agentflow.webhooks.targets.pause.label=合成暂停验收", "agentflow.webhooks.targets.pause.url=https://example.invalid/webhook",
        "agentflow.webhooks.targets.pause.enabled=true", "agentflow.webhooks.targets.pause.signing-secret=whsec_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class InstanceControlIntegrationTest {
    @Autowired MockMvc mvc;
    @MockitoSpyBean AuthService auth;
    @Autowired JsonUtil json;
    @Autowired DefinitionApplicationService definitions;
    @Autowired BusinessCalendarRepository calendars;
    @Autowired RuntimeService runtime;
    @Autowired TaskService tasks;
    @Autowired ManagementService jobs;
    @Autowired TimerWaitService waits;
    @Autowired ProcessEngine engine;
    @Autowired FlowableTaskDeadlineReminders reminders;
    @Autowired JdbcTemplate jdbc;
    @Autowired io.agentflow.approval.repository.SubmissionRoundRepository rounds;
    @MockitoSpyBean InboxRepository inbox;
    @MockitoSpyBean io.agentflow.approval.repository.ApplicationRepository applications;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_INSTANCE_CONTROL_URL", "jdbc:h2:mem:instance-control;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_INSTANCE_CONTROL_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_INSTANCE_CONTROL_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_INSTANCE_CONTROL_PASSWORD", ""));
    }
    @AfterEach
    void resetClock() { engine.getProcessEngineConfiguration().getClock().reset(); }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void administratorTerminatesRunningAndPausedRoundsWithoutInventingAnApproval(boolean paused) throws Exception {
        setTime("2026-09-23T09:00:00Z");
        String id = submitted(calendar(), false);
        String instanceId = instance(id);
        var original = task(id);
        var frozen = rounds.findByRound("demo", UUID.fromString(id), 1).orElseThrow();
        assertThat(read(control(id), "admin").path("canTerminate").asBoolean()).isTrue();
        assertThat(read(control(id), "manager").path("canTerminate").asBoolean()).isFalse();
        if (paused) action(id, "pause", 2, "核对后终止");
        int version = paused ? 3 : 2;
        var receipt = action(id, "terminate", version, "  申请重复，管理员明确终止  ");
        assertThat(receipt.path("state").asText()).isEqualTo("ENDED");
        assertThat(receipt.path("applicationVersion").asLong()).isEqualTo(version + 1);
        assertThat(receipt.path("canTerminate").asBoolean()).isFalse();
        assertThat(runtime.createProcessInstanceQuery().processInstanceId(instanceId).count()).isZero();
        assertThat(tasks.createTaskQuery().taskId(original.getId()).count()).isZero();
        var history = engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(instanceId).singleResult();
        assertThat(history.getEndTime()).isNotNull();
        assertThat(history.getDeleteReason()).isNotBlank().doesNotContain("申请重复");
        assertThat(read("/applications/" + id, "alice").path("status").asText()).isEqualTo("CANCELLED");
        assertThat(read("/applications/" + id, "alice").path("payload").path("amount").asText()).isEqualTo("123.45");
        assertThat(audits(id, "INSTANCE_TERMINATE")).isEqualTo(1);
        assertThat(audits(id, "APPROVE")).isZero();
        var ended = rounds.findByRound("demo", UUID.fromString(id), 1).orElseThrow();
        assertThat(ended.status()).isEqualTo(io.agentflow.approval.model.SubmissionRound.Status.CANCELLED);
        assertThat(ended.completedBy()).isEqualTo("admin");
        assertThat(ended.reason()).isEqualTo("申请重复，管理员明确终止");
        assertThat(ended.payload()).isEqualTo(frozen.payload());
        assertThat(ended.initiatorContext()).isEqualTo(frozen.initiatorContext());
        assertThat(engine.getHistoryService().createHistoricTaskInstanceQuery().taskId(original.getId()).singleResult().getDeleteReason()).isNotBlank();
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notification_inbox WHERE application_id=? AND kind='APPLICATION_CANCELLED' ORDER BY recipient_id", String.class, id))
                .containsExactly("alice", "manager");
        write(control(id) + "/resume", "admin", Map.of("expectedVersion", version + 1, "reason", "终止后不能恢复"), 404, null);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void terminationNotificationFailureRestoresNativeRoundAuditAndOriginalPause(boolean paused) throws Exception {
        String id = submitted(calendar(), false);
        if (paused) action(id, "pause", 2, "原暂停必须保持");
        long version = paused ? 3 : 2;
        var original = task(id);
        var round = rounds.findByRound("demo", UUID.fromString(id), 1).orElseThrow();
        Object pauseAt = runtime.getVariable(instance(id), InstanceControlService.PAUSED_AT);
        int outbox = jdbc.queryForObject("SELECT COUNT(*) FROM webhook_delivery WHERE application_id=?", Integer.class, id);
        failNotification(InboxMessage.Kind.APPLICATION_CANCELLED);
        write(control(id) + "/terminate", "admin", Map.of("expectedVersion", version, "reason", "末尾失败必须整体回滚"), 503, null);
        assertThat(task(id).getId()).isEqualTo(original.getId());
        assertThat(task(id).isSuspended()).isEqualTo(paused);
        assertThat(task(id).getDueDate()).isEqualTo(original.getDueDate());
        assertThat(runtime.getVariable(instance(id), InstanceControlService.PAUSED_AT)).isEqualTo(pauseAt);
        assertThat(rounds.findByRound("demo", UUID.fromString(id), 1).orElseThrow()).isEqualTo(round);
        assertThat(audits(id, "INSTANCE_TERMINATE")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM webhook_delivery WHERE application_id=?", Integer.class, id)).isEqualTo(outbox);
        doCallRealMethod().when(inbox).append(anyString(), any());
        action(id, "terminate", (int) version, "依赖恢复后明确终止");
    }

    @Test
    void terminationRejectsUnauthorizedStaleAndWrongRoundsAndReplaysOnlyForCurrentAdministrator() throws Exception {
        String id = submitted(calendar(), false);
        String path = control(id) + "/terminate";
        var input = Map.of("expectedVersion", 2, "reason", "管理员终止原轮次");
        for (String user : List.of("alice", "manager")) write(path, user, input, 403, null);
        write(path, "admin", Map.of("expectedVersion", 1, "reason", "过期请求"), 409, null);
        write(path, "admin", Map.of("expectedVersion", 2, "reason", " "), 400, null);
        write(path, "admin", Map.of("expectedVersion", 2, "reason", "不能跳转", "nodeId", "end"), 400, null);
        write(path.replace("/1/", "/2/"), "admin", input, 404, null);
        doReturn(new Actor("other", "admin", Set.of("ADMIN"))).when(auth).authenticate("foreign-termination-admin");
        mvc.perform(post("/api/v1" + path).header("Authorization", "Bearer foreign-termination-admin").contentType(MediaType.APPLICATION_JSON)
                .content(json.write(input))).andExpect(status().isNotFound());
        String key = UUID.randomUUID().toString();
        var first = write(path, "admin", input, 200, key);
        assertThat(write(path, "admin", input, 200, key)).isEqualTo(first);
        assertThat(audits(id, "INSTANCE_TERMINATE")).isEqualTo(1);
        String bearer = token("admin");
        doReturn(new Actor("demo", "admin", Set.of("EMPLOYEE"))).when(auth).authenticate(bearer.substring(7));
        mvc.perform(post("/api/v1" + path).header("Authorization", bearer).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json.write(input))).andExpect(status().isForbidden());
    }

    @Test
    void controlledPauseBlocksActionsAndPreservesFrozenCalendarAndFractionalRemainingTime() throws Exception {
        var calendar = calendar(); setTime("2026-09-25T16:30:00Z");
        String id = submitted(calendar, false); var task = task(id); var original = task.getDueDate();
        assertThat(read("/applications/" + id, "manager").path("payload").path("amount").asText()).isEqualTo("123.45");
        setTime("2026-09-25T16:30:12.345Z");
        var paused = action(id, "pause", 2, "维护窗口暂停");
        assertThat(paused.path("state").asText()).isEqualTo("PAUSED");
        assertThat(task(id).isSuspended()).isTrue(); assertThat(task(id).getDueDate()).isEqualTo(original);
        assertThat(read("/applications/" + id, "alice").path("status").asText()).isEqualTo("IN_APPROVAL");
        // 暂停只停止办理；原节点只读与敏感字段约束仍然有效。
        assertThat(read("/applications/" + id, "manager").path("payload").path("amount").asText()).isEqualTo("123.45");
        assertThat(read("/applications/" + id, "admin").path("payload").path("amount").asText()).isNotEqualTo("123.45");
        assertThat(read("/workspace/tasks", "manager").path("items").toString()).doesNotContain(task.getId());
        mvc.perform(post("/api/v1/tasks/" + task.getId() + "/actions").header("Authorization", token("manager"))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("action", "APPROVE", "expectedVersion", 3))))
                .andExpect(status().isNotFound());
        assertThat(reminders.remind(task.getId(), original.toInstant().plusSeconds(1))).isFalse();
        mvc.perform(post("/api/v1/applications/" + id + "/withdraw").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("expectedVersion", 3, "comment", "暂停期间撤回"))))
                .andExpect(status().isConflict());
        calendars.update(calendar.revise("后续日历", new CalendarRules("UTC", Map.of(DayOfWeek.WEDNESDAY,
                List.of(new CalendarRules.Period("12:00", "13:00"))), List.of()), 1, "admin", Instant.now()), 1);
        setTime("2026-09-29T16:30:20.125Z");
        assertThat(action(id, "resume", 3, "维护完成接续").path("state").asText()).isEqualTo("RUNNING");
        assertThat(task(id).getId()).isEqualTo(task.getId()); assertThat(task(id).isSuspended()).isFalse();
        assertThat(task(id).getDueDate().toInstant()).isEqualTo(Instant.parse("2026-09-30T09:30:07.780Z"));
        assertThat(tasks.getVariableLocal(task.getId(), "agentflowDeadlineCalendarRevision")).isEqualTo(1L);
        assertThat(tasks.getVariableLocal(task.getId(), "agentflowDeadlineStartedAt")).isEqualTo("2026-09-25T16:30:00Z");
        assertThat(tasks.getVariableLocal(task.getId(), InstanceControlService.PAUSED_DUE_AT)).isNull();
        assertThat(audits(id, "INSTANCE_PAUSE")).isEqualTo(1); assertThat(audits(id, "INSTANCE_RESUME")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notification_inbox WHERE application_id=? AND kind='APPLICATION_PAUSED' ORDER BY recipient_id", String.class, id))
                .containsExactly("alice", "manager");
        write("/tasks/" + task.getId() + "/actions", "manager", Map.of("action", "APPROVE", "expectedVersion", 4, "comment", "恢复后实际办理"), 200, null);
        assertThat(read(control(id), "admin").path("state").asText()).isEqualTo("ENDED");
        assertThat(read(control(id), "admin").path("canPause").asBoolean()).isFalse();
        assertThat(read("/applications/" + id, "alice").path("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void overdueFactsAndAlreadySentRemindersSurvivePauseAndResume() throws Exception {
        setTime("2026-09-23T09:00:00Z"); String id = submitted(calendar(), false); var task = task(id);
        Instant due = task.getDueDate().toInstant(); assertThat(reminders.remind(task.getId(), due)).isTrue();
        Object reminded = tasks.getVariableLocal(task.getId(), "agentflowDeadlineRemindedAt");
        setTime("2026-09-23T10:15:00Z"); action(id, "pause", 2, "已超时仍需暂停");
        setTime("2026-09-24T09:00:00Z"); action(id, "resume", 3, "恢复原办理");
        assertThat(task(id).getDueDate().toInstant()).isEqualTo(due);
        assertThat(tasks.getVariableLocal(task.getId(), "agentflowDeadlineRemindedAt")).isEqualTo(reminded);
        assertThat(reminders.remind(task.getId(), Instant.parse("2026-09-24T09:00:00Z"))).isFalse();
    }

    @Test
    void nativeTimersKeepTheirOriginalDueAndCannotAdvanceWhilePaused() throws Exception {
        setTime("2026-09-23T09:00:00Z"); String id = submitted(calendar(), true);
        var original = jobs.createTimerJobQuery().processInstanceId(instance(id)).singleResult();
        setTime("2026-09-23T09:00:00.500Z"); action(id, "pause", 2, "暂停原等待");
        assertThat(waits.advance(original.getId(), Instant.parse("2026-09-24T09:00:00Z"))).isFalse();
        var suspended = jobs.createSuspendedJobQuery().processInstanceId(instance(id)).singleResult();
        assertThat(suspended.getDuedate()).isEqualTo(original.getDuedate());
        setTime("2026-09-24T09:00:00Z"); action(id, "resume", 3, "恢复原等待");
        var restored = jobs.createTimerJobQuery().processInstanceId(instance(id)).singleResult();
        assertThat(restored.getId()).isEqualTo(original.getId()); assertThat(restored.getDuedate()).isEqualTo(original.getDuedate());
        assertThat(waits.advance(restored.getId(), Instant.parse("2026-09-24T09:00:00Z"))).isTrue();
        assertThat(read("/applications/" + id, "alice").path("status").asText()).isEqualTo("IN_APPROVAL");
        assertThat(task(id).getTaskDefinitionKey()).isEqualTo("review");
    }

    @Test
    void invalidAuthorityVersionAndRoundCannotMutateOrRecoverAnUncontrolledPause() throws Exception {
        setTime("2026-09-23T09:00:00Z"); String id = submitted(calendar(), false); String path = control(id);
        mvc.perform(get("/api/v1" + path).header("Authorization", token("bob"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1" + path + "?force=true").header("Authorization", token("admin"))).andExpect(status().isBadRequest());
        write(path + "/pause", "manager", Map.of("expectedVersion", 2, "reason", "无权限"), 403, null);
        write(path + "/pause", "admin", Map.of("expectedVersion", 1, "reason", "旧版本"), 409, null);
        write(path + "/pause", "admin", Map.of("expectedVersion", 2, "reason", " "), 400, null);
        write(path + "/pause", "admin", Map.of("expectedVersion", 2, "reason", "非法时间", "pausedAt", "2020-01-01"), 400, null);
        doReturn(new Actor("other", "admin", Set.of("ADMIN"))).when(auth).authenticate("foreign-instance-admin");
        mvc.perform(post("/api/v1" + path + "/pause").header("Authorization", "Bearer foreign-instance-admin").contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("expectedVersion", 2, "reason", "跨租户")))).andExpect(status().isNotFound());
        write(path.replace("/1/", "/2/") + "/pause", "admin", Map.of("expectedVersion", 2, "reason", "错误轮次"), 404, null);
        runtime.suspendProcessInstanceById(instance(id));
        assertThat(read(path, "admin").path("canResume").asBoolean()).isFalse();
        write(path + "/resume", "admin", Map.of("expectedVersion", 2, "reason", "不能重置缺失的暂停依据"), 409, null);
        assertThat(read("/applications/" + id, "alice").path("version").asLong()).isEqualTo(2);
        assertThat(audits(id, "INSTANCE_PAUSE")).isZero();
    }

    @Test
    void notificationFailureRollsBackStateDeadlinesAuditsAndVersionForBothActions() throws Exception {
        setTime("2026-09-23T09:00:00Z"); String id = submitted(calendar(), false); var original = task(id).getDueDate();
        setTime("2026-09-23T09:30:00Z"); failNotification(InboxMessage.Kind.APPLICATION_PAUSED);
        write(control(id) + "/pause", "admin", Map.of("expectedVersion", 2, "reason", "失败回滚"), 503, null);
        assertThat(task(id).isSuspended()).isFalse(); assertThat(audits(id, "INSTANCE_PAUSE")).isZero();
        assertThat(tasks.getVariableLocal(task(id).getId(), InstanceControlService.PAUSED_DUE_AT)).isNull();
        doCallRealMethod().when(inbox).append(anyString(), any()); action(id, "pause", 2, "保存原暂停");
        setTime("2026-09-24T09:00:00Z"); failNotification(InboxMessage.Kind.APPLICATION_RESUMED);
        write(control(id) + "/resume", "admin", Map.of("expectedVersion", 3, "reason", "恢复失败回滚"), 503, null);
        assertThat(task(id).isSuspended()).isTrue(); assertThat(task(id).getDueDate()).isEqualTo(original);
        assertThat(read("/applications/" + id, "alice").path("version").asLong()).isEqualTo(3);
        assertThat(audits(id, "INSTANCE_RESUME")).isZero();
        doCallRealMethod().when(inbox).append(anyString(), any()); action(id, "resume", 3, "恢复依赖后接续");
        assertThat(task(id).getDueDate().toInstant()).isEqualTo(Instant.parse("2026-09-24T09:30:00Z"));
    }

    @Test
    void sameRequestReplaysOnceAndConcurrentIndependentPauseDoesNotDuplicateEffects() throws Exception {
        setTime("2026-09-23T09:00:00Z"); String id = submitted(calendar(), false); String key = UUID.randomUUID().toString();
        var input = Map.of("expectedVersion", 2, "reason", "唯一原请求");
        var first = write(control(id) + "/pause", "admin", input, 200, key);
        assertThat(write(control(id) + "/pause", "admin", input, 200, key)).isEqualTo(first);
        action(id, "resume", 3, "继续审批");
        assertThat(write(control(id) + "/pause", "admin", input, 200, key)).isEqualTo(first);
        assertThat(task(id).isSuspended()).isFalse(); assertThat(audits(id, "INSTANCE_PAUSE")).isEqualTo(1);
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        String bearer = token("admin"); String body = json.write(Map.of("expectedVersion", 4, "reason", "并发暂停"));
        try {
            Callable<Integer> call = () -> { start.await(5, TimeUnit.SECONDS); return mvc.perform(post("/api/v1" + control(id) + "/pause")
                    .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus(); };
            var one = pool.submit(call); var two = pool.submit(call); start.countDown();
            assertThat(List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        } finally { pool.shutdownNow(); }
        assertThat(audits(id, "INSTANCE_PAUSE")).isEqualTo(2);
    }

    @Test
    void reminderWaitsForThePauseTransactionAndDoesNotPublishFromItsPreLockSnapshot() throws Exception {
        setTime("2026-09-23T09:00:00Z"); String id = submitted(calendar(), false); var task = task(id);
        setTime("2026-09-23T09:30:00Z");
        var pausedInsideTransaction = new CountDownLatch(1); var allowCommit = new CountDownLatch(1); var reminderAtLock = new CountDownLatch(1);
        doAnswer(call -> {
            var message = (InboxMessage) call.getArgument(1);
            if (message.applicationId().toString().equals(id) && message.kind() == InboxMessage.Kind.APPLICATION_PAUSED) {
                pausedInsideTransaction.countDown();
                if (!allowCommit.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Pause test release timed out");
            }
            return call.callRealMethod();
        }).when(inbox).append(anyString(), any());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var pause = pool.submit(() -> action(id, "pause", 2, "并发提醒边界"));
            assertThat(pausedInsideTransaction.await(10, TimeUnit.SECONDS)).isTrue();
            io.agentflow.approval.repository.ApplicationRepository repositoryTarget = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(applications);
            doAnswer(call -> { reminderAtLock.countDown(); return call.callRealMethod(); })
                    .when(repositoryTarget).lockById("demo", UUID.fromString(id));
            var reminder = pool.submit(() -> reminders.remind(task.getId(), task.getDueDate().toInstant()));
            assertThat(reminderAtLock.await(10, TimeUnit.SECONDS)).isTrue();
            allowCommit.countDown();
            assertThat(pause.get(15, TimeUnit.SECONDS).path("state").asText()).isEqualTo("PAUSED");
            assertThat(reminder.get(15, TimeUnit.SECONDS)).isFalse();
        } finally { allowCommit.countDown(); pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE application_id=? AND kind='TASK_OVERDUE'", Integer.class, id)).isZero();
        assertThat(tasks.getVariableLocal(task.getId(), "agentflowDeadlineRemindedAt")).isNull();
    }

    @Test
    void everyParallelCountersignTaskResumesItsOwnDeadlineAndUnconfiguredTaskStaysWithoutOne() throws Exception {
        setTime("2026-09-23T09:00:00Z"); String id = submitted(calendar(), false, true);
        var originals = tasks.createTaskQuery().processInstanceId(instance(id)).list();
        assertThat(originals).hasSize(3);
        setTime("2026-09-23T09:15:30.250Z"); action(id, "pause", 2, "并行会签一起暂停");
        assertThat(tasks.createTaskQuery().processInstanceId(instance(id)).list()).allMatch(org.flowable.task.api.Task::isSuspended);
        setTime("2026-09-24T09:00:00.125Z"); action(id, "resume", 3, "接续各自任务");
        var resumed = tasks.createTaskQuery().processInstanceId(instance(id)).list();
        assertThat(resumed).extracting(org.flowable.task.api.Task::getId).containsExactlyInAnyOrderElementsOf(originals.stream().map(org.flowable.task.api.Task::getId).toList());
        assertThat(resumed).allSatisfy(task -> {
            assertThat(task.isSuspended()).isFalse();
            if (task.getTaskDefinitionKey().equals("review")) assertThat(task.getDueDate().toInstant()).isEqualTo(Instant.parse("2026-09-24T09:44:29.875Z"));
            else assertThat(task.getDueDate()).isNull();
        });
    }

    @Test
    void realReadAndWriteResponsesContainEveryRequiredContractField() throws Exception {
        setTime("2026-09-23T09:00:00Z"); String id = submitted(calendar(), false);
        JsonNode schema;
        try (var source = new org.springframework.core.io.ClassPathResource("api/openapi.json").getInputStream()) {
            schema = json.read(new String(source.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), JsonNode.class)
                    .path("components").path("schemas").path("InstanceControlView");
        }
        for (var response : List.of(read(control(id), "admin"), action(id, "pause", 2, "核对暂停回执"), action(id, "resume", 3, "核对恢复回执"))) {
            for (var required : schema.path("required")) assertThat(response.has(required.asText()))
                    .as("required response field %s for %s", required.asText(), response.path("state").asText()).isTrue();
        }
    }

    private void failNotification(InboxMessage.Kind kind) {
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (((InboxMessage) call.getArgument(1)).kind() == kind) throw new DomainException("DEPENDENCY_UNAVAILABLE", "Simulated notification outage");
            return result;
        }).when(inbox).append(anyString(), any());
    }
    private BusinessCalendar calendar() {
        var week = new EnumMap<DayOfWeek, List<CalendarRules.Period>>(DayOfWeek.class);
        for (var day : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) {
            week.put(day, List.of(new CalendarRules.Period("09:00", "17:00")));
        }
        var rules = new CalendarRules("UTC", week, List.of(new CalendarRules.DayOverride(LocalDate.parse("2026-09-28"), List.of(), "假日")));
        var result = BusinessCalendar.create("demo", "pause-" + UUID.randomUUID(), "冻结期限", rules, "admin", Instant.now());
        calendars.create(result); return result;
    }
    private String submitted(BusinessCalendar calendar, boolean timer) throws Exception {
        return submitted(calendar, timer, false);
    }
    private String submitted(BusinessCalendar calendar, boolean timer, boolean parallel) throws Exception {
        var properties = new HashMap<>(Map.of("assigneeRule", "user:manager", "deadlineCalendarId", calendar.id().toString(),
                "deadlineCalendarRevision", "1", "deadlineWorkingMinutes", "60"));
        if (parallel) { properties.put("assigneeRule", "role:FINANCE"); properties.put("approvalMode", "ALL"); }
        var nodes = new ArrayList<>(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("review", "人工审批", NodeType.USER_TASK, properties), new Node("end", "结束", NodeType.END, Map.of())));
        var edges = new ArrayList<Edge>();
        if (parallel) {
            nodes.addAll(List.of(new Node("fork", "并行", NodeType.PARALLEL_GATEWAY, Map.of()),
                    new Node("plain", "未配置期限", NodeType.USER_TASK, Map.of("assigneeRule", "user:manager")),
                    new Node("join", "汇合", NodeType.PARALLEL_GATEWAY, Map.of())));
            for (String pair : List.of("start>fork", "fork>review", "fork>plain", "review>join", "plain>join", "join>end")) {
                var ends = pair.split(">"); edges.add(new Edge("edge" + edges.size(), ends[0], ends[1], ""));
            }
        } else if (timer) {
            nodes.add(new Node("wait", "原等待", NodeType.TIMER_WAIT, Map.of("timerDelaySeconds", "1")));
            edges.add(new Edge("first", "start", "wait", "")); edges.add(new Edge("next", "wait", "review", ""));
        } else edges.add(new Edge("first", "start", "review", ""));
        if (!parallel) edges.add(new Edge("last", "review", "end", ""));
        var field = new FormSchema.Field("amount", "金额", FormSchema.FieldType.NUMBER, false, null, null, null, null, null, null, null,
                true, Map.of("review", FieldVisibility.READ_ONLY));
        String key = "pause-" + UUID.randomUUID();
        var definition = definitions.create("demo", key, "暂停恢复验证", new Graph(nodes, edges), new FormSchema(1, List.of(field)), null);
        definitions.publish(new Actor("demo", "admin", Set.of("ADMIN")), definition.id(), 0, "明确期限");
        String id = write("/applications", "alice", Map.of("businessNo", key, "processKey", key, "definitionVersion", 1,
                "title", "暂停接续", "payload", Map.of("amount", "123.45")), 201, null).path("id").asText();
        write("/applications/" + id + "/submit", "alice", Map.of("expectedVersion", 1), 200, null); return id;
    }
    private JsonNode action(String id, String action, int version, String reason) throws Exception {
        return write(control(id) + "/" + action, "admin", Map.of("expectedVersion", version, "reason", reason), 200, null);
    }
    private JsonNode write(String path, String user, Object body, int expected, String key) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1" + path)
                .header("Idempotency-Key", key == null ? UUID.randomUUID().toString() : key).header("Authorization", token(user))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(body));
        return json.read(mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
    private JsonNode read(String path, String user) throws Exception {
        return json.read(mvc.perform(get("/api/v1" + path).header("Authorization", token(user))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
    private org.flowable.task.api.Task task(String id) { return tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult(); }
    private String instance(String id) { return runtime.createProcessInstanceQuery().variableValueEquals("applicationId", id).singleResult().getId(); }
    private String control(String id) { return "/applications/" + id + "/rounds/1/runtime"; }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private void setTime(String time) { engine.getProcessEngineConfiguration().getClock().setCurrentTime(Date.from(Instant.parse(time))); }
    private int audits(String id, String action) { return jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE application_id=? AND action=?", Integer.class, id, action); }
}
