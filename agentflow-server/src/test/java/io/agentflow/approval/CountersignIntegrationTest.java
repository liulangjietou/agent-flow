package io.agentflow.approval;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.approval.process.ApprovalCompletionService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.util.AopTestUtils;
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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 真实引擎中的全员会签、轮次终止和多任务事务回归。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:countersign;DB_CLOSE_DELAY=-1", "agentflow.auth.demo-enabled=true"})
@AutoConfigureMockMvc
class CountersignIntegrationTest {
    @Autowired MockMvc mvc;
    @MockitoSpyBean AuthService auth;
    @MockitoSpyBean ApprovalCompletionService completion;
    @Autowired JsonUtil json;
    @Autowired DefinitionApplicationService definitions;
    @Autowired TaskService tasks;
    @Autowired JdbcTemplate jdbc;

    @Test
    void createsOneTaskForEachMemberAndWaitsForAllApprovals() throws Exception {
        String id = submitted();
        assertThat(pending(id)).extracting(Task::getAssignee).containsExactlyInAnyOrder("admin", "finance");
        Task finance = assigned(id, "finance");
        mvc.perform(get("/api/v1/tasks/" + finance.getId()).header("Authorization", token("finance")))
                .andExpect(status().isOk()).andExpect(jsonPath("countersign.total").value(2))
                .andExpect(jsonPath("countersign.completed").value(0));
        act(finance, "finance", "APPROVE", 2, null).andExpect(status().isOk())
                .andExpect(jsonPath("applicationStatus").value("IN_APPROVAL"));
        assertThat(pending(id)).extracting(Task::getAssignee).containsExactly("admin");
        mvc.perform(get("/api/v1/tasks/" + assigned(id, "admin").getId()).header("Authorization", token("admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("countersign.completed").value(1));
        act(assigned(id, "admin"), "admin", "APPROVE", 3, null).andExpect(status().isOk())
                .andExpect(jsonPath("applicationStatus").value("APPROVED"));
        assertThat(pending(id)).isEmpty();
    }

    @Test
    void partialApprovalDoesNotNotifyAnExistingTaskAgain() throws Exception {
        String id = submitted();
        assertThat(messages(id, "admin", "TASK_PENDING")).isEqualTo(1);
        act(assigned(id, "finance"), "finance", "APPROVE", 2, null).andExpect(status().isOk());
        assertThat(messages(id, "admin", "TASK_PENDING")).isEqualTo(1);
        assertThat(messages(id, "alice", "APPLICATION_APPROVED")).isZero();
        act(assigned(id, "admin"), "admin", "APPROVE", 3, null).andExpect(status().isOk());
        assertThat(messages(id, "alice", "APPLICATION_APPROVED")).isEqualTo(1);
    }

    @Test
    void delegationOnlyNotifiesTheAffectedTaskAndReturnsToItsOriginalMember() throws Exception {
        String id = submitted();
        Task finance = assigned(id, "finance");
        act(finance, "finance", "DELEGATE", 2, "bob").andExpect(status().isOk());
        assertThat(messages(id, "bob", "TASK_DELEGATED")).isEqualTo(1);
        assertThat(messages(id, "admin", "TASK_DELEGATED")).isZero();
        act(finance, "bob", "APPROVE", 3, null).andExpect(status().isConflict());
        act(finance, "bob", "RESOLVE", 3, null).andExpect(status().isOk());
        assertThat(messages(id, "finance", "TASK_RESOLVED")).isEqualTo(1);
        assertThat(messages(id, "admin", "TASK_RESOLVED")).isZero();
        assertThat(assigned(id, "finance").getId()).isEqualTo(finance.getId());
        act(finance, "finance", "APPROVE", 4, null).andExpect(status().isOk());
        assertThat(pending(id)).extracting(Task::getAssignee).containsExactly("admin");
    }

    @Test
    void fixedMembersCannotTransferReleaseClaimOrActAsAnotherUser() throws Exception {
        String id = submitted();
        Task finance = assigned(id, "finance");
        mvc.perform(get("/api/v1/tasks/" + finance.getId()).header("Authorization", token("finance")))
                .andExpect(status().isOk()).andExpect(jsonPath("allowedActions", org.hamcrest.Matchers.containsInAnyOrder(
                        "APPROVE", "RETURN", "REJECT", "DELEGATE")));
        for (String action : List.of("TRANSFER", "RELEASE", "CLAIM")) {
            act(finance, "finance", action, 2, "bob").andExpect(status().isConflict())
                    .andExpect(jsonPath("code").value("COUNTERSIGN_ASSIGNMENT_FIXED"));
        }
        act(finance, "bob", "APPROVE", 2, null).andExpect(status().isForbidden());
        assertThat(pending(id)).hasSize(2);
        assertThat(application(id).path("version").asLong()).isEqualTo(2);
        assertThat(taskAuditCount(finance)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"REJECT", "RETURN", "WITHDRAW"})
    void negativeConclusionTerminatesEveryRemainingTask(String action) throws Exception {
        String id = submitted();
        Task finance = assigned(id, "finance"), admin = assigned(id, "admin");
        long version = 2;
        if (action.equals("REJECT")) {
            act(finance, "finance", "APPROVE", version, null).andExpect(status().isOk());
            version++;
        }
        if (action.equals("WITHDRAW")) {
            mvc.perform(post("/api/v1/applications/" + id + "/withdraw").header("Authorization", token("alice"))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":2,\"comment\":\"补充材料\"}"))
                    .andExpect(status().isOk());
        } else act(action.equals("REJECT") ? admin : finance, action.equals("REJECT") ? "admin" : "finance",
                action, version, null).andExpect(status().isOk());
        assertThat(pending(id)).isEmpty();
        String state = action.equals("REJECT") ? "REJECTED" : action.equals("RETURN") ? "RETURNED" : "WITHDRAWN";
        assertThat(application(id).path("status").asText()).isEqualTo(state);
        JsonNode previousRound = rounds(id).get(0);
        assertThat(previousRound.path("status").asText()).isEqualTo(state);
        act(admin, "admin", "APPROVE", 3, null).andExpect(status().isNotFound());
        if (!action.equals("REJECT")) {
            // 新轮次重新取得成员，旧轮次的终止结论保持不变。
            doReturn(List.of("finance")).when(auth).members("demo", Set.of(), Set.of("FINANCE"));
            submit(id, 3).andExpect(status().isOk());
            assertThat(pending(id)).extracting(Task::getAssignee).containsExactly("finance");
            assertThat(rounds(id).get(0)).isEqualTo(previousRound);
            assertThat(rounds(id).get(1).path("roundNo").asInt()).isEqualTo(2);
        }
    }

    @Test
    void freezesMembersOncePerActivationAndRefreshesForTheNextNode() throws Exception {
        String id = created(graph(true));
        submit(id, 1).andExpect(status().isOk());
        verify(auth, times(1)).members("demo", Set.of(), Set.of("FINANCE"));
        doReturn(List.of("bob")).when(auth).members("demo", Set.of(), Set.of("FINANCE"));
        act(assigned(id, "finance"), "finance", "APPROVE", 2, null).andExpect(status().isOk());
        assertThat(pending(id)).extracting(Task::getAssignee).containsExactly("admin");
        act(assigned(id, "admin"), "admin", "APPROVE", 3, null).andExpect(status().isOk());
        assertThat(pending(id)).extracting(Task::getAssignee).containsExactly("bob");
        Task next = assigned(id, "bob");
        assertThat(next.getTaskDefinitionKey()).isEqualTo("second");
        mvc.perform(get("/api/v1/tasks/" + next.getId()).header("Authorization", token("bob")))
                .andExpect(status().isOk()).andExpect(jsonPath("countersign.total").value(1))
                .andExpect(jsonPath("countersign.completed").value(0));
        assertThat(messages(id, "bob", "TASK_PENDING")).isEqualTo(1);
        act(next, "bob", "APPROVE", 4, null).andExpect(status().isOk())
                .andExpect(jsonPath("applicationStatus").value("APPROVED"));
    }

    @Test
    void noMembersRollsBackSubmissionAndTransitionInsteadOfSkippingTheNode() throws Exception {
        String id = created(graph(true));
        doReturn(List.of()).when(auth).members("demo", Set.of(), Set.of("FINANCE"));
        submit(id, 1).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("code").value("COUNTERSIGN_NO_MEMBERS"));
        assertThat(application(id).path("version").asLong()).isEqualTo(1);
        assertThat(application(id).path("status").asText()).isEqualTo("DRAFT");
        assertThat(rounds(id)).isEmpty();
        assertThat(pending(id)).isEmpty();
        assertThat(messages(id, "alice", "APPLICATION_SUBMITTED")).isZero();
        doReturn(List.of("finance")).when(auth).members("demo", Set.of(), Set.of("FINANCE"));
        submit(id, 1).andExpect(status().isOk());
        Task original = assigned(id, "finance");
        doReturn(List.of()).when(auth).members("demo", Set.of(), Set.of("FINANCE"));
        act(original, "finance", "APPROVE", 2, null).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("code").value("COUNTERSIGN_NO_MEMBERS"));
        assertThat(assigned(id, "finance").getId()).isEqualTo(original.getId());
        assertThat(application(id).path("version").asLong()).isEqualTo(2);
        assertThat(taskAuditCount(original)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVE", "REJECT"})
    void concurrentMemberDecisionsRollbackTheLoserAndCanBeRetried(String secondAction) throws Exception {
        String id = submitted();
        Task finance = assigned(id, "finance"), admin = assigned(id, "admin");
        CyclicBarrier barrier = new CyclicBarrier(2);
        var completionTarget = AopTestUtils.<ApprovalCompletionService>getUltimateTargetObject(completion);
        // 在申请锁之前同步两个已读取的请求；锁后等待另一请求会由测试本身制造死锁。
        doAnswer(invocation -> { barrier.await(5, TimeUnit.SECONDS); return invocation.callRealMethod(); })
                .when(completionTarget).lock(any());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> act(finance, "finance", "APPROVE", 2, null).andReturn().getResponse());
            var second = executor.submit(() -> act(admin, "admin", secondAction, 2, null).andReturn().getResponse());
            assertThat(List.of(first.get(15, TimeUnit.SECONDS).getStatus(), second.get(15, TimeUnit.SECONDS).getStatus()))
                    .containsExactlyInAnyOrder(200, 409);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            doCallRealMethod().when(completionTarget).lock(any());
        }
        assertThat(taskAuditCount(finance) + taskAuditCount(admin)).isEqualTo(1);
        assertThat(application(id).path("version").asLong()).isEqualTo(3);
        if (application(id).path("status").asText().equals("REJECTED")) {
            assertThat(pending(id)).isEmpty();
            assertThat(messages(id, "alice", "APPLICATION_REJECTED")).isEqualTo(1);
            assertThat(messages(id, "alice", "APPLICATION_APPROVED")).isZero();
            return;
        }
        assertThat(pending(id)).hasSize(1);
        Task remaining = pending(id).get(0);
        String result = secondAction.equals("APPROVE") ? "APPROVED" : "REJECTED";
        act(remaining, remaining.getAssignee(), secondAction, 3, null).andExpect(status().isOk())
                .andExpect(jsonPath("applicationStatus").value(result));
        assertThat(pending(id)).isEmpty();
        assertThat(taskAuditCount(finance) + taskAuditCount(admin)).isEqualTo(2);
        assertThat(messages(id, "alice", "APPLICATION_" + result)).isEqualTo(1);
    }

