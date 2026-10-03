package io.agentflow.approval;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.notification.InboxMessage;
import io.agentflow.notification.InboxRepository;
import io.agentflow.organization.LocalOrganizationDirectory;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 实际多实例门槛、取消语义、原责任与并行节点隔离；同时用于 H2 和 PostgreSQL。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = "agentflow.auth.demo-enabled=true")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class CountersignPolicyIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired DefinitionApplicationService definitions;
    @Autowired TaskService tasks;
    @Autowired HistoryService history;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean LocalOrganizationDirectory directory;
    @MockitoSpyBean InboxRepository inbox;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_COUNTERSIGN_POLICY_URL", "jdbc:h2:mem:countersign-policy;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_COUNTERSIGN_POLICY_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_COUNTERSIGN_POLICY_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_COUNTERSIGN_POLICY_PASSWORD", ""));
    }

    @BeforeEach
    void threeRealEligiblePeople() {
        doReturn(List.of("admin", "finance", "bob")).when(directory).members("demo", Set.of(), Set.of("APPROVER"));
    }

    @Test
    void anyApprovalEndsOtherTasksWithoutInventingTheirOpinionsAndReplayDoesNotNotifyAgain() throws Exception {
        String id = submitted("ANY", null, false); Task source = assigned(id, "finance");
        List<String> original = pending(id).stream().map(Task::getId).toList();
        var progress = read("/api/v1/tasks/" + source.getId(), "finance").path("countersign");
        assertThat(progress.path("mode").asText()).isEqualTo("ANY");
        assertThat(progress.path("required").asInt()).isEqualTo(1);
        String key = UUID.randomUUID().toString();
        String result = action(source, "finance", "APPROVE", 2, null, key, 200);
        assertThat(application(id).path("status").asText()).isEqualTo("APPROVED");
        assertThat(pending(id)).isEmpty();
        assertThat(history.createHistoricTaskInstanceQuery().taskId(source.getId()).singleResult().getDeleteReason()).isNull();
        for (String taskId : original) if (!taskId.equals(source.getId())) {
            assertThat(history.createHistoricTaskInstanceQuery().taskId(taskId).singleResult().getDeleteReason()).isNotBlank();
        }
        assertThat(approvals(id)).isEqualTo(1);
        assertThat(closedNotifications(id)).isEqualTo(2);
        assertThat(action(source, "finance", "APPROVE", 2, null, key, 200)).isEqualTo(result);
        assertThat(approvals(id)).isEqualTo(1); assertThat(closedNotifications(id)).isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource({"1,1", "50,2", "67,3", "100,3"})
    void percentageUsesCeilingAndActualVotes(int percentage, int required) throws Exception {
        String id = submitted("PERCENT", percentage, false);
        var originals = pending(id); long version = 2;
        for (int index = 0; index < required; index++) {
            Task task = originals.get(index);
            var progress = read("/api/v1/tasks/" + task.getId(), task.getAssignee()).path("countersign");
            assertThat(progress.path("percentage").asInt()).isEqualTo(percentage);
            assertThat(progress.path("required").asInt()).isEqualTo(required);
            assertThat(progress.path("completed").asInt()).isEqualTo(index);
            action(task, task.getAssignee(), "APPROVE", version++, null, UUID.randomUUID().toString(), 200);
            assertThat(application(id).path("status").asText()).isEqualTo(index + 1 == required ? "APPROVED" : "IN_APPROVAL");
        }
        assertThat(approvals(id)).isEqualTo(required);
        assertThat(closedNotifications(id)).isEqualTo(3 - required);
    }

    @Test
    void directoryChangesAndNewPublishedVersionCannotChangeTheActiveThreshold() throws Exception {
        String id = submitted("PERCENT", 50, false); Task original = assigned(id, "finance");
        String key = application(id).path("processKey").asText();
        var next = definitions.create("demo", key, "后续全员规则", graph("ALL", null, false));
        definitions.publish(new Actor("demo", "admin", Set.of("ADMIN")), next.id(), 0, "只影响后续版本");
        doReturn(List.of("finance")).when(directory).members("demo", Set.of(), Set.of("APPROVER"));
        action(original, "finance", "APPROVE", 2, null, UUID.randomUUID().toString(), 200);
        var remaining = read("/api/v1/tasks/" + assigned(id, "admin").getId(), "admin").path("countersign");
        assertThat(remaining.path("total").asInt()).isEqualTo(3);
        assertThat(remaining.path("required").asInt()).isEqualTo(2);
        assertThat(remaining.path("mode").asText()).isEqualTo("PERCENT");
        action(assigned(id, "admin"), "admin", "APPROVE", 3, null, UUID.randomUUID().toString(), 200);
        assertThat(application(id).path("definitionVersion").asInt()).isEqualTo(1);
        assertThat(application(id).path("status").asText()).isEqualTo("APPROVED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"REJECT", "RETURN"})
    void negativeDecisionBeforeTheThresholdEndsTheWholeRound(String decision) throws Exception {
        String id = submitted("PERCENT", 67, false);
        action(assigned(id, "finance"), "finance", "APPROVE", 2, null, UUID.randomUUID().toString(), 200);
        action(assigned(id, "admin"), "admin", decision, 3, null, UUID.randomUUID().toString(), 200);
        assertThat(pending(id)).isEmpty();
        assertThat(application(id).path("status").asText()).isEqualTo(decision.equals("RETURN") ? "RETURNED" : "REJECTED");
        assertThat(approvals(id)).isEqualTo(1);
        assertThat(closedNotifications(id)).isZero();
    }

    @Test
    void delegationDoesNotCountAndMembershipCannotChangeTheFrozenDenominator() throws Exception {
        String id = submitted("PERCENT", 50, false); Task source = assigned(id, "finance");
        action(source, "finance", "DELEGATE", 2, "manager", UUID.randomUUID().toString(), 200);
        action(source, "manager", "APPROVE", 3, null, UUID.randomUUID().toString(), 409);
        action(source, "manager", "RESOLVE", 3, null, UUID.randomUUID().toString(), 200);
        assertThat(read("/api/v1/tasks/" + source.getId(), "finance").path("countersign").path("completed").asInt()).isZero();
        for (String action : List.of("TRANSFER", "CLAIM", "RELEASE")) {
            action(source, "finance", action, 4, "manager", UUID.randomUUID().toString(), 409);
        }
        mvc.perform(post("/api/v1/tasks/" + source.getId() + "/countersign-changes").header("Authorization", token("finance"))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("action", "ADD", "targetUser", "manager", "reason", "不得改变分母", "expectedVersion", 4))))
                .andExpect(status().isConflict());
        assertThat(application(id).path("version").asInt()).isEqualTo(4);
        action(source, "finance", "APPROVE", 4, null, UUID.randomUUID().toString(), 200);
        assertThat(pending(id)).hasSize(2);
    }

    @Test
    void earlyCompletionDoesNotReleaseAnotherParallelBranchOrNotifyItsAssignee() throws Exception {
        String id = submitted("ANY", null, true);
        action(assigned(id, "finance"), "finance", "APPROVE", 2, null, UUID.randomUUID().toString(), 200);
        assertThat(pending(id)).extracting(Task::getTaskDefinitionKey).containsExactly("other");
        assertThat(application(id).path("status").asText()).isEqualTo("IN_APPROVAL");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_inbox WHERE application_id=? AND recipient_id='manager' AND kind='TASK_COUNTERSIGN_COMPLETED'", Integer.class, id)).isZero();
        action(assigned(id, "manager"), "manager", "APPROVE", 3, null, UUID.randomUUID().toString(), 200);
        assertThat(application(id).path("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void notificationFailureRollsBackCompletedAndCanceledTasksThenOriginalRequestCanRetry() throws Exception {
        String id = submitted("ANY", null, false); Task source = assigned(id, "finance"); String key = UUID.randomUUID().toString();
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (((InboxMessage) call.getArgument(1)).kind() == InboxMessage.Kind.TASK_COUNTERSIGN_COMPLETED) {
                throw new DomainException("DEPENDENCY_UNAVAILABLE", "Simulated countersign notification failure");
            }
            return result;
        }).when(inbox).append(anyString(), any(InboxMessage.class));
        action(source, "finance", "APPROVE", 2, null, key, 503);
        assertThat(pending(id)).hasSize(3);
        assertThat(application(id).path("version").asInt()).isEqualTo(2);
        assertThat(history.createHistoricTaskInstanceQuery().taskId(source.getId()).singleResult().getEndTime()).isNull();
        assertThat(approvals(id)).isZero(); assertThat(closedNotifications(id)).isZero();
        doCallRealMethod().when(inbox).append(anyString(), any(InboxMessage.class));
        action(source, "finance", "APPROVE", 2, null, key, 200);
        assertThat(approvals(id)).isEqualTo(1); assertThat(closedNotifications(id)).isEqualTo(2);
    }

    @Test
    void simultaneousApprovalAndRejectionCommitOnlyOneDecision() throws Exception {
        String id = submitted("ANY", null, false); Task finance = assigned(id, "finance"), admin = assigned(id, "admin");
        String financeToken = token("finance"), adminToken = token("admin");
        var ready = new CyclicBarrier(2); var pool = Executors.newFixedThreadPool(2);
        try {
            var approve = pool.submit(() -> { ready.await(); return sendAction(finance, financeToken, "APPROVE", 2, null, UUID.randomUUID().toString()).getResponse().getStatus(); });
            var reject = pool.submit(() -> { ready.await(); return sendAction(admin, adminToken, "REJECT", 2, null, UUID.randomUUID().toString()).getResponse().getStatus(); });
            int first = approve.get(30, TimeUnit.SECONDS), second = reject.get(30, TimeUnit.SECONDS);
            assertThat(List.of(first, second).stream().filter(code -> code == 200).count()).isEqualTo(1);
            assertThat(first == 200 ? second : first).isIn(404, 409);
            assertThat(application(id).path("status").asText()).isEqualTo(first == 200 ? "APPROVED" : "REJECTED");
            assertThat(pending(id)).isEmpty();
            assertThat(approvals(id)).isEqualTo(first == 200 ? 1 : 0);
        } finally { pool.shutdownNow(); }
    }

    private Graph graph(String mode, Integer percentage, boolean parallel) {
        var props = new LinkedHashMap<String, String>(); props.put("assigneeRule", "role:APPROVER"); props.put("approvalMode", mode);
        if (percentage != null) props.put("approvalPercentage", percentage.toString());
        var nodes = new ArrayList<>(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("review", "必要复核", NodeType.USER_TASK, props), new Node("end", "结束", NodeType.END, Map.of())));
        if (!parallel) return new Graph(nodes, List.of(new Edge("begin", "start", "review", ""), new Edge("finish", "review", "end", "")));
        nodes.add(new Node("fork", "并行分支", NodeType.PARALLEL_GATEWAY, Map.of()));
        nodes.add(new Node("join", "汇合", NodeType.PARALLEL_GATEWAY, Map.of()));
        nodes.add(new Node("other", "独立主管审批", NodeType.USER_TASK, Map.of("assigneeRule", "user:manager")));
        return new Graph(nodes, List.of(new Edge("begin", "start", "fork", ""), new Edge("left", "fork", "review", ""),
                new Edge("right", "fork", "other", ""), new Edge("leftJoin", "review", "join", ""),
                new Edge("rightJoin", "other", "join", ""), new Edge("finish", "join", "end", "")));
    }

    private String submitted(String mode, Integer percentage, boolean parallel) throws Exception {
        String key = "policy-" + UUID.randomUUID();
        var definition = definitions.create("demo", key, "会签方式验收", graph(mode, percentage, parallel));
        definitions.publish(new Actor("demo", "admin", Set.of("ADMIN")), definition.id(), 0, "明确通过方式");
        var result = mvc.perform(post("/api/v1/applications").header("Authorization", token("alice")).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("businessNo", key, "processKey", key, "definitionVersion", 1, "title", "会签策略验收", "payload", Map.of()))))
                .andExpect(status().isCreated()).andReturn();
        String id = json.read(result.getResponse().getContentAsString(), JsonNode.class).path("id").asText();
        mvc.perform(post("/api/v1/applications/" + id + "/submit").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}")).andExpect(status().isOk());
        return id;
    }

    private List<Task> pending(String id) { return tasks.createTaskQuery().processVariableValueEquals("applicationId", id).list(); }
    private Task assigned(String id, String user) { return pending(id).stream().filter(task -> user.equals(task.getAssignee())).findFirst().orElseThrow(); }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private JsonNode read(String path, String user) throws Exception { return json.read(mvc.perform(get(path).header("Authorization", token(user))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), JsonNode.class); }
    private JsonNode application(String id) throws Exception { return read("/api/v1/applications/" + id, "alice"); }
    private int approvals(String id) { return jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE application_id=? AND action='APPROVE'", Integer.class, id); }
    private int closedNotifications(String id) { return jdbc.queryForObject("SELECT count(*) FROM notification_inbox WHERE application_id=? AND kind='TASK_COUNTERSIGN_COMPLETED'", Integer.class, id); }
    private String action(Task task, String user, String action, long version, String target, String key, int expected) throws Exception {
        var result = sendAction(task, token(user), action, version, target, key);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(expected);
        return result.getResponse().getContentAsString();
    }
    private org.springframework.test.web.servlet.MvcResult sendAction(Task task, String token, String action, long version, String target, String key) throws Exception {
        var body = new LinkedHashMap<String, Object>(); body.put("action", action); body.put("expectedVersion", version); body.put("comment", "真实责任人意见");
        if (target != null) body.put("targetUser", target);
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/tasks/" + task.getId() + "/actions").header("Authorization", token).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json.write(body))).andReturn();
    }
}
