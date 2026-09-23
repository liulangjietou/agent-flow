package io.agentflow.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import org.flowable.engine.HistoryService;
import io.agentflow.definition.DefinitionApplicationService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static io.agentflow.definition.DefinitionModels.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static io.agentflow.support.MutationRequests.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通过真实引擎和 HTTP 链路验证认证、资源隔离与审批终止语义。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:approval-security;DB_CLOSE_DELAY=-1",
        "agentflow.auth.demo-enabled=true", "agentflow.auth.demo-tenant=demo"
})
@AutoConfigureMockMvc
class ApprovalSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AuthService authService;
    @Autowired CurrentActor currentActor;
    @Autowired ApprovalApplicationFacade applications;
    @Autowired ApplicationRepository repository;
    @Autowired DefinitionApplicationService definitions;
    @Autowired RuntimeService runtime;
    @Autowired TaskService tasks;
    @Autowired HistoryService history;
    @Autowired JdbcTemplate jdbc;

    @Test
    void loginConsumesJsonBody() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"demo\",\"username\":\"employee\",\"password\":\"demo\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("token").isNotEmpty());
    }

    @Test
    void invalidTokenReturnsStructured401AndClearsContext() throws Exception {
        mvc.perform(get("/api/v1/applications").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("UNAUTHENTICATED"));
    }

    @Test
    void rejectsUnsupportedTaskStatusInsteadOfReturningActiveTasks() throws Exception {
        mvc.perform(get("/api/v1/tasks?status=completed").header("Authorization", token("finance")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("code").value("INVALID_REQUEST"));
    }

    @Test
    void unrelatedEmployeeCannotReadOrSubmitAnotherPersonsDraft() throws Exception {
        Application application = draft("alice", "demo");
        mvc.perform(get("/api/v1/applications/" + application.id()).header("Authorization", token("bob")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/applications/" + application.id() + "/submit")
                        .header("Authorization", token("bob")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isForbidden());
        assertThat(repository.findById("demo", application.id()).orElseThrow().status()).isEqualTo(ApplicationStatus.DRAFT);
    }

    @Test
    void approverRoleDoesNotGrantAnotherUsersTask() throws Exception {
        Application application = submitted("demo");
        String taskId = task(application);
        mvc.perform(get("/api/v1/tasks").header("Authorization", token("finance")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.taskId == '" + taskId + "')]").isNotEmpty());
        mvc.perform(get("/api/v1/tasks").header("Authorization", token("bob")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.taskId == '" + taskId + "')]").isEmpty());
        mvc.perform(post("/api/v1/tasks/" + taskId + "/actions").header("Authorization", token("bob"))
                        .contentType(MediaType.APPLICATION_JSON).content(action("APPROVE", 2)))
                .andExpect(status().isForbidden());
        assertThat(tasks.createTaskQuery().taskId(taskId).count()).isEqualTo(1);
    }

    @Test
    void crossTenantTaskIsInvisibleEvenToMatchingAssignee() throws Exception {
        Application application = submitted("other-tenant");
        String taskId = task(application);
        mvc.perform(get("/api/v1/applications/" + application.id()).header("Authorization", token("finance")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/tasks/" + taskId + "/actions").header("Authorization", token("finance"))
                        .contentType(MediaType.APPLICATION_JSON).content(action("APPROVE", 2)))
                .andExpect(status().isNotFound());
        assertThat(tasks.createTaskQuery().taskId(taskId).count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RETURN", "REJECT"})
    void negativeDecisionTerminatesEntireProcess(String action) throws Exception {
        Application application = submitted("demo");
        String taskId = task(application);
        String processId = tasks.createTaskQuery().taskId(taskId).singleResult().getProcessInstanceId();
        mvc.perform(post("/api/v1/tasks/" + taskId + "/actions").header("Authorization", token("finance"))
                        .contentType(MediaType.APPLICATION_JSON).content(action(action, 2)))
                .andExpect(status().isOk());
        assertThat(runtime.createProcessInstanceQuery().processInstanceId(processId).count()).isZero();
        assertThat(tasks.createTaskQuery().processInstanceId(processId).count()).isZero();
        assertThat(repository.findById("demo", application.id()).orElseThrow().status().name())
                .isEqualTo(action.equals("RETURN") ? "RETURNED" : "REJECTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE aggregate_id=?", Integer.class,
                taskId)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVE", "RETURN", "REJECT"})
    void candidateDecisionKeepsTheActualApproverAsAnApplicationParticipant(String decision) throws Exception {
        Application application = submitted("demo");
        String taskId = task(application);
        assertThat(tasks.createTaskQuery().taskId(taskId).singleResult().getAssignee()).isNull();
        mvc.perform(get("/api/v1/applications/" + application.id()).header("Authorization", token("finance")))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/tasks/" + taskId + "/actions").header("Authorization", token("finance"))
                        .contentType(MediaType.APPLICATION_JSON).content(action(decision, 2)))
                .andExpect(status().isOk());

        assertThat(history.createHistoricTaskInstanceQuery().taskId(taskId).singleResult().getAssignee())
                .isEqualTo("finance");
        mvc.perform(get("/api/v1/applications/" + application.id()).header("Authorization", token("finance")))
                .andExpect(status().isOk()).andExpect(jsonPath("id").value(application.id().toString()));
        mvc.perform(get("/api/v1/applications").header("Authorization", token("finance")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + application.id() + "')]").isNotEmpty());
        mvc.perform(get("/api/v1/applications/" + application.id()).header("Authorization", token("bob")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/applications").header("Authorization", token("bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + application.id() + "')]").isEmpty());
    }

    @Test
    void staleTaskVersionCannotPerformAnotherAction() throws Exception {
        Application application = submitted("demo");
        String taskId = task(application);
        mvc.perform(post("/api/v1/tasks/" + taskId + "/actions").header("Authorization", token("finance"))
                        .contentType(MediaType.APPLICATION_JSON).content(action("CLAIM", 2)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/tasks/" + taskId + "/actions").header("Authorization", token("finance"))
                        .contentType(MediaType.APPLICATION_JSON).content(action("APPROVE", 2)))
                .andExpect(status().isConflict());
        assertThat(tasks.createTaskQuery().taskId(taskId).count()).isEqualTo(1);
    }

    private Application submitted(String tenant) {
        Application application = draft("alice", tenant);
        currentActor.set(new Actor(tenant, "alice", Set.of("EMPLOYEE", "APPROVER")));
        try { return applications.submit(application.id(), 1); }
        finally { currentActor.clear(); }
    }

    private Application draft(String user, String tenant) {
        String key = "security-" + UUID.randomUUID();
        // 安全用例使用平台正式发布链路建立相同的财务、经理两级审批，避免只有引擎定义而没有业务版本。
        var definition = definitions.create(tenant, key, "安全验证流程", new Graph(List.of(
                new Node("start", "开始", NodeType.START, Map.of()),
                new Node("finance", "Finance", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE")),
                new Node("manager", "Manager", NodeType.USER_TASK, Map.of("assigneeRule", "user:manager")),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("first", "start", "finance", ""), new Edge("next", "finance", "manager", ""),
                        new Edge("last", "manager", "end", ""))));
        definitions.publish(tenant, definition.id(), 0);
        currentActor.set(new Actor(tenant, user, Set.of("EMPLOYEE", "APPROVER")));
        try { return applications.create("TEST-" + UUID.randomUUID(), key, 1, "Approval security", Map.of()); }
        finally { currentActor.clear(); }
    }

    private String task(Application application) {
        return tasks.createTaskQuery().processVariableValueEquals("applicationId", application.id().toString())
                .singleResult().getId();
    }

    private String token(String user) { return "Bearer " + authService.login("demo", user, "demo").token(); }
    private String action(String name, long version) throws Exception {
        return mapper.writeValueAsString(Map.of("action", name, "expectedVersion", version, "comment", "Review reason"));
    }
}