    private int taskAuditCount(Task task) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE aggregate_type='Task' AND aggregate_id=?",
                Integer.class, task.getId());
    }

    private JsonNode application(String id) throws Exception { return read("/api/v1/applications/" + id); }
    private JsonNode rounds(String id) throws Exception { return read("/api/v1/applications/" + id + "/rounds"); }
    private JsonNode read(String path) throws Exception {
        return json.read(mvc.perform(get(path).header("Authorization", token("alice"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
    }

    private int messages(String id, String recipient, String kind) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE application_id=? AND recipient_id=? AND kind=?",
                Integer.class, id, recipient, kind);
    }

    private String submitted() throws Exception {
        String id = created(graph(false));
        submit(id, 1).andExpect(status().isOk());
        return id;
    }

    private Graph graph(boolean secondNode) {
        var nodes = new java.util.ArrayList<>(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("review", "财务会签", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE", "approvalMode", "ALL")),
                new Node("end", "结束", NodeType.END, Map.of())));
        var edges = new java.util.ArrayList<>(List.of(new Edge("begin", "start", "review", ""),
                new Edge("finish", "review", secondNode ? "second" : "end", "")));
        if (secondNode) {
            nodes.add(new Node("second", "再次会签", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE", "approvalMode", "ALL")));
            edges.add(new Edge("last", "second", "end", ""));
        }
        return new Graph(nodes, edges);
    }

    private String created(Graph graph) throws Exception {
        String key = "countersign-" + UUID.randomUUID();
        var definition = definitions.create("demo", key, "全员会签", graph);
        definitions.publish(new Actor("demo", "admin", Set.of("ADMIN")), definition.id(), 0, "全员同意才通过");
        JsonNode application = json.read(mvc.perform(post("/api/v1/applications").header("Authorization", token("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("businessNo", "CS-" + UUID.randomUUID(),
                                "processKey", key, "definitionVersion", 1, "title", "全员会签验收", "payload", Map.of()))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), JsonNode.class);
        return application.path("id").asText();
    }

    private ResultActions submit(String id, long version) throws Exception {
        return mvc.perform(post("/api/v1/applications/" + id + "/submit").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("expectedVersion", version))));
    }

    private List<Task> pending(String id) {
        return tasks.createTaskQuery().processVariableValueEquals("applicationId", id).list();
    }
    private Task assigned(String id, String user) { return pending(id).stream().filter(task -> user.equals(task.getAssignee())).findFirst().orElseThrow(); }
    private ResultActions act(Task task, String user, String action, long version, String target) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("action", action); body.put("expectedVersion", version); body.put("comment", "会签操作意见");
        if (target != null) body.put("targetUser", target);
        return mvc.perform(post("/api/v1/tasks/" + task.getId() + "/actions").header("Authorization", token(user))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(body)));
    }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
}
