package io.agentflow.definition;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import io.agentflow.organization.LocalOrganizationDirectory;
import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 只读提示绑定所选原版本与申请原来源；不能把未知依赖当作不需要任职。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:initiator-requirements-query;DB_CLOSE_DELAY=-1", "agentflow.auth.demo-enabled=true"})
@AutoConfigureMockMvc
class InitiatorRequirementsQueryIntegrationTest {
    /** 可用独立 PostgreSQL 库重复验证同一组查询边界，默认仍使用本类 H2 库。 */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        String url = System.getenv("AGENTFLOW_REQUIREMENTS_TEST_URL");
        if (url == null || url.isBlank()) return;
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.datasource.username", () -> System.getenv("AGENTFLOW_REQUIREMENTS_TEST_USER"));
        properties.add("spring.datasource.password", () -> System.getenv("AGENTFLOW_REQUIREMENTS_TEST_PASSWORD"));
    }
    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired DefinitionDraftRepository definitions;
    @Autowired DefinitionDeploymentPort deployment;
    @Autowired TransactionTemplate transactions;
    @Autowired RepositoryService engine;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean AuthService auth;

    @Test
    void readsOnlyTheSelectedVersionAndDeclaredDescendantsWithoutReturningTheirDetails() throws Exception {
        String key = "requirement-child-" + UUID.randomUUID();
        publish(key, 1, graph(task(LocalOrganizationDirectory.SUPERVISOR_RULE + "1")), false);
        publish(key, 2, graph(task("user:manager")), false);
        var required = publish("required-" + UUID.randomUUID(), 1, graph(call(key, 1)), false);
        var optional = publish("optional-" + UUID.randomUUID(), 1, graph(call(key, 2)), false);
        var before = counts();
        assertView(readDefinition(required, "alice"), required.key(), 1, true);
        assertView(readDefinition(optional, "alice"), optional.key(), 1, false);
        assertThat(counts()).isEqualTo(before);
    }

    @Test
    void enforcesAuthenticationTenantAndPublishedVisibility() throws Exception {
        var published = publish("visible-" + UUID.randomUUID(), 1, graph(task("user:manager")), false);
        var draft = definitions.save(DefinitionDraft.create(UUID.randomUUID(), "demo", "draft-" + UUID.randomUUID(), "草稿", published.graph()));
        String path = definitionPath(published);
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        for (String user : List.of("alice", "admin")) {
            mvc.perform(get(definitionPath(draft)).header("Authorization", token(user))).andExpect(status().isNotFound());
        }
        doReturn(new Actor("other", "alice", Set.of())).when(auth).authenticate("foreign-requirement-token");
        mvc.perform(get(path).header("Authorization", "Bearer foreign-requirement-token")).andExpect(status().isNotFound());
    }

    @Test
    void missingDependencyIsAnExplicitFailureAndDisabledVersionsRetainTheirRequirement() throws Exception {
        var missing = publish("missing-" + UUID.randomUUID(), 1, graph(call("unavailable-child", 3)), false);
        mvc.perform(get(definitionPath(missing)).header("Authorization", token("alice")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SUBPROCESS_DEFINITION_UNAVAILABLE"));
        var disabled = publish("disabled-" + UUID.randomUUID(), 1, graph(task(LocalOrganizationDirectory.SUPERVISOR_RULE + "1")), false);
        jdbc.update("UPDATE approval_definition SET start_enabled=false WHERE id=?", disabled.id().toString());
        assertView(readDefinition(disabled, "alice"), disabled.key(), 1, true);
    }

    @Test
    void applicationQueryUsesBoundVersionAndExistingResourceAuthorizationWithoutStartingARound() throws Exception {
        String key = "bound-" + UUID.randomUUID();
        var original = publish(key, 1, graph(task(LocalOrganizationDirectory.SUPERVISOR_RULE + "1")), true);
        JsonNode application = create(key);
        publish(key, 2, graph(task("user:manager")), true);
        var before = counts();
        assertView(readApplication(application, "alice"), original.key(), 1, true);
        String path = applicationPath(application);
        mvc.perform(get(path).header("Authorization", token("bob"))).andExpect(status().isNotFound());
        doReturn(new Actor("other", "alice", Set.of())).when(auth).authenticate("foreign-requirement-token");
        mvc.perform(get(path).header("Authorization", "Bearer foreign-requirement-token")).andExpect(status().isNotFound());
        assertThat(counts()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT status FROM approval_application WHERE id=?", String.class, application.path("id").asText())).isEqualTo("DRAFT");
    }

    @Test
    void originalBundledAndHistoricalBindingsDoNotBorrowLaterTenantRequirements() throws Exception {
        JsonNode original = create("expense-reimbursement");
        JsonNode legacy = create("expense-reimbursement");
        String legacyId = legacy.path("id").asText();
        jdbc.update("UPDATE approval_application SET runtime_definition_id=NULL WHERE id=?", legacyId);
        postApplication(legacyId + "/submit", Map.of("expectedVersion", 1), 200);
        postApplication(legacyId + "/withdraw", Map.of("expectedVersion", 2), 200);
        JsonNode ambiguous = create("expense-reimbursement");
        jdbc.update("UPDATE approval_application SET runtime_definition_id=NULL WHERE id=?", ambiguous.path("id").asText());
        publish("expense-reimbursement", 1, graph(task(LocalOrganizationDirectory.SUPERVISOR_RULE + "1")), true);
        assertView(readApplication(original, "alice"), "expense-reimbursement", 1, false);
        assertView(readApplication(legacy, "alice"), "expense-reimbursement", 1, false);
        mvc.perform(get(applicationPath(ambiguous)).header("Authorization", token("alice")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEFINITION_BINDING_AMBIGUOUS"));
        assertView(readApplication(create("expense-reimbursement"), "alice"), "expense-reimbursement", 1, true);
    }

    @Test
    void deletedActualEngineDefinitionDoesNotProduceAnOptionalHint() throws Exception {
        var definition = publish("deleted-" + UUID.randomUUID(), 1, graph(task("user:manager")), true);
        JsonNode application = create(definition.key());
        var runtimeDefinition = engine.createProcessDefinitionQuery().processDefinitionKey(definition.key()).processDefinitionTenantId("demo").singleResult();
        engine.deleteDeployment(runtimeDefinition.getDeploymentId());
        mvc.perform(get(applicationPath(application)).header("Authorization", token("alice")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PROCESS_DEFINITION_NOT_FOUND"));
    }

    private DefinitionDraft publish(String key, long version, Graph graph, boolean deploy) {
        return transactions.execute(status -> {
            var draft = DefinitionDraft.create(UUID.randomUUID(), "demo", key, "任职要求查询", graph);
            definitions.save(draft); draft.publish(0, version); definitions.save(draft);
            if (deploy) deployment.deploy(draft);
            return draft;
        });
    }
    private JsonNode create(String key) throws Exception {
        return postApplication("", Map.of("businessNo", "REQUIREMENT-" + UUID.randomUUID(), "processKey", key, "definitionVersion", 1,
                "title", "任职提示验收", "payload", Map.of("amount", 6000)), 201);
    }
    private JsonNode postApplication(String suffix, Object body, int status) throws Exception {
        var response = mvc.perform(post("/api/v1/applications" + (suffix.isEmpty() ? "" : "/" + suffix))
                .header("Authorization", token("alice")).header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json").content(json.write(body))).andExpect(status().is(status)).andReturn().getResponse();
        return json.read(response.getContentAsString(), JsonNode.class);
    }
    private JsonNode readDefinition(DefinitionDraft definition, String user) throws Exception { return read(definitionPath(definition), user); }
    private JsonNode readApplication(JsonNode application, String user) throws Exception { return read(applicationPath(application), user); }
    private JsonNode read(String path, String user) throws Exception {
        return json.read(mvc.perform(get(path).header("Authorization", token(user))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
    private String definitionPath(DefinitionDraft definition) { return "/api/v1/process-definitions/" + definition.id() + "/initiator-requirements"; }
    private String applicationPath(JsonNode application) { return "/api/v1/applications/" + application.path("id").asText() + "/initiator-requirements"; }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private void assertView(JsonNode value, String key, long version, boolean required) {
        assertThat(value).isEqualTo(json.read(json.write(Map.of("processKey", key, "definitionVersion", version, "appointmentRequired", required)), JsonNode.class));
    }
    private List<Long> counts() {
        return List.of("approval_application", "approval_submission_round", "approval_subprocess_call", "audit_event", "act_ru_execution", "act_ru_task")
                .stream().map(table -> jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)).toList();
    }
    private Node task(String rule) { return new Node("review", "复核", NodeType.USER_TASK, Map.of("assigneeRule", rule)); }
    private Node call(String key, long version) { return new Node("call", "子审批", NodeType.SUB_PROCESS, new SubprocessPolicy(key, version, Map.of()).properties()); }
    private Graph graph(Node middle) {
        return new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()), middle, new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("in", "start", middle.id(), "", false), new Edge("out", middle.id(), "end", "", false)));
    }
}
