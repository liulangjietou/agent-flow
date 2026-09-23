package io.agentflow.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.auth.AuthService;
import org.flowable.engine.TaskService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.agentflow.support.MutationRequests.post;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同 key、同版本的内置定义和租户定义必须保持原申请的来源绑定。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:bundled-form-binding;DB_CLOSE_DELAY=-1", "agentflow.auth.demo-enabled=true"})
@AutoConfigureMockMvc
class BundledDefinitionBindingIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AuthService auth;
    @Autowired TaskService tasks;
    @Autowired RepositoryService definitions;
    @Autowired RuntimeService runtime;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetOnlyThisTestDatabasesTenantDefinitionFixtures() {
        definitions.createDeploymentQuery().deploymentTenantId("demo").list()
                .forEach(deployment -> definitions.deleteDeployment(deployment.getId(), true));
        jdbc.update("DELETE FROM definition_publication WHERE tenant_id='demo'");
        jdbc.update("DELETE FROM approval_definition WHERE tenant_id='demo'");
    }

    @Test
    void laterTenantVersionCannotReplaceTheBundledDefinitionBoundToAnEarlierDraft() throws Exception {
        JsonNode original = send("/api/v1/applications", "alice", Map.of("businessNo", "BUNDLED-" + UUID.randomUUID(),
                "processKey", "expense-reimbursement", "definitionVersion", 1, "title", "原内置申请", "payload", Map.of("amount", 6000)), 201);
        assertThat(original.path("formSchema").isNull()).isTrue();
        JsonNode definition = publishTenantDefinition();

        send("/api/v1/applications/" + original.path("id").asText() + "/submit", "alice", Map.of("expectedVersion", 1), 200);

        var task = tasks.createTaskQuery().processVariableValueEquals("applicationId", original.path("id").asText()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("finance-approval");
        JsonNode incomplete = createDraft(Map.of());
        JsonNode required = send("/api/v1/applications/" + incomplete.path("id").asText() + "/submit", "alice", Map.of("expectedVersion", 1), 422);
        assertThat(required.at("/details/fieldErrors/reason").asText()).isEqualTo("REQUIRED");
        JsonNode newDraft = send("/api/v1/applications", "alice", Map.of("businessNo", "TENANT-" + UUID.randomUUID(),
                "processKey", "expense-reimbursement", "definitionVersion", 1, "title", "新租户申请", "payload", Map.of("reason", "按新表单申请")), 201);
        assertThat(newDraft.path("formSchema")).isEqualTo(definition.path("formSchema"));
        send("/api/v1/applications/" + newDraft.path("id").asText() + "/submit", "alice", Map.of("expectedVersion", 1), 200);
        assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", newDraft.path("id").asText()).singleResult().getTaskDefinitionKey()).isEqualTo("tenant-approve");
    }

    @Test
    void deletedBoundTenantDefinitionCannotFallbackToBundledVersion() throws Exception {
        publishTenantDefinition();
        JsonNode draft = createDraft(Map.of("reason", "租户表单"));
        String id = draft.path("id").asText();
        String boundId = jdbc.queryForObject("SELECT runtime_definition_id FROM approval_application WHERE id=?", String.class, id);
        var bound = definitions.createProcessDefinitionQuery().processDefinitionId(boundId).singleResult();
        definitions.deleteDeployment(bound.getDeploymentId(), true);

        JsonNode response = send("/api/v1/applications/" + id + "/submit", "alice", Map.of("expectedVersion", 1), 422);

        assertThat(response.path("code").asText()).isEqualTo("PROCESS_DEFINITION_NOT_FOUND");
        assertUnchangedDraft(id);
    }

    @Test
    void legacyRoundUsesActualHistoricalDefinitionWhenTenantVersionAppearsLater() throws Exception {
        JsonNode draft = createDraft(Map.of("oldField", "旧内容"));
        String id = draft.path("id").asText();
        jdbc.update("UPDATE approval_application SET runtime_definition_id=NULL WHERE id=?", id);
        send("/api/v1/applications/" + id + "/submit", "alice", Map.of("expectedVersion", 1), 200);
        var originalTask = tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult();
        String originalDefinition = originalTask.getProcessDefinitionId();
        send("/api/v1/applications/" + id + "/withdraw", "alice", Map.of("expectedVersion", 2), 200);
        publishTenantDefinition();

        send("/api/v1/applications/" + id + "/submit", "alice", Map.of("expectedVersion", 3), 200);

        var newTask = tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult();
        assertThat(newTask.getProcessDefinitionId()).isEqualTo(originalDefinition);
        assertThat(newTask.getTaskDefinitionKey()).isEqualTo("finance-approval");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_submission_round WHERE application_id=?", Integer.class, id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT runtime_definition_id FROM approval_application WHERE id=?", String.class, id)).isNull();
    }

    @Test
    void legacyDraftWithTwoPossibleSourcesFailsWithoutInventingABinding() throws Exception {
        JsonNode draft = createDraft(Map.of("oldField", "旧内容"));
        String id = draft.path("id").asText();
        jdbc.update("UPDATE approval_application SET runtime_definition_id=NULL WHERE id=?", id);
        publishTenantDefinition();

        JsonNode response = send("/api/v1/applications/" + id + "/submit", "alice", Map.of("expectedVersion", 1), 409);

        assertThat(response.path("code").asText()).isEqualTo("DEFINITION_BINDING_AMBIGUOUS");
        assertUnchangedDraft(id);
    }

    private void assertUnchangedDraft(String id) {
        assertThat(jdbc.queryForObject("SELECT status FROM approval_application WHERE id=?", String.class, id)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT version FROM approval_application WHERE id=?", Long.class, id)).isEqualTo(1);
        assertThat(runtime.createProcessInstanceQuery().variableValueEquals("applicationId", id).count()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_submission_round WHERE application_id=?", Integer.class, id)).isZero();
    }

    private JsonNode createDraft(Map<String, Object> payload) throws Exception {
        return send("/api/v1/applications", "alice", Map.of("businessNo", "SOURCE-" + UUID.randomUUID(), "processKey", "expense-reimbursement",
                "definitionVersion", 1, "title", "来源绑定", "payload", payload), 201);
    }

    private JsonNode publishTenantDefinition() throws Exception {
        JsonNode draft = send("/api/v1/process-definitions", "admin", Map.of("key", "expense-reimbursement", "name", "同版本新租户表单",
                "graph", graph(), "formSchema", Map.of("schemaVersion", 1, "fields", List.of(Map.of("key", "reason", "label", "新必填字段", "type", "TEXT", "required", true)))), 200);
        return send("/api/v1/process-definitions/" + draft.path("id").asText() + "/publish?expectedRevision=0", "admin", Map.of("changeNote", "测试表单版本发布"), 200);
    }

    private Map<String, Object> graph() {
        return Map.of("nodes", List.of(Map.of("id", "start", "name", "开始", "type", "START"),
                        Map.of("id", "tenant-approve", "name", "新租户审批", "type", "USER_TASK", "properties", Map.of("assigneeRule", "user:manager")),
                        Map.of("id", "end", "name", "结束", "type", "END")),
                "edges", List.of(Map.of("id", "a", "source", "start", "target", "tenant-approve"),
                        Map.of("id", "b", "source", "tenant-approve", "target", "end")));
    }

    private JsonNode send(String path, String user, Object body, int expected) throws Exception {
        var response = mvc.perform(post(path).header("Authorization", "Bearer " + auth.login("demo", user, "demo").token())
                .contentType(MediaType.APPLICATION_JSON).content(body == null ? "" : mapper.writeValueAsString(body))).andReturn().getResponse();
        assertThat(response.getStatus()).withFailMessage("Unexpected HTTP %s: %s", response.getStatus(), response.getContentAsString()).isEqualTo(expected);
        return mapper.readTree(response.getContentAsString());
    }
}
