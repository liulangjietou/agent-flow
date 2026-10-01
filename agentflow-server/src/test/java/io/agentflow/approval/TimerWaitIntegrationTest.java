package io.agentflow.approval;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.approval.process.TimerWaitService;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.notification.InboxMessage;
import io.agentflow.notification.InboxRepository;
import io.agentflow.organization.LocalOrganizationDirectory;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.job.api.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static io.agentflow.definition.DefinitionModels.*;
import static io.agentflow.support.MutationRequests.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实原生定时任务与业务事务、失败原件、暂停和轮次边界；H2/PostgreSQL 共用。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.timers.enabled=false",
        "agentflow.webhooks.worker-enabled=false", "agentflow.webhooks.targets.timer.tenant-id=demo",
        "agentflow.webhooks.targets.timer.label=合成等待验收", "agentflow.webhooks.targets.timer.url=https://example.invalid/webhook",
        "agentflow.webhooks.targets.timer.enabled=true", "agentflow.webhooks.targets.timer.signing-secret=whsec_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class TimerWaitIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @MockitoSpyBean AuthService auth;
    @Autowired DefinitionApplicationService definitions;
    @Autowired TimerWaitService waits;
    @Autowired ManagementService jobs;
    @Autowired RuntimeService runtime;
    @Autowired TaskService tasks;
    @Autowired HistoryService history;
    @Autowired ProcessEngine engine;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean InboxRepository inbox;
    @MockitoSpyBean LocalOrganizationDirectory directory;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_TIMER_WAIT_URL", "jdbc:h2:mem:timer-wait;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_TIMER_WAIT_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_TIMER_WAIT_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_TIMER_WAIT_PASSWORD", ""));
    }

    @BeforeEach
    void isolatedEngineClock() {
        // 原生到期处于真实当前时刻之前，重试接口仍必须自行检查实际时间，测试不依赖 sleep。
        engine.getProcessEngineConfiguration().getClock().setCurrentTime(Date.from(Instant.now().minusSeconds(120)));
    }

    @AfterEach
    void resetEngineClock() { engine.getProcessEngineConfiguration().getClock().reset(); }

    @Test
    void waitsUntilTheOriginalDueTimeThenCreatesTheRealHumanTaskExactlyOnce() throws Exception {
        String id = submitted(graph(true, false)); Job job = timer(id);
        assertThat(tasksFor(id)).isEmpty();
        var before = read(timers(id), "alice");
        assertThat(before.path("items").get(0).path("state").asText()).isEqualTo("WAITING");
        assertThat(before.path("items").get(0).path("canRetry").asBoolean()).isFalse();
        assertThat(waits.advance(job.getId(), job.getDuedate().toInstant().minusMillis(1))).isFalse();
        assertThat(application(id).path("version").asLong()).isEqualTo(2);
        assertThat(waits.advance(job.getId(), job.getDuedate().toInstant())).isTrue();
        assertThat(tasksFor(id)).hasSize(1);
        assertThat(application(id).path("status").asText()).isEqualTo("IN_APPROVAL");
        assertThat(application(id).path("version").asLong()).isEqualTo(3);
        assertThat(waits.advance(job.getId(), Instant.now())).isFalse();
        assertThat(auditCount(id, "TIMER_ELAPSED")).isEqualTo(1); assertThat(auditCount(id, "APPROVE")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_inbox WHERE application_id=? AND kind='TASK_PENDING'", Integer.class, id)).isEqualTo(1);
        approve(id, 3);
        assertThat(application(id).path("status").asText()).isEqualTo("APPROVED");
        assertThat(auditCount(id, "APPROVE")).isEqualTo(1);
    }

    @Test
    void waitAfterHumanDecisionCompletesBusinessRoundAndNotificationWithoutFakeOpinion() throws Exception {
        String id = submitted(graph(false, false)); approve(id, 2); Job job = timer(id);
        assertThat(application(id).path("status").asText()).isEqualTo("IN_APPROVAL");
        assertThat(waits.advance(job.getId(), job.getDuedate().toInstant())).isTrue();
        assertThat(application(id).path("status").asText()).isEqualTo("APPROVED");
        assertThat(auditCount(id, "APPROVE")).isEqualTo(1); assertThat(auditCount(id, "TIMER_ELAPSED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_inbox WHERE application_id=? AND kind='APPLICATION_APPROVED'", Integer.class, id)).isEqualTo(1);
        var rounds = read("/api/v1/applications/" + id + "/rounds", "alice");
        assertThat(rounds.toString()).contains("APPROVED", "system:timer");
        assertThat(history.createHistoricProcessInstanceQuery().processInstanceId(job.getProcessInstanceId()).singleResult().getEndTime()).isNotNull();
        var events = read("/api/v1/applications/" + id + "/audit?action=TIMER_ELAPSED", "alice").path("items");
        assertThat(events.size()).isEqualTo(1); assertThat(events.get(0).path("source").asText()).isEqualTo("APPLICATION_AUDIT");
        assertThat(jdbc.queryForList("SELECT event_type FROM webhook_delivery WHERE application_id=?", String.class, id))
                .containsExactlyInAnyOrder("ApplicationSubmitted", "TaskActionAccepted", "TimerWaitElapsed", "ApplicationApproved");
    }

    @Test
    void parallelWaitCannotReleaseTheOtherHumanBranch() throws Exception {
        String id = submitted(graph(true, true)); Job job = timer(id); String taskId = tasksFor(id).get(0).getId();
        assertThat(waits.advance(job.getId(), job.getDuedate().toInstant())).isTrue();
        assertThat(tasksFor(id)).extracting(org.flowable.task.api.Task::getId).containsExactly(taskId);
        assertThat(application(id).path("status").asText()).isEqualTo("IN_APPROVAL");
        approve(id, 3);
        assertThat(application(id).path("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void withdrawalCancelsTheOldTimerAndResubmissionUsesANewRound() throws Exception {
        String id = submitted(graph(true, false)); Job old = timer(id);
        mvc.perform(post("/api/v1/applications/" + id + "/withdraw").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("expectedVersion", 2, "comment", "撤回原等待")))).andExpect(status().isOk());
        assertThat(waits.advance(old.getId(), Instant.now())).isFalse();
        submit(id, 3); Job current = timer(id);
        assertThat(current.getProcessInstanceId()).isNotEqualTo(old.getProcessInstanceId());
        assertThat(application(id).path("roundNo").asInt()).isEqualTo(2);
        assertThat(read(timers(id), "alice").path("items")).isEmpty();
        assertThat(waits.advance(old.getId(), Instant.now())).isFalse();
        assertThat(waits.advance(current.getId(), Instant.now())).isTrue();
        assertThat(auditCount(id, "TIMER_ELAPSED")).isEqualTo(1);
    }

    @Test
    void suspendedInstancesRemainWaitingEvenThoughTheNativeExecuteApiCanForceThem() throws Exception {
        String id = submitted(graph(true, false)); Job job = timer(id);
        runtime.suspendProcessInstanceById(job.getProcessInstanceId());
        assertThat(waits.advance(job.getId(), Instant.now())).isFalse();
        assertThat(read(timers(id), "admin").path("items").get(0).path("state").asText()).isEqualTo("SUSPENDED");
        assertThat(tasksFor(id)).isEmpty();
        runtime.activateProcessInstanceById(job.getProcessInstanceId());
        Job restored = timer(id); assertThat(restored.getDuedate()).isEqualTo(job.getDuedate());
        assertThat(waits.advance(restored.getId(), Instant.now())).isTrue();
    }

    @Test
    void concurrentDispatchersProduceOneAdvanceAndOneNotification() throws Exception {
        String id = submitted(graph(true, false)); Job job = timer(id);
        var barrier = new CyclicBarrier(2); var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { barrier.await(); return waits.advance(job.getId(), Instant.now()); });
            var second = pool.submit(() -> { barrier.await(); return waits.advance(job.getId(), Instant.now()); });
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        } finally { pool.shutdownNow(); }
        assertThat(tasksFor(id)).hasSize(1); assertThat(auditCount(id, "TIMER_ELAPSED")).isEqualTo(1);
        assertThat(application(id).path("version").asLong()).isEqualTo(3);
    }

    @Test
    void notificationFailureRollsBackTheEngineAndAdminRetriesTheOriginalFailureIdempotently() throws Exception {
        String id = submitted(graph(true, false)); Job job = timer(id);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (((InboxMessage) call.getArgument(1)).kind() == InboxMessage.Kind.TASK_PENDING) {
                throw new DomainException("DEPENDENCY_UNAVAILABLE", "Simulated notification failure");
            }
            return result;
        }).when(inbox).append(anyString(), any(InboxMessage.class));
        Throwable failure = catchThrowable(() -> waits.advance(job.getId(), Instant.now()));
        assertThat(failure).isInstanceOf(RuntimeException.class);
        assertThat(timer(id).getId()).isEqualTo(job.getId()); assertThat(tasksFor(id)).isEmpty();
        assertThat(application(id).path("version").asLong()).isEqualTo(2); assertThat(auditCount(id, "TIMER_ELAPSED")).isZero();
        waits.failed(job.getId(), (RuntimeException) failure);
        assertThat(jobs.createTimerJobQuery().jobId(job.getId()).count()).isZero();
        var entry = read(timers(id), "admin").path("items").get(0);
        assertThat(entry.path("state").asText()).isEqualTo("FAILED");
        assertThat(entry.path("errorCode").asText()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(entry.path("executionId").asText()).isEqualTo(job.getExecutionId());
        assertThat(Instant.parse(entry.path("dueAt").asText())).isEqualTo(job.getDuedate().toInstant());
        assertThat(entry.path("canRetry").asBoolean()).isTrue();
        assertThat(read(timers(id), "alice").path("items").get(0).path("canRetry").asBoolean()).isFalse();
        String path = timers(id) + "/" + entry.path("jobId").asText() + "/retry";
        String body = json.write(Map.of("expectedVersion", 3, "reason", "通知服务恢复，重试原等待"));
        mvc.perform(post(path).header("Authorization", token("alice")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        doReturn(new Actor("other", "admin", Set.of("ADMIN"))).when(auth).authenticate("other-timer-admin");
        mvc.perform(post(path).header("Authorization", "Bearer other-timer-admin").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        mvc.perform(post(path).header("Authorization", token("admin")).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("expectedVersion", 2, "reason", "旧版本重试")))).andExpect(status().isConflict());
        // 故障尚未修复时再次执行也必须回滚，原失败任务与版本保持不变。
        mvc.perform(post(path).header("Authorization", token("admin")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isServiceUnavailable());
        assertThat(jobs.createDeadLetterJobQuery().jobId(entry.path("jobId").asText()).count()).isEqualTo(1);
        assertThat(application(id).path("version").asLong()).isEqualTo(3);
        assertThat(tasksFor(id)).isEmpty(); assertThat(auditCount(id, "TIMER_RETRY")).isZero();
        doCallRealMethod().when(inbox).append(anyString(), any(InboxMessage.class));
        String key = UUID.randomUUID().toString();
        String receipt = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Idempotency-Key", key).header("Authorization", token("admin"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String replay = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Idempotency-Key", key).header("Authorization", token("admin"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(receipt); assertThat(tasksFor(id)).hasSize(1);
        assertThat(auditCount(id, "TIMER_FAILED")).isEqualTo(1); assertThat(auditCount(id, "TIMER_RETRY")).isEqualTo(1);
        assertThat(auditCount(id, "APPROVE")).isZero();
        assertThat(jdbc.queryForList("SELECT event_type FROM webhook_delivery WHERE application_id=?", String.class, id))
                .containsExactlyInAnyOrder("ApplicationSubmitted", "TimerWaitFailed", "TimerWaitRetried");
        doReturn(new Actor("demo", "admin", Set.of("EMPLOYEE"))).when(auth).authenticate("former-timer-admin");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Idempotency-Key", key)
                .header("Authorization", "Bearer former-timer-admin").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
    }

    @Test
    void unavailableDownstreamApproversKeepTheOriginalTimerAndCanRecoverAfterDirectoryRepair() throws Exception {
        var base = graph(true, false); var nodes = new ArrayList<>(base.nodes());
        nodes.replaceAll(n -> n.type() == NodeType.USER_TASK ? new Node(n.id(), n.name(), n.type(), Map.of("assigneeRule", "role:APPROVER", "approvalMode", "ALL")) : n);
        String id = submitted(new Graph(nodes, base.edges())); Job job = timer(id);
        doReturn(List.of()).when(directory).members("demo", Set.of(), Set.of("APPROVER"));
        Throwable failure = catchThrowable(() -> waits.advance(job.getId(), Instant.now()));
        assertThat(failure).isInstanceOf(RuntimeException.class);
        assertThat(timer(id).getExecutionId()).isEqualTo(job.getExecutionId()); assertThat(tasksFor(id)).isEmpty();
        waits.failed(job.getId(), (RuntimeException) failure);
        var failed = read(timers(id), "admin").path("items").get(0);
        assertThat(failed.path("errorCode").asText()).isEqualTo("COUNTERSIGN_NO_MEMBERS");
        doCallRealMethod().when(directory).members("demo", Set.of(), Set.of("APPROVER"));
        mvc.perform(post(timers(id) + "/" + failed.path("jobId").asText() + "/retry").header("Authorization", token("admin"))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("expectedVersion", 3, "reason", "人员关系修复，重试原等待")))).andExpect(status().isOk());
        assertThat(tasksFor(id)).hasSize(directory.members("demo", Set.of(), Set.of("APPROVER")).size());
        assertThat(auditCount(id, "TIMER_RETRY")).isEqualTo(1);
    }

    @Test
    void readBoundaryRequiresApplicationPermissionAndRejectsUnknownQueryParameters() throws Exception {
        String id = submitted(graph(true, false));
        mvc.perform(get(timers(id)).header("Authorization", token("bob"))).andExpect(status().isNotFound());
        mvc.perform(get(timers(id) + "?force=true").header("Authorization", token("alice"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/applications/" + id + "/rounds/0/timers").header("Authorization", token("admin"))).andExpect(status().isBadRequest());
        var diagram = read("/api/v1/applications/" + id + "/rounds/1/diagram", "alice");
        assertThat(diagram.toString()).contains("TIMER_WAIT", "ACTIVE");
    }

    private Graph graph(boolean before, boolean parallel) {
        var nodes = new ArrayList<>(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("wait", "等待约定时间", NodeType.TIMER_WAIT, Map.of("timerDelaySeconds", "1")),
                new Node("review", "实际人工审批", NodeType.USER_TASK, Map.of("assigneeRule", "user:finance")),
                new Node("end", "结束", NodeType.END, Map.of())));
        if (parallel) {
            nodes.add(new Node("fork", "同时进行", NodeType.PARALLEL_GATEWAY, Map.of()));
            nodes.add(new Node("join", "等待两边", NodeType.PARALLEL_GATEWAY, Map.of()));
            return new Graph(nodes, edges("start>fork", "fork>wait", "fork>review", "wait>join", "review>join", "join>end"));
        }
        return new Graph(nodes, before ? edges("start>wait", "wait>review", "review>end") : edges("start>review", "review>wait", "wait>end"));
    }
    private List<Edge> edges(String... pairs) {
        var values = new ArrayList<Edge>();
        for (String pair : pairs) { String[] ends = pair.split(">"); values.add(new Edge("edge" + values.size(), ends[0], ends[1], "")); }
        return values;
    }
    private String submitted(Graph graph) throws Exception {
        String key = "timer-" + UUID.randomUUID();
        var definition = definitions.create("demo", key, "定时等待验收", graph);
        definitions.publish(new Actor("demo", "admin", Set.of("ADMIN")), definition.id(), 0, "明确等待时间");
        var response = mvc.perform(post("/api/v1/applications").header("Authorization", token("alice")).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("businessNo", key, "processKey", key, "definitionVersion", 1, "title", "等待节点验收", "payload", Map.of()))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = json.read(response, JsonNode.class).path("id").asText(); submit(id, 1); return id;
    }
    private void submit(String id, long version) throws Exception {
        mvc.perform(post("/api/v1/applications/" + id + "/submit").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("expectedVersion", version)))).andExpect(status().isOk());
    }
    private Job timer(String id) { return jobs.createTimerJobQuery().processInstanceId(instance(id)).singleResult(); }
    private String instance(String id) { return runtime.createProcessInstanceQuery().variableValueEquals("applicationId", id).singleResult().getId(); }
    private List<org.flowable.task.api.Task> tasksFor(String id) { return tasks.createTaskQuery().processVariableValueEquals("applicationId", id).list(); }
    private void approve(String id, long version) throws Exception {
        mvc.perform(post("/api/v1/tasks/" + tasksFor(id).get(0).getId() + "/actions").header("Authorization", token("finance"))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("action", "APPROVE", "expectedVersion", version, "comment", "保留人工意见")))).andExpect(status().isOk());
    }
    private JsonNode application(String id) throws Exception { return read("/api/v1/applications/" + id, "alice"); }
    private JsonNode read(String path, String user) throws Exception {
        return json.read(mvc.perform(get(path).header("Authorization", token(user))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
    private String timers(String id) { return "/api/v1/applications/" + id + "/rounds/1/timers"; }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private int auditCount(String id, String action) { return jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE application_id=? AND action=?", Integer.class, id, action); }
}
