package io.agentflow.approval;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.approval.service.ProcessRuntimePort;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

import static io.agentflow.definition.DefinitionModels.*;
import static io.agentflow.support.MutationRequests.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通过真实审批接口和 Flowable 验证并行汇合、轮次终止及并发事务。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:parallel-approval;DB_CLOSE_DELAY=-1", "agentflow.auth.demo-enabled=true"})
@AutoConfigureMockMvc
class ParallelApprovalIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired DefinitionApplicationService definitions;
    @Autowired TaskService tasks;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean ProcessRuntimePort runtime;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void allBranchesMustFinishInEitherOrderBeforeTheFinalTask(boolean reverse) throws Exception {
        String id = submitted(false);
        assertThat(pending(id)).extracting(Task::getTaskDefinitionKey).containsExactlyInAnyOrder("manager", "finance");
        String first = reverse ? "finance" : "manager", second = reverse ? "manager" : "finance";
        act(task(id, first), "APPROVE", 2).andExpect(status().isOk()).andExpect(jsonPath("applicationStatus").value("IN_APPROVAL"));
        assertThat(pending(id)).extracting(Task::getTaskDefinitionKey).containsExactly(second);
        assertThat(messages(id, second, "TASK_PENDING")).isEqualTo(1);
        assertThat(messages(id, "alice", "APPLICATION_APPROVED")).isZero();
        act(task(id, second), "APPROVE", 3).andExpect(status().isOk());
        assertThat(pending(id)).extracting(Task::getTaskDefinitionKey).containsExactly("final");
        assertThat(messages(id, "admin", "TASK_PENDING")).isEqualTo(1);
        act(task(id, "final"), "APPROVE", 4).andExpect(status().isOk()).andExpect(jsonPath("applicationStatus").value("APPROVED"));
        assertThat(pending(id)).isEmpty();
        assertThat(messages(id, "alice", "APPLICATION_APPROVED")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REJECT", "RETURN"})
    void aBranchDecisionTerminatesTheWholeRoundWithoutStartingTheFinalTask(String action) throws Exception {
        String id = submitted(false);
        act(task(id, "finance"), "APPROVE", 2).andExpect(status().isOk());
        act(task(id, "manager"), action, 3).andExpect(status().isOk())
                .andExpect(jsonPath("applicationStatus").value(action.equals("RETURN") ? "RETURNED" : "REJECTED"));
        assertThat(pending(id)).isEmpty();
        assertThat(messages(id, "admin", "TASK_PENDING")).isZero();
        assertThat(messages(id, "alice", "APPLICATION_APPROVED")).isZero();
    }

    @Test
    void withdrawalClearsAllBranchesAndResubmissionCreatesANewRound() throws Exception {
        String id = submitted(false);
        var original = pending(id).stream().map(Task::getId).toList();
        mvc.perform(post("/api/v1/applications/" + id + "/withdraw").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("expectedVersion", 2, "reason", "修正申请"))))
                .andExpect(status().isOk());
        assertThat(pending(id)).isEmpty();
        submit(id, 3).andExpect(status().isOk());
        assertThat(pending(id)).hasSize(2).extracting(Task::getId).doesNotContainAnyElementsOf(original);
        mvc.perform(get("/api/v1/applications/" + id + "/rounds").header("Authorization", token("alice")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void countersignWithinOneBranchDoesNotReleaseTheOuterJoinEarly() throws Exception {
        String id = submitted(true);
        assertThat(pending(id)).hasSize(3);
        Task admin = pending(id).stream().filter(task -> task.getAssignee().equals("admin")).findFirst().orElseThrow();
        act(admin, "APPROVE", 2).andExpect(status().isOk());
        act(task(id, "finance"), "APPROVE", 3).andExpect(status().isOk());
        assertThat(pending(id)).extracting(Task::getTaskDefinitionKey).containsExactly("manager");
        act(task(id, "manager"), "APPROVE", 4).andExpect(status().isOk());
        act(task(id, "final"), "APPROVE", 5).andExpect(status().isOk()).andExpect(jsonPath("applicationStatus").value("APPROVED"));
    }

    @Test
    void concurrentBranchesRollbackTheLoserThenJoinOnceAfterRetry() throws Exception {
        String id = submitted(false);
        Task manager = task(id, "manager"), finance = task(id, "finance");
        CyclicBarrier barrier = new CyclicBarrier(2);
        doAnswer(invocation -> { barrier.await(5, TimeUnit.SECONDS); return invocation.callRealMethod(); }).when(runtime).complete(any());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> act(manager, "APPROVE", 2).andReturn().getResponse().getStatus());
            var second = executor.submit(() -> act(finance, "APPROVE", 2).andReturn().getResponse().getStatus());
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            doCallRealMethod().when(runtime).complete(any());
        }
        assertThat(pending(id)).hasSize(1);
        act(pending(id).get(0), "APPROVE", 3).andExpect(status().isOk());
        assertThat(pending(id)).extracting(Task::getTaskDefinitionKey).containsExactly("final");
        assertThat(messages(id, "admin", "TASK_PENDING")).isEqualTo(1);
    }

    private String submitted(boolean countersign) throws Exception {
        String key = "parallel-" + UUID.randomUUID();
        var draft = definitions.create("demo", key, "并行审批", graph(countersign));
        definitions.publish(new Actor("demo", "admin", Set.of("ADMIN")), draft.id(), 0, "并行分支全部完成后继续");
        JsonNode application = json.read(mvc.perform(post("/api/v1/applications").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("businessNo", "PG-" + UUID.randomUUID(),
                        "processKey", key, "definitionVersion", 1, "title", "并行审批验证", "payload", Map.of()))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), JsonNode.class);
        String id = application.path("id").asText();
        submit(id, 1).andExpect(status().isOk());
        return id;
    }

    private Graph graph(boolean countersign) {
        return new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("fork", "并行拆分", NodeType.PARALLEL_GATEWAY, Map.of()),
                new Node("manager", "主管审批", NodeType.USER_TASK, Map.of("assigneeRule", "user:manager")),
                new Node("finance", "财务审批", NodeType.USER_TASK, countersign
                        ? Map.of("assigneeRule", "role:FINANCE", "approvalMode", "ALL") : Map.of("assigneeRule", "user:finance")),
                new Node("join", "全部汇合", NodeType.PARALLEL_GATEWAY, Map.of()),
                new Node("final", "最终审批", NodeType.USER_TASK, Map.of("assigneeRule", "user:admin")),
                new Node("end", "结束", NodeType.END, Map.of())), List.of(new Edge("sf", "start", "fork", ""),
                new Edge("fm", "fork", "manager", ""), new Edge("ff", "fork", "finance", ""),
                new Edge("mj", "manager", "join", ""), new Edge("fj", "finance", "join", ""),
                new Edge("jl", "join", "final", ""), new Edge("le", "final", "end", "")));
    }

    private ResultActions submit(String id, long version) throws Exception {
        return mvc.perform(post("/api/v1/applications/" + id + "/submit").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("expectedVersion", version))));
    }

    private List<Task> pending(String id) { return tasks.createTaskQuery().processVariableValueEquals("applicationId", id).list(); }
    private Task task(String id, String node) { return pending(id).stream().filter(task -> task.getTaskDefinitionKey().equals(node)).findFirst().orElseThrow(); }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private int messages(String id, String user, String kind) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE application_id=? AND recipient_id=? AND kind=?", Integer.class, id, user, kind);
    }
    private ResultActions act(Task task, String action, long version) throws Exception {
        return mvc.perform(post("/api/v1/tasks/" + task.getId() + "/actions").header("Authorization", token(task.getAssignee()))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("action", action, "expectedVersion", version, "comment", "并行审批意见"))));
    }
}
