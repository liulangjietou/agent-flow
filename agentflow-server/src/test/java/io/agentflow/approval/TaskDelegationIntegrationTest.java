package io.agentflow.approval;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.DelegationState;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import io.agentflow.approval.service.TaskAuditPort;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.approval.process.FlowableTaskFacade;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static io.agentflow.definition.DefinitionModels.*;
import static io.agentflow.support.MutationRequests.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用真实 HTTP、Flowable 和业务事务验证委派、回交与责任人链路。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:task-delegation;DB_CLOSE_DELAY=-1",
        "agentflow.auth.demo-enabled=true", "agentflow.auth.demo-tenant=demo"})
@AutoConfigureMockMvc
class TaskDelegationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired DefinitionApplicationService definitions;
    @Autowired TaskService tasks;
    @Autowired JdbcTemplate jdbc;
    @Autowired CurrentActor currentActor;
    @Autowired FlowableTaskFacade facade;
    @MockitoSpyBean TaskAuditPort taskAudit;

    @Test
    void candidateDelegationRecordsTheActualOwnerInsteadOfLeavingItUnassigned() throws Exception {
        Task task = submitted("role:FINANCE");
        act(task.getId(), "finance", "DELEGATE", "bob", 2).andExpect(status().isOk());
        Task delegated = reload(task);
        assertThat(delegated.getOwner()).isEqualTo("finance");
        assertThat(delegated.getAssignee()).isEqualTo("bob");
        assertThat(delegated.getDelegationState()).isEqualTo(DelegationState.PENDING);
    }

    @Test
    void pendingDelegationReturnsBusinessConflictInsteadOfCallingComplete() throws Exception {
        Task task = submitted("user:finance");
        act(task.getId(), "finance", "DELEGATE", "bob", 2).andExpect(status().isOk());
        act(task.getId(), "bob", "APPROVE", null, 3).andExpect(status().isConflict())
                .andExpect(jsonPath("code").value("TASK_DELEGATION_PENDING"));
        assertThat(reload(task).getDelegationState()).isEqualTo(DelegationState.PENDING);
        assertVersion(task, 3);
    }

    @Test
    void resolveReturnsToOriginalOwnerWithoutCompletingApplicationAndKeepsBothParticipants() throws Exception {
        Task task = submitted("user:finance");
        act(task.getId(), "finance", "DELEGATE", "bob", 2).andExpect(status().isOk());
        act(task.getId(), "bob", "RESOLVE", "forged-recipient", 3).andExpect(status().isOk())
                .andExpect(jsonPath("applicationStatus").value("IN_APPROVAL"));
        assertThat(reload(task).getAssignee()).isEqualTo("finance");
        assertThat(reload(task).getDelegationState()).isEqualTo(DelegationState.RESOLVED);
        String id = applicationId(task);
        mvc.perform(get("/api/v1/applications/" + id).header("Authorization", token("bob"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/applications/" + id).header("Authorization", token("employee"))).andExpect(status().isNotFound());
        JsonNode audit = json.read(jdbc.queryForObject("SELECT payload_json FROM audit_event WHERE aggregate_id=? AND action='RESOLVE'", String.class, task.getId()), JsonNode.class);
        assertThat(audit.path("actor").asText()).isEqualTo("bob");
        assertThat(audit.path("targetUser").asText()).isEqualTo("finance");
        act(task.getId(), "finance", "APPROVE", null, 4).andExpect(status().isOk())
                .andExpect(jsonPath("applicationStatus").value("APPROVED"));
    }

    @Test
    void delegationAfterTransferUsesTheNewOwner() throws Exception {
        Task task = submitted("user:finance");
        act(task.getId(), "finance", "DELEGATE", "bob", 2).andExpect(status().isOk());
        act(task.getId(), "bob", "RESOLVE", null, 3).andExpect(status().isOk());
        act(task.getId(), "finance", "TRANSFER", "manager", 4).andExpect(status().isOk());
        assertThat(reload(task).getOwner()).isNull();
        assertThat(reload(task).getDelegationState()).isNull();
        act(task.getId(), "manager", "DELEGATE", "alice", 5).andExpect(status().isOk());
        assertThat(reload(task).getOwner()).isEqualTo("manager");
    }

    @Test
    void nonexistentRecipientCannotOrphanTheTask() throws Exception {
        Task task = submitted("user:finance");
        act(task.getId(), "finance", "DELEGATE", "missing-user", 2).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("code").value("INVALID_TASK_RECIPIENT"));
        assertThat(reload(task).getAssignee()).isEqualTo("finance");
        assertVersion(task, 2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVE", "RETURN", "REJECT", "TRANSFER", "DELEGATE", "CLAIM", "RELEASE"})
    void delegateCannotBypassReturnToOwnerWithAnotherAction(String action) throws Exception {
        Task task = submitted("user:finance");
        act(task.getId(), "finance", "DELEGATE", "bob", 2).andExpect(status().isOk());
        act(task.getId(), "bob", action, "manager", 3).andExpect(status().isConflict());
        assertThat(reload(task).getAssignee()).isEqualTo("bob");
        assertVersion(task, 3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE aggregate_id=?", Integer.class, task.getId())).isEqualTo(1);
    }

    @Test
    void taskViewAndRecipientDirectoryRespectCurrentAssignmentAndTenant() throws Exception {
        Task task = submitted("role:FINANCE");
        mvc.perform(get("/api/v1/tasks/" + task.getId() + "/recipients").header("Authorization", token("finance")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@ == 'finance')]").isEmpty())
                .andExpect(jsonPath("$[?(@ == 'bob')]").isNotEmpty());
        mvc.perform(get("/api/v1/tasks/" + task.getId() + "/recipients").header("Authorization", token("employee"))).andExpect(status().isForbidden());
        act(task.getId(), "finance", "DELEGATE", "finance", 2).andExpect(status().isUnprocessableEntity());
        act(task.getId(), "finance", "RESOLVE", null, 2).andExpect(status().isUnprocessableEntity());
        act(task.getId(), "finance", "DELEGATE", "bob", 2).andExpect(status().isOk());
        mvc.perform(get("/api/v1/tasks").header("Authorization", token("bob"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.taskId == '" + task.getId() + "')].owner").value("finance"))
                .andExpect(jsonPath("$[?(@.taskId == '" + task.getId() + "')].delegationState").value("PENDING"));
        currentActor.set(new Actor("demo", "bob", Set.of("APPROVER")));
        try { assertThat(facade.list("pending").stream().filter(value -> value.taskId().equals(task.getId())).findFirst().orElseThrow().allowedActions())
                .containsExactly(io.agentflow.approval.model.TaskAction.RESOLVE); }
        finally { currentActor.clear(); }
        mvc.perform(get("/api/v1/tasks/" + task.getId() + "/recipients").header("Authorization", token("bob"))).andExpect(status().isConflict());
        act(task.getId(), "finance", "RESOLVE", null, 3).andExpect(status().isForbidden());
        act(task.getId(), "employee", "RESOLVE", null, 3).andExpect(status().isForbidden());
        currentActor.set(new Actor("other-tenant", "bob", Set.of("APPROVER", "ADMIN")));
        try { assertThatThrownBy(() -> facade.action(task.getId(), "RESOLVE", "跨租户", null, 3L))
                .isInstanceOfSatisfying(DomainException.class, failure -> assertThat(failure.code()).isEqualTo("NOT_FOUND")); }
        finally { currentActor.clear(); }
        assertThat(auth.approvers("other-tenant")).isEmpty();
        assertThat(new AuthService(false, "demo").approvers("demo")).isEmpty();
    }

    @Test
    void staleVersionMissingCommentAndUnknownOwnerDoNotResolveOrAppendHistory() throws Exception {
        Task task = submitted("user:finance");
        act(task.getId(), "finance", "DELEGATE", "bob", 2).andExpect(status().isOk());
        act(task.getId(), "bob", "RESOLVE", null, 2).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/tasks/" + task.getId() + "/actions").header("Authorization", token("bob"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"RESOLVE\",\"expectedVersion\":3,\"comment\":\" \"}"))
                .andExpect(status().isUnprocessableEntity());
        Task legacy = reload(task); legacy.setOwner(null); tasks.saveTask(legacy);
        act(task.getId(), "bob", "RESOLVE", null, 3).andExpect(status().isConflict())
                .andExpect(jsonPath("code").value("TASK_DELEGATION_OWNER_MISSING"));
        assertThat(reload(task).getAssignee()).isEqualTo("bob");
        assertThat(reload(task).getDelegationState()).isEqualTo(DelegationState.PENDING);
        assertVersion(task, 3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE aggregate_id=? AND action='RESOLVE'", Integer.class, task.getId())).isZero();
    }

    @Test
    void auditFailureRollsBackResolutionAssignmentCommentAndVersion() throws Exception {
        Task task = submitted("user:finance");
        act(task.getId(), "finance", "DELEGATE", "bob", 2).andExpect(status().isOk());
        int comments = tasks.getTaskComments(task.getId()).size();
        doAnswer(invocation -> { invocation.callRealMethod(); throw new DomainException("DEPENDENCY_UNAVAILABLE", "Audit transaction failed"); })
                .when(taskAudit).record(any());
        act(task.getId(), "bob", "RESOLVE", null, 3).andExpect(status().isServiceUnavailable());
        assertThat(reload(task).getAssignee()).isEqualTo("bob");
        assertThat(reload(task).getDelegationState()).isEqualTo(DelegationState.PENDING);
        assertThat(tasks.getTaskComments(task.getId())).hasSize(comments);
        assertVersion(task, 3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE aggregate_id=? AND action='RESOLVE'", Integer.class, task.getId())).isZero();
    }

    @Test
    void concurrentResolutionReplaysOriginalResultEvenAfterFinalApproval() throws Exception {
        Task task = submitted("user:finance");
        act(task.getId(), "finance", "DELEGATE", "bob", 2).andExpect(status().isOk());
        String key = UUID.randomUUID().toString(), token = token("bob");
        var executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        String original;
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (int index = 0; index < 4; index++) futures.add(executor.submit(() -> resolveWithKey(task, token, key)));
            var results = new java.util.ArrayList<String>();
            for (var future : futures) results.add(future.get(15, java.util.concurrent.TimeUnit.SECONDS));
            original = results.get(0);
            assertThat(results).allMatch(original::equals);
        } finally { executor.shutdownNow(); }
        assertVersion(task, 4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE aggregate_id=? AND action='RESOLVE'", Integer.class, task.getId())).isEqualTo(1);
        act(task.getId(), "finance", "APPROVE", null, 4).andExpect(status().isOk());
        assertThat(resolveWithKey(task, token, key)).isEqualTo(original);
        mvc.perform(get("/api/v1/workspace/handled").param("q", task.getProcessVariables().get("businessNo").toString()).param("action", "RESOLVE")
                .header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("items.length()").value(1)).andExpect(jsonPath("items[0].applicationStatus").value("APPROVED"))
                .andExpect(jsonPath("items[0].handledStatus").value("IN_APPROVAL"));
        mvc.perform(get("/api/v1/applications/" + applicationId(task) + "/audit?action=RESOLVE").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("items.length()").value(1));
    }

    private String resolveWithKey(Task task, String token, String key) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/tasks/" + task.getId() + "/actions")
                .header("Authorization", token).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"RESOLVE\",\"expectedVersion\":3,\"comment\":\"回交核实意见\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private Task submitted(String rule) throws Exception {
        String key = "delegation-" + UUID.randomUUID();
        var definition = definitions.create("demo", key, "委派回交流程", new Graph(List.of(
                new Node("start", "开始", NodeType.START, Map.of()),
                new Node("review", "审批", NodeType.USER_TASK, Map.of("assigneeRule", rule)),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("begin", "start", "review", ""), new Edge("finish", "review", "end", ""))));
        definitions.publish(new Actor("demo", "admin", Set.of("ADMIN")), definition.id(), 0, "委派回交验收");
        JsonNode draft = json.read(mvc.perform(post("/api/v1/applications").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("businessNo", "DG-" + UUID.randomUUID(),
                        "processKey", key, "definitionVersion", 1, "title", "委派验证", "payload", Map.of()))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), JsonNode.class);
        String id = draft.path("id").asText();
        mvc.perform(post("/api/v1/applications/" + id + "/submit").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}"))
                .andExpect(status().isOk());
        return tasks.createTaskQuery().processVariableValueEquals("applicationId", id).includeProcessVariables().singleResult();
    }

    private ResultActions act(String taskId, String user, String action, String target, long version) throws Exception {
        var body = new HashMap<String, Object>(Map.of("action", action, "expectedVersion", version, "comment", "已核对并提供办理意见"));
        if (target != null) body.put("targetUser", target);
        return mvc.perform(post("/api/v1/tasks/" + taskId + "/actions").header("Authorization", token(user))
                .contentType(MediaType.APPLICATION_JSON).content(json.write(body)));
    }
    private void assertVersion(Task task, long version) {
        assertThat(jdbc.queryForObject("SELECT version FROM approval_application WHERE id=?", Long.class, applicationId(task))).isEqualTo(version);
    }
    private String applicationId(Task task) { return task.getProcessVariables().get("applicationId").toString(); }
    private Task reload(Task task) { return tasks.createTaskQuery().taskId(task.getId()).singleResult(); }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
}
