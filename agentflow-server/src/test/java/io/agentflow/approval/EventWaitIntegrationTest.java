package io.agentflow.approval;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.approval.process.EventWaitService;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.definition.DefinitionValidationException;
import io.agentflow.event.EventContractService;
import io.agentflow.notification.InboxMessage;
import io.agentflow.notification.InboxRepository;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.eventsubscription.api.EventSubscription;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static io.agentflow.approval.process.EventWaitService.Outcome.*;
import static io.agentflow.definition.DefinitionModels.*;
import static io.agentflow.support.MutationRequests.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实消息订阅的版本、租户、轮次和单次消费；可信收件适配器之外不暴露消费接口。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.timers.enabled=false",
        "agentflow.webhooks.worker-enabled=false", "agentflow.webhooks.targets.event.tenant-id=demo",
        "agentflow.webhooks.targets.event.label=合成事件验收", "agentflow.webhooks.targets.event.url=https://example.invalid/webhook",
        "agentflow.webhooks.targets.event.enabled=true", "agentflow.webhooks.targets.event.signing-secret=whsec_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class EventWaitIntegrationTest {
    private static final Actor ADMIN = new Actor("demo", "admin", Set.of("ADMIN"));
    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired AuthService auth;
    @Autowired DefinitionApplicationService definitions;
    @Autowired EventContractService contracts;
    @Autowired EventWaitService waits;
    @Autowired RuntimeService runtime;
    @Autowired TaskService tasks;
    @Autowired HistoryService history;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean InboxRepository inbox;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_EVENT_WAIT_URL", "jdbc:h2:mem:event-wait;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_EVENT_WAIT_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_EVENT_WAIT_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_EVENT_WAIT_PASSWORD", ""));
    }

    @Test
    void exactNativeSubscriptionAdvancesOnceAndDoesNotCreateAnApprovalOpinion() throws Exception {
        String key = contract(); String id = submitted(graph(key, true, false)); var command = command(id, key);
        var nativeWait = subscription(id);
        assertThat(nativeWait.getProcessDefinitionId()).isEqualTo(runtime.createProcessInstanceQuery().processInstanceId(nativeWait.getProcessInstanceId()).singleResult().getProcessDefinitionId());
        assertThat(tasksFor(id)).isEmpty();
        var before = read(path(id, 1), "alice");
        assertThat(before.path("items").size()).isEqualTo(1);
        assertThat(before.path("items").get(0).path("waitId").asText()).isEqualTo(command.waitId());
        var variables = runtime.getVariables(nativeWait.getProcessInstanceId());
        assertThat(waits.advance(command)).isEqualTo(ADVANCED);
        assertThat(runtime.getVariables(nativeWait.getProcessInstanceId())).containsAllEntriesOf(variables);
        assertThat(tasksFor(id)).hasSize(1);
        assertThat(application(id).path("version").asLong()).isEqualTo(3);
        assertThat(application(id).path("status").asText()).isEqualTo("IN_APPROVAL");
        assertThat(waits.advance(command)).isEqualTo(STALE);
        assertThat(auditCount(id, "EVENT_RECEIVED")).isEqualTo(1);
        assertThat(auditCount(id, "APPROVE")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_inbox WHERE application_id=? AND kind='TASK_PENDING'", Integer.class, id)).isEqualTo(1);
        approve(id, 3);
        assertThat(application(id).path("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void waitAfterApprovalCompletesTheBusinessRoundAndKeepsTheRealOpinion() throws Exception {
        String key = contract(); String id = submitted(graph(key, false, false)); approve(id, 2);
        var nativeWait = subscription(id);
        assertThat(waits.advance(command(id, key))).isEqualTo(ADVANCED);
        assertThat(application(id).path("status").asText()).isEqualTo("APPROVED");
        assertThat(auditCount(id, "APPROVE")).isEqualTo(1);
        assertThat(auditCount(id, "EVENT_RECEIVED")).isEqualTo(1);
        assertThat(read("/api/v1/applications/" + id + "/rounds", "alice").toString()).contains("APPROVED", "system:event:erp");
        assertThat(history.createHistoricProcessInstanceQuery().processInstanceId(nativeWait.getProcessInstanceId()).singleResult().getEndTime()).isNotNull();
        assertThat(read("/api/v1/applications/" + id + "/audit?action=EVENT_RECEIVED", "alice").path("items").size()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT event_type FROM webhook_delivery WHERE application_id=?", String.class, id))
                .containsExactlyInAnyOrder("ApplicationSubmitted", "TaskActionAccepted", "EventWaitReceived", "ApplicationApproved");
    }

    @Test
    void aMatchingEventCannotReleaseAnotherApplicationOrParallelHumanBranch() throws Exception {
        String key = contract(); String id = submitted(graph(key, true, true)); String other = submitted(graph(key, true, false));
        String taskId = tasksFor(id).get(0).getId(); String otherWait = subscription(other).getId();
        assertThat(waits.advance(command(id, key))).isEqualTo(ADVANCED);
        assertThat(tasksFor(id)).extracting(org.flowable.task.api.Task::getId).containsExactly(taskId);
        assertThat(subscription(other).getId()).isEqualTo(otherWait);
        assertThat(application(id).path("status").asText()).isEqualTo("IN_APPROVAL");
        assertThat(application(other).path("version").asLong()).isEqualTo(2);
        approve(id, 3);
        assertThat(application(id).path("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void tenantApplicationRoundAndActivationCannotBeRebound() throws Exception {
        String key = contract(); String id = submitted(graph(key, true, false)); String other = submitted(graph(key, true, false));
        var c = command(id, key);
        var wrongTargets = List.of(
                new EventWaitService.Command("another", c.sourceKey(), c.eventType(), 1, c.eventId(), c.applicationId(), 1, c.waitId(), key, 1),
                new EventWaitService.Command("demo", c.sourceKey(), c.eventType(), 1, c.eventId(), UUID.fromString(other), 1, c.waitId(), key, 1),
                new EventWaitService.Command("demo", c.sourceKey(), c.eventType(), 1, c.eventId(), c.applicationId(), 2, c.waitId(), key, 1),
                new EventWaitService.Command("demo", c.sourceKey(), c.eventType(), 1, c.eventId(), c.applicationId(), 1, subscription(other).getId(), key, 1));
        for (var wrong : wrongTargets) assertThat(waits.advance(wrong)).isEqualTo(STALE);
        assertThat(subscription(id).getId()).isEqualTo(c.waitId());
        assertThat(application(id).path("version").asLong()).isEqualTo(2);
        assertThat(auditCount(id, "EVENT_RECEIVED")).isZero();
    }

    @Test
    void sourceTypeEnvelopeAndPublishedVersionMustMatchTheFrozenReference() throws Exception {
        String key = contract(); String id = submitted(graph(key, true, false)); var c = command(id, key);
        contracts.publish(ADMIN, key, 1, "另一个契约版本", "warehouse", "GoodsAccepted", "保留旧版");
        var wrongEnvelopes = List.of(
                new EventWaitService.Command("demo", "warehouse", c.eventType(), 1, c.eventId(), c.applicationId(), 1, c.waitId(), key, 1),
                new EventWaitService.Command("demo", "erp", "AnotherEvent", 1, c.eventId(), c.applicationId(), 1, c.waitId(), key, 1),
                new EventWaitService.Command("demo", "erp", c.eventType(), 2, c.eventId(), c.applicationId(), 1, c.waitId(), key, 1),
                new EventWaitService.Command("demo", "erp", c.eventType(), 1, c.eventId(), c.applicationId(), 1, c.waitId(), "another-key", 1),
                new EventWaitService.Command("demo", "warehouse", c.eventType(), 1, c.eventId(), c.applicationId(), 1, c.waitId(), key, 2));
        for (var wrong : wrongEnvelopes) assertThat(waits.advance(wrong)).isEqualTo(MISMATCH);
        assertThat(application(id).path("version").asLong()).isEqualTo(2);
        assertThat(waits.advance(c)).isEqualTo(ADVANCED);
    }

    @Test
    void withdrawnRoundCannotConsumeAnEventAfterResubmission() throws Exception {
        String key = contract(); String id = submitted(graph(key, true, false)); var old = command(id, key);
        mvc.perform(post("/api/v1/applications/" + id + "/withdraw").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("expectedVersion", 2, "comment", "撤回原等待")))).andExpect(status().isOk());
        assertThat(waits.advance(old)).isEqualTo(STALE);
        submit(id, 3);
        var current = command(id, key);
        assertThat(current.roundNo()).isEqualTo(2); assertThat(current.waitId()).isNotEqualTo(old.waitId());
        assertThat(read(path(id, 1), "alice").path("items")).isEmpty();
        assertThat(waits.advance(old)).isEqualTo(STALE);
        assertThat(waits.advance(current)).isEqualTo(ADVANCED);
        assertThat(auditCount(id, "EVENT_RECEIVED")).isEqualTo(1);
    }

    @Test
    void pauseAndContractAvailabilityKeepTheOriginalNativeWait() throws Exception {
        String key = contract(); String id = submitted(graph(key, true, false)); var c = command(id, key);
        String processId = subscription(id).getProcessInstanceId();
        runtime.suspendProcessInstanceById(processId);
        assertThat(waits.advance(c)).isEqualTo(PAUSED);
        assertThat(read(path(id, 1), "admin").path("items").get(0).path("suspended").asBoolean()).isTrue();
        runtime.activateProcessInstanceById(processId);
        contracts.changeAvailability(ADMIN, key, 1, 1, false, "暂停可信事件消费");
        assertThat(waits.advance(c)).isEqualTo(CONTRACT_UNAVAILABLE);
        assertThat(read(path(id, 1), "alice").path("items").get(0).path("contractEnabled").asBoolean()).isFalse();
        assertThat(subscription(id).getId()).isEqualTo(c.waitId());
        assertThat(application(id).path("version").asLong()).isEqualTo(2);
        contracts.changeAvailability(ADMIN, key, 1, 2, true, "恢复原契约");
        assertThat(waits.advance(c)).isEqualTo(ADVANCED);
    }

    @Test
    void disablingAContractDoesNotUndoAnEarlierHumanDecisionOnTheWayToTheWait() throws Exception {
        String key = contract(); String id = submitted(graph(key, false, false));
        contracts.changeAvailability(ADMIN, key, 1, 1, false, "暂停新事件");
        approve(id, 2); var c = command(id, key);
        assertThat(auditCount(id, "APPROVE")).isEqualTo(1);
        assertThat(waits.advance(c)).isEqualTo(CONTRACT_UNAVAILABLE);
        assertThat(application(id).path("status").asText()).isEqualTo("IN_APPROVAL");
        contracts.changeAvailability(ADMIN, key, 1, 2, true, "恢复原版本");
        assertThat(waits.advance(c)).isEqualTo(ADVANCED);
        assertThat(application(id).path("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void disabledContractBlocksPublicationAndEveryNewSubmissionRound() throws Exception {
        String key = contract(); var graph = graph(key, true, false); String id = draft(graph);
        contracts.changeAvailability(ADMIN, key, 1, 1, false, "停用已发布契约");
        var definition = definitions.create("demo", "disabled-event-" + UUID.randomUUID(), "不可发布", graph);
        assertThatThrownBy(() -> definitions.publish(ADMIN, definition.id(), 0, "必须失败"))
                .isInstanceOfSatisfying(DefinitionValidationException.class,
                        failure -> assertThat(failure.errors()).contains("EVENT_CONTRACT_UNAVAILABLE:wait"));
        mvc.perform(post("/api/v1/applications/" + id + "/submit").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("expectedVersion", 1)))).andExpect(status().isConflict());
        assertThat(application(id).path("version").asLong()).isEqualTo(1);
        contracts.changeAvailability(ADMIN, key, 1, 2, true, "恢复后发起");
        submit(id, 1);
        mvc.perform(post("/api/v1/applications/" + id + "/withdraw").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("expectedVersion", 2, "comment", "重新发起前复核契约")))).andExpect(status().isOk());
        contracts.changeAvailability(ADMIN, key, 1, 3, false, "阻断新轮次");
        mvc.perform(post("/api/v1/applications/" + id + "/submit").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("expectedVersion", 3)))).andExpect(status().isConflict());
        assertThat(application(id).path("roundNo").asInt()).isEqualTo(1);
        assertThat(application(id).path("status").asText()).isEqualTo("WITHDRAWN");
    }

    @Test
    void concurrentConsumersProduceOneTransitionAndNotification() throws Exception {
        String key = contract(); String id = submitted(graph(key, true, false)); var c = command(id, key);
        var barrier = new CyclicBarrier(2); var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { barrier.await(); return waits.advance(c); });
            var second = pool.submit(() -> { barrier.await(); return waits.advance(c); });
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS))).containsExactlyInAnyOrder(ADVANCED, STALE);
        } finally { pool.shutdownNow(); }
        assertThat(tasksFor(id)).hasSize(1);
        assertThat(auditCount(id, "EVENT_RECEIVED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_inbox WHERE application_id=? AND kind='TASK_PENDING'", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void notificationFailureRollsBackTheNativeSubscriptionAndBusinessEffects() throws Exception {
        String key = contract(); String id = submitted(graph(key, true, false)); var c = command(id, key);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (((InboxMessage) call.getArgument(1)).kind() == InboxMessage.Kind.TASK_PENDING) {
                throw new DomainException("DEPENDENCY_UNAVAILABLE", "Simulated notification failure");
            }
            return result;
        }).when(inbox).append(anyString(), any(InboxMessage.class));
        try {
            assertThatThrownBy(() -> waits.advance(c)).isInstanceOf(DomainException.class);
        } finally { doCallRealMethod().when(inbox).append(anyString(), any(InboxMessage.class)); }
        assertThat(subscription(id).getId()).isEqualTo(c.waitId());
        assertThat(tasksFor(id)).isEmpty();
        assertThat(application(id).path("version").asLong()).isEqualTo(2);
        assertThat(auditCount(id, "EVENT_RECEIVED")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_inbox WHERE application_id=? AND kind='TASK_PENDING'", Integer.class, id)).isZero();
        assertThat(waits.advance(c)).isEqualTo(ADVANCED);
    }

    @Test
    void eachSequentialActivationRequiresItsOwnWaitIdentity() throws Exception {
        String key = contract(); var graph = graph(key, true, false); var nodes = new ArrayList<>(graph.nodes());
        nodes.add(new Node("anotherWait", "第二次等待", NodeType.EVENT_WAIT, Map.of("eventContractKey", key, "eventContractVersion", "1")));
        String id = submitted(new Graph(nodes, edges("start>wait", "wait>anotherWait", "anotherWait>review", "review>end")));
        var first = command(id, key);
        assertThat(waits.advance(first)).isEqualTo(ADVANCED);
        var second = command(id, key); assertThat(second.waitId()).isNotEqualTo(first.waitId());
        assertThat(waits.advance(first)).isEqualTo(STALE);
        assertThat(tasksFor(id)).isEmpty();
        assertThat(waits.advance(second)).isEqualTo(ADVANCED);
        assertThat(tasksFor(id)).hasSize(1); assertThat(auditCount(id, "EVENT_RECEIVED")).isEqualTo(2);
    }

    @Test
    void runtimeBindingMustStillAgreeWithTheBusinessRound() throws Exception {
        String key = contract(); String id = submitted(graph(key, true, false)); var c = command(id, key);
        String processId = subscription(id).getProcessInstanceId();
        var replacements = Map.<String, Object>of("tenantId", "another", "applicationId", UUID.randomUUID().toString(), "roundNo", 2);
        for (var value : replacements.entrySet()) {
            Object original = runtime.getVariable(processId, value.getKey());
            try {
                runtime.setVariable(processId, value.getKey(), value.getValue());
                assertThat(waits.advance(c)).isEqualTo(STALE);
            } finally { runtime.setVariable(processId, value.getKey(), original); }
        }
        assertThat(subscription(id).getId()).isEqualTo(c.waitId());
        assertThat(application(id).path("version").asLong()).isEqualTo(2);
        assertThat(auditCount(id, "EVENT_RECEIVED")).isZero();
        assertThat(waits.advance(c)).isEqualTo(ADVANCED);
    }

    @Test
    void generatedMessageNamesCannotCollideWithUserSuppliedElementIds() throws Exception {
        String key = contract(); var graph = graph(key, true, false);
        var nodes = graph.nodes().stream().map(node -> node.id().equals("wait")
                ? new Node("agentflowEventMessage1", node.name(), node.type(), node.properties()) : node).toList();
        var edges = List.of(new Edge("agentflowEventMessage2", "start", "agentflowEventMessage1", ""),
                new Edge("b", "agentflowEventMessage1", "review", ""), new Edge("c", "review", "end", ""));
        String id = submitted(new Graph(nodes, edges));
        assertThat(subscription(id).getEventName()).isNotIn("agentflowEventMessage1", "agentflowEventMessage2");
        assertThat(waits.advance(command(id, key))).isEqualTo(ADVANCED);
        assertThat(tasksFor(id)).hasSize(1);
    }

    @Test
    void readRequiresParticipantPermissionAndTheDiagramUsesTheEventNodeType() throws Exception {
        String key = contract(); String id = submitted(graph(key, true, false));
        mvc.perform(get(path(id, 1)).header("Authorization", token("bob"))).andExpect(status().isNotFound());
        mvc.perform(get(path(id, 1) + "?force=true").header("Authorization", token("alice"))).andExpect(status().isBadRequest());
        mvc.perform(get(path(id, 0)).header("Authorization", token("admin"))).andExpect(status().isBadRequest());
        mvc.perform(get(path(id, 2)).header("Authorization", token("alice"))).andExpect(status().isNotFound());
        assertThat(read("/api/v1/applications/" + id + "/rounds/1/diagram", "alice").toString()).contains("EVENT_WAIT", "ACTIVE");
    }

    private String contract() {
        String key = "accepted-" + UUID.randomUUID();
        contracts.publish(ADMIN, key, 0, "验收完成事件", "erp", "GoodsAccepted", "固定来源和事件种类");
        return key;
    }
    private Graph graph(String key, boolean before, boolean parallel) {
        var nodes = new ArrayList<>(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("wait", "等待验收事件", NodeType.EVENT_WAIT, Map.of("eventContractKey", key, "eventContractVersion", "1")),
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
    private String draft(Graph graph) throws Exception {
        String key = "event-" + UUID.randomUUID();
        var definition = definitions.create("demo", key, "事件等待验收", graph);
        definitions.publish(ADMIN, definition.id(), 0, "明确契约版本");
        var response = mvc.perform(post("/api/v1/applications").header("Authorization", token("alice")).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("businessNo", key, "processKey", key, "definitionVersion", 1, "title", "事件等待验收", "payload", Map.of()))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.read(response, JsonNode.class).path("id").asText();
    }
    private String submitted(Graph graph) throws Exception { String id = draft(graph); submit(id, 1); return id; }
    private void submit(String id, long version) throws Exception {
        mvc.perform(post("/api/v1/applications/" + id + "/submit").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("expectedVersion", version)))).andExpect(status().isOk());
    }
    private EventSubscription subscription(String id) {
        String processId = runtime.createProcessInstanceQuery().variableValueEquals("applicationId", id).singleResult().getId();
        return runtime.createEventSubscriptionQuery().processInstanceId(processId).eventType("message").singleResult();
    }
    private EventWaitService.Command command(String id, String key) throws Exception {
        return new EventWaitService.Command("demo", "erp", "GoodsAccepted", 1, UUID.randomUUID().toString(), UUID.fromString(id),
                application(id).path("roundNo").asInt(), subscription(id).getId(), key, 1);
    }
    private List<org.flowable.task.api.Task> tasksFor(String id) { return tasks.createTaskQuery().processVariableValueEquals("applicationId", id).list(); }
    private void approve(String id, long version) throws Exception {
        mvc.perform(post("/api/v1/tasks/" + tasksFor(id).get(0).getId() + "/actions").header("Authorization", token("finance"))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("action", "APPROVE", "expectedVersion", version, "comment", "保留人工意见")))).andExpect(status().isOk());
    }
    private JsonNode application(String id) throws Exception { return read("/api/v1/applications/" + id, "alice"); }
    private JsonNode read(String path, String user) throws Exception {
        return json.read(mvc.perform(get(path).header("Authorization", token(user))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
    private String path(String id, int round) { return "/api/v1/applications/" + id + "/rounds/" + round + "/event-waits"; }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private int auditCount(String id, String action) { return jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE application_id=? AND action=?", Integer.class, id, action); }
}
