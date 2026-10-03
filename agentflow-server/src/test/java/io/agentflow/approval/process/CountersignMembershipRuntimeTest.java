package io.agentflow.approval.process;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static io.agentflow.definition.DefinitionModels.*;
import static io.agentflow.support.MutationRequests.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 实际 Flowable 7.2 多实例验证；公开授权、版本、审计及通知在应用层接续验收。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = "agentflow.auth.demo-enabled=true")
@AutoConfigureMockMvc
class CountersignMembershipRuntimeTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired DefinitionApplicationService definitions;
    @Autowired FlowableCountersignRuntime membership;
    @Autowired TaskService tasks;
    @Autowired HistoryService history;
    @Autowired PlatformTransactionManager transactions;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_COUNTERSIGN_TEST_URL", "jdbc:h2:mem:countersign-membership-runtime;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_COUNTERSIGN_TEST_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_COUNTERSIGN_TEST_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_COUNTERSIGN_TEST_PASSWORD", ""));
    }

    @Test
    void addedMemberIsAnActualRequiredTaskAndOriginalSnapshotStaysFrozen() throws Exception {
        String application = submit(graph(false, false));
        Task finance = task(application, "review", "finance"), admin = task(application, "review", "admin");
        var before = membership.read(finance);
        var added = transaction(() -> membership.add(finance, "bob"));
        assertThat(added.totalBefore()).isEqualTo(2);
        assertThat(added.totalAfter()).isEqualTo(3);
        var after = membership.read(finance);
        assertThat(after.originalMembers()).isEqualTo(before.originalMembers());
        assertThat(after.membership().pending()).extracting(io.agentflow.approval.model.CountersignMembership.Member::taskId)
                .containsExactlyInAnyOrder(finance.getId(), admin.getId(), added.targetTaskId());
        act(finance, "finance", "APPROVE", 2, null);
        act(admin, "admin", "APPROVE", 3, null);
        Task bob = task(application, "review", "bob");
        assertThat(membership.read(bob).membership().completed()).isEqualTo(2);
        assertThat(pending(application)).hasSize(1);
        assertThat(act(bob, "bob", "APPROVE", 4, null).path("applicationStatus").asText()).isEqualTo("APPROVED");
    }

    @Test
    void removalKeepsCompletedOpinionAndReadditionCreatesAnotherNecessaryTask() throws Exception {
        String application = submit(graph(false, false));
        Task finance = task(application, "review", "finance"), admin = task(application, "review", "admin");
        act(finance, "finance", "APPROVE", 2, null);
        var originalDecision = history.createHistoricTaskInstanceQuery().taskId(finance.getId()).singleResult();
        var first = transaction(() -> membership.add(admin, "bob"));
        var removed = transaction(() -> membership.remove(admin, first.targetTaskId()));
        assertThat(removed.totalAfter()).isEqualTo(2);
        assertThat(removed.completed()).isEqualTo(1);
        var canceled = history.createHistoricTaskInstanceQuery().taskId(first.targetTaskId()).singleResult();
        assertThat(canceled.getEndTime()).isNotNull();
        assertThat(canceled.getDeleteReason()).isNotBlank();
        var second = transaction(() -> membership.add(admin, "bob"));
        assertThat(second.targetTaskId()).isNotEqualTo(first.targetTaskId());
        assertThat(membership.read(admin).membership().completedUsers()).containsExactly("finance");
        var retained = history.createHistoricTaskInstanceQuery().taskId(finance.getId()).singleResult();
        assertThat(retained.getEndTime()).isEqualTo(originalDecision.getEndTime());
        assertThat(tasks.getTaskComments(finance.getId())).hasSize(1);
        act(admin, "admin", "APPROVE", 3, null);
        assertThat(pending(application)).extracting(Task::getId).containsExactly(second.targetTaskId());
        assertThat(act(task(application, "review", "bob"), "bob", "APPROVE", 4, null).path("applicationStatus").asText()).isEqualTo("APPROVED");
    }

    @Test
    void completedAndFinalPendingMembersCannotBeRemovedOrAddedTwice() throws Exception {
        String application = submit(graph(false, false));
        Task finance = task(application, "review", "finance"), admin = task(application, "review", "admin");
        rejects(() -> transaction(() -> membership.add(admin, "finance")), "COUNTERSIGN_MEMBER_EXISTS");
        act(finance, "finance", "APPROVE", 2, null);
        var before = membership.read(admin);
        rejects(() -> transaction(() -> membership.remove(admin, finance.getId())), "COUNTERSIGN_MEMBER_UNAVAILABLE");
        rejects(() -> transaction(() -> membership.remove(admin, admin.getId())), "COUNTERSIGN_LAST_MEMBER");
        rejects(() -> transaction(() -> membership.add(admin, "finance")), "COUNTERSIGN_MEMBER_EXISTS");
        assertThat(membership.read(admin)).isEqualTo(before);
    }

    @Test
    void delegationKeepsOriginalResponsibilityAndMustBeResolvedBeforeMembershipChanges() throws Exception {
        String application = submit(graph(false, false));
        Task finance = task(application, "review", "finance"), admin = task(application, "review", "admin");
        act(finance, "finance", "DELEGATE", 2, "bob");
        var delegated = membership.read(finance).membership().pending().stream().filter(member -> member.taskId().equals(finance.getId())).findFirst().orElseThrow();
        assertThat(delegated.user()).isEqualTo("finance");
        assertThat(delegated.assignee()).isEqualTo("bob");
        rejects(() -> transaction(() -> membership.add(finance, "manager")), "TASK_DELEGATION_PENDING");
        rejects(() -> transaction(() -> membership.remove(admin, finance.getId())), "TASK_DELEGATION_PENDING");
        act(finance, "bob", "RESOLVE", 3, null);
        transaction(() -> membership.remove(admin, finance.getId()));
        assertThat(membership.read(admin).membership().completed()).isZero();
        assertThat(act(admin, "admin", "APPROVE", 4, null).path("applicationStatus").asText()).isEqualTo("APPROVED");
    }

    @Test
    void anOuterTransactionFailureRollsBackBothEngineTasksAndTheirHistory() throws Exception {
        String application = submit(graph(false, false));
        Task finance = task(application, "review", "finance"), admin = task(application, "review", "admin");
        var before = membership.read(admin);
        long historyCount = history.createHistoricTaskInstanceQuery().processInstanceId(admin.getProcessInstanceId()).count();
        assertThatThrownBy(() -> transaction(() -> {
            membership.add(admin, "bob");
            membership.remove(admin, finance.getId());
            throw new IllegalStateException("Synthetic audit failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(membership.read(admin)).isEqualTo(before);
        assertThat(history.createHistoricTaskInstanceQuery().processInstanceId(admin.getProcessInstanceId()).count()).isEqualTo(historyCount);
    }

    @Test
    void parallelNodesStayIsolatedAndSingleTasksDoNotBecomeCountersign() throws Exception {
        String application = submit(graph(true, false));
        Task first = task(application, "review", "finance"), second = task(application, "other", "finance");
        var secondBefore = membership.read(second);
        transaction(() -> membership.add(first, "bob"));
        rejects(() -> transaction(() -> membership.remove(first, second.getId())), "COUNTERSIGN_MEMBER_UNAVAILABLE");
        assertThat(membership.read(second)).isEqualTo(secondBefore);
        String single = submit(graph(false, true));
        Task singleTask = pending(single).get(0);
        rejects(() -> transaction(() -> membership.add(singleTask, "bob")), "COUNTERSIGN_STATE_INVALID");
        assertThat(pending(single)).hasSize(1);
    }

    @Test
    void mutationRequiresTheCallingApplicationTransaction() throws Exception {
        String application = submit(graph(false, false));
        assertThatThrownBy(() -> membership.add(task(application, "review", "finance"), "bob"))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(pending(application)).hasSize(2);
    }

    private Graph graph(boolean parallel, boolean single) {
        var nodes = new java.util.ArrayList<>(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("review", "财务会签", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE", "approvalMode", single ? "SINGLE" : "ALL")),
                new Node("end", "结束", NodeType.END, Map.of())));
        if (!parallel) return new Graph(nodes, List.of(new Edge("begin", "start", "review", ""), new Edge("finish", "review", "end", "")));
        nodes.add(new Node("split", "并行", NodeType.PARALLEL_GATEWAY, Map.of()));
        nodes.add(new Node("join", "汇合", NodeType.PARALLEL_GATEWAY, Map.of()));
        nodes.add(new Node("other", "另一会签", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE", "approvalMode", "ALL")));
        return new Graph(nodes, List.of(new Edge("begin", "start", "split", ""), new Edge("left", "split", "review", ""),
                new Edge("right", "split", "other", ""), new Edge("leftEnd", "review", "join", ""),
                new Edge("rightEnd", "other", "join", ""), new Edge("finish", "join", "end", "")));
    }

    private String submit(Graph graph) throws Exception {
        String key = "membership-" + UUID.randomUUID();
        var definition = definitions.create("demo", key, "会签增减引擎验收", graph);
        definitions.publish(new Actor("demo", "admin", Set.of("ADMIN")), definition.id(), 0, "验证实际多实例变更");
        String body = json.write(Map.of("businessNo", key, "processKey", key, "definitionVersion", 1, "title", "会签增减", "payload", Map.of()));
        String id = json.read(mvc.perform(post("/api/v1/applications").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), JsonNode.class).path("id").asText();
        mvc.perform(post("/api/v1/applications/" + id + "/submit").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}")).andExpect(status().isOk());
        return id;
    }

    private List<Task> pending(String application) { return tasks.createTaskQuery().processVariableValueEquals("applicationId", application).list(); }
    private Task task(String application, String node, String user) {
        return pending(application).stream().filter(task -> node.equals(task.getTaskDefinitionKey()) && user.equals(task.getAssignee())).findFirst().orElseThrow();
    }
    private JsonNode act(Task task, String user, String action, long version, String target) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("action", action); body.put("expectedVersion", version); body.put("comment", "保留实际会签意见");
        if (target != null) body.put("targetUser", target);
        return json.read(mvc.perform(post("/api/v1/tasks/" + task.getId() + "/actions").header("Authorization", token(user))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(body))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
    private <T> T transaction(Supplier<T> work) { return new TransactionTemplate(transactions).execute(status -> work.get()); }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private void rejects(Runnable work, String code) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
}
