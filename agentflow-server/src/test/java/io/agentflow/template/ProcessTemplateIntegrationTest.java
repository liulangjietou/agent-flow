package io.agentflow.template;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.api.idempotency.JdbcIdempotencyRepository;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.definition.DefinitionModels.Graph;
import io.agentflow.definition.DefinitionModels.Node;
import io.agentflow.form.FormSchema;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通过真实 HTTP 入口验证模板目录、复制及租户隔离。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:process-templates;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
        "agentflow.auth.demo-enabled=true"
})
@AutoConfigureMockMvc
class ProcessTemplateIntegrationTest {
    @Autowired MockMvc mvc;
    @MockitoSpyBean AuthService auth;
    @MockitoSpyBean JdbcTemplateCopyRepository copies;
    @MockitoSpyBean JdbcIdempotencyRepository records;
    @Autowired JsonUtil json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ClasspathProcessTemplateCatalog catalog;
    @Autowired DefinitionApplicationService definitions;
    @Autowired RepositoryService engineDefinitions;
    @Autowired TaskService tasks;

    @Test
    void administratorCanReadValidatedBuiltInTemplates() throws Exception {
        mvc.perform(get("/api/v1/process-templates")
                        .header("Authorization", "Bearer " + auth.login("demo", "admin", "demo").token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].templateVersion").value(1))
                .andExpect(jsonPath("$[0].copies").isArray());
    }

    @Test
    void copyIsAnIndependentDraftAndReplayKeepsTheOriginalResponse() throws Exception {
        String processKey = uniqueKey();
        String requestKey = uniqueKey();
        String body = body(processKey);
        long deploymentsBefore = engineDefinitions.createDeploymentQuery().count();
        MockHttpServletResponse first = copy("leave-request", token("admin"), requestKey, body);
        assertThat(first.getStatus()).isEqualTo(200);
        JsonNode draft = tree(first);
        assertThat(draft.path("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.path("version").asLong()).isZero();
        assertThat(draft.path("revision").asLong()).isZero();
        assertThat(engineDefinitions.createDeploymentQuery().count()).isEqualTo(deploymentsBefore);
        String id = draft.path("id").asText();
        var source = jdbc.queryForMap("SELECT * FROM template_copy WHERE definition_id=?", id);
        assertThat(source).containsEntry("TENANT_ID", "demo").containsEntry("TEMPLATE_KEY", "leave-request")
                .containsEntry("TEMPLATE_VERSION", 1L).containsEntry("COPIED_BY", "admin");
        assertThat(source.get("COPIED_AT")).isNotNull();

        var original = catalog.get("leave-request");
        Graph editedGraph = new Graph(original.graph().nodes().stream().map(node -> new Node(node.id(),
                node.id().equals("manager") ? "副本自己的审批节点" : node.name(), node.type(), node.properties())).toList(), original.graph().edges());
        var editedFields = new ArrayList<>(original.formSchema().fields());
        editedFields.add(new FormSchema.Field("internalMemo", "副本备注", FormSchema.FieldType.TEXT, false, null, 200, null, null, List.of()));
        definitions.update("demo", UUID.fromString(id), "已经修改的副本", editedGraph, new FormSchema(1, editedFields), 0);
        assertThat(definitions.get("demo", UUID.fromString(id)).graph()).isNotEqualTo(original.graph());
        assertThat(definitions.get("demo", UUID.fromString(id)).formSchema()).isNotEqualTo(original.formSchema());
        assertThat(jdbc.queryForMap("SELECT * FROM template_copy WHERE definition_id=?", id)).isEqualTo(source);
        MockHttpServletResponse replay = copy("leave-request", token("admin"), requestKey, body);
        assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
        JsonNode listed = template("leave-request", token("admin"));
        JsonNode listedCopy = findCopy(listed, id);
        assertThat(listedCopy.path("name").asText()).isEqualTo("已经修改的副本");
        assertThat(listedCopy.path("revision").asLong()).isEqualTo(1);
        assertThat(listedCopy.path("templateVersion").asLong()).isEqualTo(1);
        assertThat(listedCopy.has("tenantId")).isFalse();
        assertThat(catalog.get("leave-request").name()).isEqualTo(original.name());
        MockHttpServletResponse second = copy("leave-request", token("admin"), uniqueKey(), body(uniqueKey()));
        assertThat(tree(second).path("graph")).isEqualTo(draft.path("graph"));
        assertThat(tree(second).path("formSchema")).isEqualTo(draft.path("formSchema"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_definition WHERE tenant_id='demo' AND process_key=?", Integer.class, processKey)).isEqualTo(1);
        MockHttpServletResponse conflict = copy("leave-request", token("admin"), requestKey, body(uniqueKey()));
        assertThat(conflict.getStatus()).isEqualTo(409);
        assertThat(tree(conflict).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"employee", "manager", "finance"})
    void nonAdministratorsCannotReadScenariosOrCopy(String user) throws Exception {
        String token = token(user);
        mvc.perform(get("/api/v1/process-templates").header("Authorization", token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/process-templates/leave-request/scenarios").header("Authorization", token)).andExpect(status().isForbidden());
        assertThat(copy("leave-request", token, uniqueKey(), body(uniqueKey())).getStatus()).isEqualTo(403);
    }

    @Test
    void processAdminCanUseEveryEndpointAndCopiesAreTenantPrivate() throws Exception {
        doReturn(new Actor("other-tenant", "designer", Set.of("PROCESS_ADMIN"))).when(auth).authenticate("other-admin");
        String demoToken = token("admin");
        String foreignToken = "Bearer other-admin";
        String requestKey = uniqueKey();
        String body = body(uniqueKey());
        String demoId = tree(copy("seal-application", demoToken, requestKey, body)).path("id").asText();
        MockHttpServletResponse foreign = copy("seal-application", foreignToken, requestKey, body);
        assertThat(foreign.getStatus()).isEqualTo(200);
        String foreignId = tree(foreign).path("id").asText();
        assertThat(tree(foreign).path("tenantId").asText()).isEqualTo("other-tenant");
        assertThat(template("seal-application", demoToken).path("copies").toString()).contains(demoId).doesNotContain(foreignId);
        assertThat(template("seal-application", foreignToken).path("copies").toString()).contains(foreignId).doesNotContain(demoId);
        mvc.perform(get("/api/v1/process-templates/seal-application/scenarios").header("Authorization", foreignToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].payload").isMap());
        mvc.perform(get("/api/v1/process-definitions/" + demoId).header("Authorization", foreignToken)).andExpect(status().isNotFound());
    }

    @Test
    void staleVersionUnknownTemplateAndMissingIdempotencyKeyHaveExplicitErrors() throws Exception {
        String admin = token("admin");
        MockHttpServletResponse stale = copy("leave-request", admin, uniqueKey(), json.write(Map.of("key", uniqueKey(), "name", "复制", "templateVersion", 2)));
        assertThat(stale.getStatus()).isEqualTo(409);
        assertThat(tree(stale).path("code").asText()).isEqualTo("TEMPLATE_VERSION_CONFLICT");
        assertThat(copy("missing", admin, uniqueKey(), body(uniqueKey())).getStatus()).isEqualTo(404);
        mvc.perform(get("/api/v1/process-templates/missing/scenarios").header("Authorization", admin)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/process-templates/leave-request/copy").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body(uniqueKey())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"key\":\"valid\",\"name\":\"复制\",\"templateVersion\":\"1\"}",
            "{\"key\":\"valid\",\"name\":\"复制\",\"templateVersion\":1.0}",
            "{\"key\":\"valid\",\"name\":\"复制\",\"templateVersion\":0}",
            "{\"key\":\"valid\",\"name\":\"复制\",\"templateVersion\":-1}",
            "{\"key\":\"valid\",\"name\":\"复制\",\"templateVersion\":9223372036854775808}",
            "{\"key\":42,\"name\":\"复制\",\"templateVersion\":1}",
            "{\"key\":\"合法\",\"name\":\"复制\",\"templateVersion\":1}",
            "{\"key\":\"1start\",\"name\":\"复制\",\"templateVersion\":1}",
            "{\"key\":\"invalid.key\",\"name\":\"复制\",\"templateVersion\":1}",
            "{\"key\":\"valid\",\"name\":\"  \",\"templateVersion\":1}",
            "{\"key\":\"valid\",\"name\":42,\"templateVersion\":1}",
            "{\"key\":\"valid\",\"name\":\"复制\",\"templateVersion\":1,\"tenantId\":\"other\"}",
            "{\"key\":\"valid\",\"name\":\"复制\"}", "null", "[]"
    })
    void copyRejectsInvalidRequestWithoutBusinessWrites(String body) throws Exception {
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM template_copy", Integer.class);
        MockHttpServletResponse response = copy("leave-request", token("admin"), uniqueKey(), body);
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM template_copy", Integer.class)).isEqualTo(before);
    }

    @Test
    void copyAcceptsBoundaryLengthsAndRejectsLongerNamesOrKeys() throws Exception {
        String admin = token("admin");
        assertThat(copy("leave-request", admin, uniqueKey(), json.write(Map.of("key", "A".repeat(64), "name", "名".repeat(128), "templateVersion", 1))).getStatus()).isEqualTo(200);
        assertThat(copy("leave-request", admin, uniqueKey(), json.write(Map.of("key", "A".repeat(65), "name", "复制", "templateVersion", 1))).getStatus()).isEqualTo(400);
        assertThat(copy("leave-request", admin, uniqueKey(), json.write(Map.of("key", uniqueKey(), "name", "名".repeat(129), "templateVersion", 1))).getStatus()).isEqualTo(400);
    }

    @Test
    void sourceFailureRollsBackDefinitionAndIdempotencyClaim() {
        String key = uniqueKey();
        String requestKey = uniqueKey();
        doThrow(new IllegalStateException("Injected provenance failure")).when(copies).save(any());
        assertThatThrownBy(() -> copy("leave-request", token("admin"), requestKey, body(key)))
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_definition WHERE process_key=?", Integer.class, key)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_idempotency WHERE idempotency_key=?", Integer.class, requestKey)).isZero();
    }

    @Test
    void concurrentCopiesWithOneKeyCommitExactlyOneDefinitionAndSource() throws Exception {
        String requestKey = uniqueKey();
        String body = body(uniqueKey());
        String admin = token("admin");
        CyclicBarrier beforeClaim = new CyclicBarrier(2);
        doAnswer(call -> {
            beforeClaim.await(5, TimeUnit.SECONDS);
            return call.callRealMethod();
        }).when(records).claim(eq("demo"), eq(requestKey), anyString(), anyString(), anyString(), any(), any());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> copy("leave-request", admin, requestKey, body));
            var second = executor.submit(() -> copy("leave-request", admin, requestKey, body));
            var a = first.get(15, TimeUnit.SECONDS);
            var b = second.get(15, TimeUnit.SECONDS);
            assertThat(List.of(a.getStatus(), b.getStatus())).containsExactly(200, 200);
            assertThat(a.getContentAsString()).isEqualTo(b.getContentAsString());
            assertThat(List.of(a.getHeader("Idempotency-Replayed"), b.getHeader("Idempotency-Replayed"))).containsExactlyInAnyOrder("false", "true");
            String id = tree(a).path("id").asText();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM template_copy WHERE definition_id=?", Integer.class, id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_definition WHERE id=?", Integer.class, id)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"leave-request", "seal-application", "contract-review"})
    void copiedTemplatesSimulatePublishAndRunEveryValidScenario(String templateKey) throws Exception {
        String admin = token("admin");
        String manager = token("manager");
        String applicant = token("alice");
        ProcessTemplate template = catalog.get(templateKey);
        String processKey = uniqueKey();
        JsonNode definition = tree(copy(templateKey, admin, uniqueKey(), body(processKey)));
        String definitionId = definition.path("id").asText();
        String definitionUrl = "/api/v1/process-definitions/" + definitionId;
        for (var scenario : template.scenarios()) {
            MockHttpServletResponse simulation = send(definitionUrl + "/simulate", admin, json.write(Map.of("values", scenario.payload())));
            if (!scenario.expectedFieldErrors().isEmpty()) {
                assertThat(simulation.getStatus()).isEqualTo(422);
                assertThat(tree(simulation).path("details").path("fieldErrors")).isEqualTo(json.read(json.write(scenario.expectedFieldErrors()), JsonNode.class));
            } else {
                assertThat(simulation.getStatus()).isEqualTo(200);
                assertThat(tree(simulation).path("path")).isEqualTo(json.read(json.write(scenario.expectedPath()), JsonNode.class));
            }
        }
        assertThat(send(definitionUrl + "/publish?expectedRevision=0", admin, "").getStatus()).isEqualTo(200);
        assertThat(findCopy(template(templateKey, admin), definitionId).path("status").asText()).isEqualTo("PUBLISHED");
        for (var scenario : template.scenarios().stream().filter(value -> value.expectedFieldErrors().isEmpty()).toList()) {
            var application = tree(send("/api/v1/applications", applicant, json.write(Map.of("businessNo", uniqueKey(), "processKey", processKey,
                    "definitionVersion", 1, "title", scenario.name(), "payload", scenario.payload()))));
            String applicationId = application.path("id").asText();
            MockHttpServletResponse submitted = send("/api/v1/applications/" + applicationId + "/submit", applicant,
                    json.write(Map.of("expectedVersion", application.path("version").asLong())));
            assertThat(submitted.getStatus()).isEqualTo(200);
            long version = tree(submitted).path("version").asLong();
            List<String> expectedTasks = scenario.expectedPath().stream().filter(node -> List.of("manager", "review").contains(node)).toList();
            for (String node : expectedTasks) {
                var task = tasks.createTaskQuery().processVariableValueEquals("applicationId", applicationId).singleResult();
                assertThat(task.getTaskDefinitionKey()).isEqualTo(node);
                var approved = send("/api/v1/tasks/" + task.getId() + "/actions", node.equals("manager") ? manager : admin,
                        json.write(Map.of("action", "APPROVE", "expectedVersion", version)));
                assertThat(approved.getStatus()).isEqualTo(200);
                version = tree(approved).path("version").asLong();
            }
            mvc.perform(get("/api/v1/applications/" + applicationId).header("Authorization", applicant))
                    .andExpect(status().isOk()).andExpect(jsonPath("status").value("APPROVED"));
            assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", applicationId).count()).isZero();
        }
    }

    @Test
    void revokedAdministrativeRoleCannotReplayACopyResponse() throws Exception {
        String requestKey = uniqueKey();
        String body = body(uniqueKey());
        assertThat(copy("leave-request", token("admin"), requestKey, body).getStatus()).isEqualTo(200);
        doReturn(new Actor("demo", "admin", Set.of("EMPLOYEE"))).when(auth).authenticate("former-admin");
        assertThat(copy("leave-request", "Bearer former-admin", requestKey, body).getStatus()).isEqualTo(403);
    }

    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private String uniqueKey() { return "test-" + UUID.randomUUID(); }
    private String body(String key) { return json.write(Map.of("key", key, "name", "模板副本", "templateVersion", 1)); }
    private JsonNode tree(MockHttpServletResponse response) throws Exception { return json.read(response.getContentAsString(), JsonNode.class); }

    private MockHttpServletResponse copy(String template, String token, String key, String body) throws Exception {
        return mvc.perform(post("/api/v1/process-templates/" + template + "/copy").header("Authorization", token)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
    }

    private MockHttpServletResponse send(String url, String token, String body) throws Exception {
        return mvc.perform(post(url).header("Authorization", token).header("Idempotency-Key", uniqueKey())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
    }

    private JsonNode template(String key, String token) throws Exception {
        JsonNode list = tree(mvc.perform(get("/api/v1/process-templates").header("Authorization", token)).andReturn().getResponse());
        for (JsonNode item : list) if (item.path("key").asText().equals(key)) return item;
        throw new AssertionError("Template not present");
    }

    private JsonNode findCopy(JsonNode template, String definitionId) {
        for (JsonNode copy : template.path("copies")) if (copy.path("definitionId").asText().equals(definitionId)) return copy;
        throw new AssertionError("Template copy not present");
    }
}
