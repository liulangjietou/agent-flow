package io.agentflow.approval;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static io.agentflow.definition.DefinitionModels.*;
import static io.agentflow.support.MutationRequests.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 真实引擎验证轮次绑定、会签数量、历史隔离与读取权限。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:round-diagram;DB_CLOSE_DELAY=-1", "agentflow.auth.demo-enabled=true"})
@AutoConfigureMockMvc
class RoundDiagramIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired CurrentActor current;
    @Autowired ApprovalApplicationFacade applications;
    @Autowired DefinitionApplicationService definitions;
    @Autowired TaskService tasks;
    @Autowired HistoryService history;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonUtil json;

    @Test
    void realBranchAndEachRoundStaySeparateFromLaterDefinitionsAndConclusions() throws Exception {
        String key = "diagram-" + UUID.randomUUID();
        publish("demo", key, false, "部门审批 v1");
        UUID id = submitted(key);
        JsonNode first = diagram(id, 1, "alice");
        assertThat(node(first, "manager").path("state").asText()).isEqualTo("ACTIVE");
        assertThat(node(first, "manager").path("activeTasks").asLong()).isEqualTo(1);
        assertThat(node(first, "finance").path("state").asText()).isEqualTo("NOT_REACHED");
        assertThat(first.toString()).doesNotContain("formData", "assigneeRule", "role:MANAGER", "tenantId", "sensitive-value");
        action(id, "manager", "RETURN");
        JsonNode returned = diagram(id, 1, "alice");
        assertThat(returned.path("status").asText()).isEqualTo("RETURNED");
        assertThat(node(returned, "manager").path("state").asText()).isEqualTo("LEFT");
        assertThat(node(returned, "end").path("state").asText()).isEqualTo("NOT_REACHED");
        publish("demo", key, false, "部门审批 v2 不可串入历史");
        asAlice(() -> applications.submit(id, applications.get(id).version()));
        action(id, "manager", "APPROVE");
        JsonNode second = diagram(id, 2, "alice");
        assertThat(second.path("definitionVersion").asInt()).isEqualTo(1);
        assertThat(node(second, "manager").path("name").asText()).isEqualTo("部门审批 v1");
        assertThat(node(second, "finance").path("state").asText()).isEqualTo("ACTIVE");
        assertThat(node(diagram(id, 1, "alice"), "finance").path("state").asText()).isEqualTo("NOT_REACHED");
        action(id, "finance", "APPROVE");
        JsonNode approved = diagram(id, 2, "alice");
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(node(approved, "end").path("state").asText()).isEqualTo("LEFT");
        assertThat(approved.path("nodes")).allSatisfy(n -> assertThat(n.path("activeTasks").asInt()).isZero());
        assertThat(approved.path("edges")).anySatisfy(e -> assertThat(e.path("defaultBranch").asBoolean()).isTrue());
    }

    @Test
    void countersignReadsActualRemainingTasksAndRejectionDoesNotReachEnd() throws Exception {
        String key = "diagram-all-" + UUID.randomUUID();
        publish("demo", key, true, "财务会签");
        UUID id = submitted(key);
        assertThat(node(diagram(id, 1, "alice"), "manager").path("activeTasks").asInt()).isEqualTo(2);
        action(id, "finance", "APPROVE");
        JsonNode partial = diagram(id, 1, "alice");
        assertThat(node(partial, "manager").path("state").asText()).isEqualTo("ACTIVE");
        assertThat(node(partial, "manager").path("activeTasks").asInt()).isEqualTo(1);
        action(id, "admin", "REJECT");
        JsonNode rejected = diagram(id, 1, "alice");
        assertThat(rejected.path("status").asText()).isEqualTo("REJECTED");
        assertThat(node(rejected, "manager").path("state").asText()).isEqualTo("LEFT");
        assertThat(node(rejected, "end").path("state").asText()).isEqualTo("NOT_REACHED");
    }

    @Test
    void authorizationTenantBindingAndReadOnlyContractAreEnforced() throws Exception {
        UUID id = submitted("expense-reimbursement");
        long auditCount = jdbc.queryForObject("select count(*) from audit_event", Long.class);
        mvc.perform(get(path(id, 1))).andExpect(status().isUnauthorized());
        mvc.perform(get(path(id, 1)).header("Authorization", token("bob"))).andExpect(status().isNotFound());
        diagram(id, 1, "alice"); diagram(id, 1, "finance"); diagram(id, 1, "admin");
        assertThat(jdbc.queryForObject("select count(*) from audit_event", Long.class)).isEqualTo(auditCount);
        for (String round : List.of("0", "-1", "1.5", "2147483648")) {
            mvc.perform(get("/api/v1/applications/" + id + "/rounds/" + round + "/diagram")
                    .header("Authorization", token("alice"))).andExpect(status().isBadRequest());
        }
        mvc.perform(get(path(id, 1)).param("tenantId", "other").header("Authorization", token("alice"))).andExpect(status().isBadRequest());
        mvc.perform(get(path(id, 2)).header("Authorization", token("alice"))).andExpect(status().isNotFound());
        // 即使同一账号、同一申请标识，认证租户也不能读取另一租户轮次。
        jdbc.update("update approval_application set tenant_id='other' where id=?", id.toString());
        mvc.perform(get(path(id, 1)).header("Authorization", token("admin"))).andExpect(status().isNotFound());
    }

    @Test
    void globalAndTenantSameKeyVersionNeverSubstituteForTheBoundProcess() throws Exception {
        UUID id = submitted("expense-reimbursement");
        publish("demo", "expense-reimbursement", false, "同号租户流程");
        JsonNode result = diagram(id, 1, "alice");
        assertThat(result.path("nodes")).hasSize(3);
        assertThat(node(result, "finance-approval").path("name").asText()).isEqualTo("财务审批");
        assertThat(result.toString()).doesNotContain("同号租户流程");
    }

    @Test
    void missingAndMismatchedHistoryFailsClosedInsteadOfReconstructingADiagram() throws Exception {
        String key = "diagram-missing-" + UUID.randomUUID(); publish("demo", key, false, "绑定测试");
        UUID first = submitted(key), other = submitted(key);
        String instance = tasks.createTaskQuery().processVariableValueEquals("applicationId", other.toString()).singleResult().getProcessInstanceId();
        String firstInstance = tasks.createTaskQuery().processVariableValueEquals("applicationId", first.toString()).singleResult().getProcessInstanceId();
        jdbc.update("update approval_submission_round set process_instance_id=? where application_id=?", "temporary-unbound", other.toString());
        jdbc.update("update approval_submission_round set process_instance_id=? where application_id=?", instance, first.toString());
        mvc.perform(get(path(first, 1)).header("Authorization", token("alice"))).andExpect(status().isNotFound());
        jdbc.update("update approval_submission_round set process_instance_id=? where application_id=?", firstInstance, first.toString());
        jdbc.update("update approval_submission_round set process_instance_id=? where application_id=?", instance, other.toString());
        asAlice(() -> applications.withdraw(other, applications.get(other).version(), "撤回"));
        assertThat(diagram(other, 1, "alice").path("status").asText()).isEqualTo("WITHDRAWN");
        history.deleteHistoricProcessInstance(instance);
        mvc.perform(get(path(other, 1)).header("Authorization", token("alice"))).andExpect(status().isNotFound());
    }

    private void publish(String tenant, String key, boolean all, String label) {
        Map<String, String> rule = all ? Map.of("assigneeRule", "role:FINANCE", "approvalMode", "ALL") : Map.of("assigneeRule", "role:MANAGER");
        Graph graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()), new Node("manager", label, NodeType.USER_TASK, rule),
                new Node("gate", "金额分支", NodeType.EXCLUSIVE_GATEWAY, Map.of()), new Node("finance", "财务复核", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE")),
                new Node("end", "结束", NodeType.END, Map.of())), List.of(new Edge("a", "start", "manager", ""), new Edge("b", "manager", "gate", ""),
                new Edge("c", "gate", "finance", "amount >= 5000"), new Edge("d", "gate", "end", "", true), new Edge("e", "finance", "end", "")));
        var draft = definitions.create(tenant, key, "流程图回归", graph);
        definitions.publish(new Actor(tenant, "admin", Set.of("ADMIN")), draft.id(), draft.revision(), "验证流程图绑定");
    }

    private UUID submitted(String key) {
        current.set(new Actor("demo", "alice", Set.of("EMPLOYEE")));
        try {
            var app = applications.create("DIAGRAM-" + UUID.randomUUID(), key, 1, "流程图验收", Map.of("amount", 6000, "description", "sensitive-value"));
            return applications.submit(app.id(), app.version()).id();
        } finally { current.clear(); }
    }
    private void asAlice(Runnable work) { current.set(new Actor("demo", "alice", Set.of("EMPLOYEE"))); try { work.run(); } finally { current.clear(); } }
    private void action(UUID id, String user, String action) throws Exception {
        var available = tasks.createTaskQuery().processVariableValueEquals("applicationId", id.toString()).list();
        var task = available.stream().filter(t -> user.equals(t.getAssignee())).findFirst().orElse(available.get(0));
        long version = jdbc.queryForObject("select version from approval_application where id=?", Long.class, id.toString());
        mvc.perform(post("/api/v1/tasks/" + task.getId() + "/actions").header("Authorization", token(user)).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("expectedVersion", version, "action", action, "comment", "验收意见")))).andExpect(status().isOk());
    }
    private JsonNode diagram(UUID id, int round, String user) throws Exception {
        var response = mvc.perform(get(path(id, round)).header("Authorization", token(user))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
        return json.read(response.getContentAsString(), JsonNode.class);
    }
    private JsonNode node(JsonNode diagram, String id) { for (JsonNode node : diagram.path("nodes")) if (node.path("id").asText().equals(id)) return node; throw new AssertionError(id); }
    private String path(UUID id, int round) { return "/api/v1/applications/" + id + "/rounds/" + round + "/diagram"; }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
}
