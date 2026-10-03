package io.agentflow.approval;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.approval.repository.SubprocessCallRepository;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.SubprocessPolicy;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import io.agentflow.organization.OrganizationService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * 定义保存、更新、发布和父子办理全部通过认证接口，只有组织身份使用合成初始化夹具。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.timers.enabled=false",
        "agentflow.sla.reminders-enabled=false", "spring.datasource.hikari.maximum-pool-size=5"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SubprocessPublicApiIntegrationTest {
    private static final String DEFINITIONS = "/api/v1/process-definitions";
    private static final String APPLICATIONS = "/api/v1/applications";
    private static final Actor ADMIN = new Actor("demo", "admin", Set.of("ADMIN", "PROCESS_ADMIN"));

    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired AuthService auth;
    @Autowired OrganizationService organization;
    @Autowired JdbcTemplate jdbc;
    @Autowired SubprocessCallRepository calls;
    @Autowired TaskService tasks;
    @Autowired RepositoryService engine;

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_PUBLIC_URL", "jdbc:h2:mem:subprocess-public;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_PUBLIC_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_PUBLIC_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_PUBLIC_PASSWORD", ""));
    }

    @BeforeEach void identities() {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM organization_directory WHERE tenant_id='demo'", Integer.class) == 0) organization.initialize(ADMIN);
        for (String subject : List.of("alice", "manager", "finance")) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM organization_person WHERE tenant_id='demo' AND subject=?", Integer.class, subject) == 0) {
                organization.createPerson(ADMIN, subject, subject, true, !subject.equals("alice"));
            }
        }
    }

    @Test void publicSaveUpdatePublishAndSubmissionKeepTheExactChildVersionAndIndependentAccess() throws Exception {
        var child = child(key());
        var graph = parentGraph(child.path("key").asText());
        var draft = createDefinition(key(), graph, parentSchema(false));
        String definitionPath = DEFINITIONS + "/" + draft.path("id").asText();
        draft = ok(send(put(definitionPath), "admin", Map.of("name", "公开父子审批", "graph", graph,
                "formSchema", parentSchema(false), "expectedRevision", draft.path("revision").asLong())), 200);
        var published = publish(draft);
        var nextChild = child(child.path("key").asText());
        assertThat(nextChild.path("version").asInt()).isEqualTo(2);
        String id = application(published);
        submit(id);
        var first = childApplication(id, 1, "first");
        assertThat(first.path("definitionVersion").asInt()).isEqualTo(1);
        assertThat(first.path("payload").properties()).extracting(Map.Entry::getKey).containsExactly("total");
        assertThat(first.path("payload").path("total").asText()).isEqualTo("15.25");
        assertThat(first.toString()).doesNotContain("private-original-value");
        assertCode(send(get(APPLICATIONS + "/" + id), "manager", null), "NOT_FOUND");
        assertCode(send(get(APPLICATIONS + "/" + first.path("id").asText()), "finance", null), "NOT_FOUND");
        assertThat(app(id, "admin").toString()).doesNotContain("private-original-value");
        approve(first.path("id").asText(), "manager");
        assertThat(app(id, "alice").path("status").asText()).isEqualTo("IN_APPROVAL");
        approve(id, "finance");
        var second = childApplication(id, 1, "last");
        assertThat(second.path("definitionVersion").asInt()).isEqualTo(1);
        assertThat(second.path("id").asText()).isNotEqualTo(first.path("id").asText());
        approve(second.path("id").asText(), "manager");
        assertThat(app(id, "alice").path("status").asText()).isEqualTo("APPROVED");
        assertThat(calls.findByParentRound("demo", UUID.fromString(id), 1)).hasSize(2);
        var publication = ok(send(get(definitionPath + "/publication"), "admin", null), 200);
        assertThat(publication.path("recorded").asBoolean()).isTrue();
    }

    @Test void unavailableDependenciesMayBeSavedButCannotPublishUntilExplicitlyCorrected() throws Exception {
        var draft = createDefinition(key(), parentGraph(key()), parentSchema(false));
        long deployed = engine.createDeploymentQuery().count();
        var response = publishResponse(draft, UUID.randomUUID().toString());
        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(body(response).path("details").path("definitionErrors").toString()).contains("SUBPROCESS_DEFINITION_UNAVAILABLE:first");
        assertThat(engine.createDeploymentQuery().count()).isEqualTo(deployed);
        assertThat(definition(draft).path("status").asText()).isEqualTo("DRAFT");
        assertThat(definition(draft).path("revision")).isEqualTo(draft.path("revision"));
        var child = child(key());
        var fixed = ok(send(put(DEFINITIONS + "/" + draft.path("id").asText()), "admin", Map.of("name", "修正明确依赖",
                "graph", parentGraph(child.path("key").asText()), "formSchema", parentSchema(false),
                "expectedRevision", draft.path("revision").asLong())), 200);
        assertThat(publish(fixed).path("version").asInt()).isEqualTo(1);
    }

    @Test void employeeCannotManageSubprocessDefinitionsOrPublishAnotherAdministratorsDraft() throws Exception {
        var child = child(key()); var graph = parentGraph(child.path("key").asText());
        var draft = createDefinition(key(), graph, parentSchema(false));
        String path = DEFINITIONS + "/" + draft.path("id").asText();
        assertCode(send(post(DEFINITIONS), "alice", definitionInput(key(), graph, parentSchema(false))), "FORBIDDEN");
        assertCode(send(put(path), "alice", Map.of("name", "无权修改", "graph", graph, "expectedRevision", draft.path("revision").asLong())), "FORBIDDEN");
        assertCode(send(post(path + "/publish?expectedRevision=" + draft.path("revision").asLong()), "alice", Map.of("changeNote", "无权发布")), "FORBIDDEN");
        assertThat(definition(draft)).isEqualTo(draft);
    }

    @Test void publicationCannotExposeSensitiveParentInputThroughAnOrdinaryChildField() throws Exception {
        var child = child(key()); var graph = parentGraph(child.path("key").asText());
        var draft = createDefinition(key(), graph, parentSchema(true));
        var preview = ok(send(post(DEFINITIONS + "/validate"), "admin", Map.of("key", draft.path("key").asText(),
                "graph", graph, "formSchema", parentSchema(true))), 200);
        assertThat(preview.path("errors").toString()).contains("SUBPROCESS_INPUT_SENSITIVITY_LOSS:first");
        long deployed = engine.createDeploymentQuery().count();
        var response = publishResponse(draft, UUID.randomUUID().toString());
        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(body(response).path("details").path("definitionErrors").toString()).contains("SUBPROCESS_INPUT_SENSITIVITY_LOSS:first");
        assertThat(engine.createDeploymentQuery().count()).isEqualTo(deployed);
        assertThat(definition(draft).path("status").asText()).isEqualTo("DRAFT");
    }

    @Test void disablingPinnedChildBeforeTheNextCallRollsBackTheOriginalTaskAndAllowsSameRequestRecovery() throws Exception {
        var child = child(key()); var parent = publish(createDefinition(key(), parentGraph(child.path("key").asText()), parentSchema(false)));
        String id = application(parent); submit(id);
        approve(childApplication(id, 1, "first").path("id").asText(), "manager");
        var paused = availability(child, false);
        var original = app(id, "alice");
        String task = task(id);
        String requestKey = UUID.randomUUID().toString();
        var input = decision(id, "APPROVE");
        assertCode(send(post("/api/v1/tasks/" + task + "/actions"), "finance", requestKey, input), "DEFINITION_DISABLED");
        assertThat(app(id, "alice")).isEqualTo(original);
        assertThat(task(id)).isEqualTo(task);
        assertThat(calls.findByParentRound("demo", UUID.fromString(id), 1)).hasSize(1);
        availability(paused, true);
        var response = send(post("/api/v1/tasks/" + task + "/actions"), "finance", requestKey, input);
        ok(response, 200);
        assertThat(send(post("/api/v1/tasks/" + task + "/actions"), "finance", requestKey, input).getContentAsString())
                .isEqualTo(response.getContentAsString());
        approve(childApplication(id, 1, "last").path("id").asText(), "manager");
        assertThat(app(id, "alice").path("status").asText()).isEqualTo("APPROVED");
    }

    @ParameterizedTest @ValueSource(strings = {"RETURN", "REJECT"})
    void negativeChildConclusionUsesPubliclyPublishedBindingAndPreservesTheOldRound(String action) throws Exception {
        var child = child(key()); var parent = publish(createDefinition(key(), parentGraph(child.path("key").asText()), parentSchema(false)));
        String id = application(parent); submit(id);
        var first = childApplication(id, 1, "first");
        String childId = first.path("id").asText();
        ok(send(post("/api/v1/tasks/" + task(childId) + "/actions"), "manager", decision(childId, action)), 200);
        String expected = action.equals("RETURN") ? "RETURNED" : "REJECTED";
        assertThat(app(id, "alice").path("status").asText()).isEqualTo(expected);
        var original = calls.findByParentRound("demo", UUID.fromString(id), 1);
        if (action.equals("RETURN")) {
            submit(id);
            assertThat(childApplication(id, 2, "first").path("id").asText()).isNotEqualTo(childId);
            assertThat(calls.findByParentRound("demo", UUID.fromString(id), 1)).isEqualTo(original);
            assertThat(app(childId, "alice").path("status").asText()).isEqualTo("RETURNED");
        }
        var history = ok(send(get(APPLICATIONS + "/" + id + "/rounds"), "alice", null), 200);
        assertThat(history.toString()).contains(expected);
    }

    private JsonNode child(String key) throws Exception {
        return publish(createDefinition(key, linear(List.of(new Node("review", "子审批人", NodeType.USER_TASK,
                Map.of("assigneeRule", assignee("manager"))))), new FormSchema(1, List.of(field("total")))));
    }
    private JsonNode createDefinition(String key, Graph graph, FormSchema schema) throws Exception {
        return ok(send(post(DEFINITIONS), "admin", definitionInput(key, graph, schema)), 200);
    }
    private Map<String, Object> definitionInput(String key, Graph graph, FormSchema schema) {
        return Map.of("key", key, "name", "公开子流程验收", "graph", graph, "formSchema", schema);
    }
    private JsonNode publish(JsonNode draft) throws Exception {
        String key = UUID.randomUUID().toString(); var response = publishResponse(draft, key);
        var value = ok(response, 200);
        assertThat(publishResponse(draft, key).getContentAsString()).isEqualTo(response.getContentAsString());
        return value;
    }
    private MockHttpServletResponse publishResponse(JsonNode draft, String key) throws Exception {
        return send(post(DEFINITIONS + "/" + draft.path("id").asText() + "/publish?expectedRevision=" + draft.path("revision").asLong()),
                "admin", key, Map.of("changeNote", "核对子版本和显式字段映射后发布"));
    }
    private JsonNode definition(JsonNode draft) throws Exception { return ok(send(get(DEFINITIONS + "/" + draft.path("id").asText()), "admin", null), 200); }
    private JsonNode availability(JsonNode definition, boolean enabled) throws Exception {
        return ok(send(post(DEFINITIONS + "/" + definition.path("id").asText() + "/availability"), "admin",
                Map.of("expectedRevision", definition.path("revision").asLong(), "startEnabled", enabled, "reason", "明确调整原版本可用性")), 200);
    }
    private String application(JsonNode definition) throws Exception {
        return ok(send(post(APPLICATIONS), "alice", Map.of("businessNo", "PUBLIC-" + UUID.randomUUID(), "processKey", definition.path("key").asText(),
                "definitionVersion", definition.path("version").asLong(), "title", "公开固定版本子审批", "payload", Map.of("amount", "15.25", "secret", "private-original-value"))), 201).path("id").asText();
    }
    private void submit(String id) throws Exception {
        var input = Map.of("expectedVersion", app(id, "alice").path("version").asLong());
        String key = UUID.randomUUID().toString(); var response = send(post(APPLICATIONS + "/" + id + "/submit"), "alice", key, input);
        ok(response, 200);
        assertThat(send(post(APPLICATIONS + "/" + id + "/submit"), "alice", key, input).getContentAsString()).isEqualTo(response.getContentAsString());
    }
    private JsonNode childApplication(String id, int round, String node) throws Exception {
        var matches = calls.findByParentRound("demo", UUID.fromString(id), round).stream().filter(call -> call.nodeId().equals(node)).toList();
        assertThat(matches).hasSize(1);
        return app(matches.get(0).childApplicationId().toString(), "alice");
    }
    private JsonNode app(String id, String user) throws Exception { return ok(send(get(APPLICATIONS + "/" + id), user, null), 200); }
    private String task(String id) { return tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult().getId(); }
    private Map<String, Object> decision(String id, String action) throws Exception {
        return Map.of("action", action, "expectedVersion", app(id, "alice").path("version").asLong(), "comment", "按原轮次核对后办理");
    }
    private void approve(String id, String user) throws Exception {
        String task = task(id); String key = UUID.randomUUID().toString(); var input = decision(id, "APPROVE");
        var response = send(post("/api/v1/tasks/" + task + "/actions"), user, key, input); ok(response, 200);
        assertThat(send(post("/api/v1/tasks/" + task + "/actions"), user, key, input).getContentAsString()).isEqualTo(response.getContentAsString());
    }
    private MockHttpServletResponse send(MockHttpServletRequestBuilder request, String user, Object input) throws Exception {
        return send(request, user, UUID.randomUUID().toString(), input);
    }
    private MockHttpServletResponse send(MockHttpServletRequestBuilder request, String user, String key, Object input) throws Exception {
        request.header("Authorization", "Bearer " + auth.login("demo", user, "demo").token()).header("Idempotency-Key", key);
        if (input != null) request.contentType("application/json").content(json.write(input));
        return mvc.perform(request).andReturn().getResponse();
    }
    private JsonNode body(MockHttpServletResponse response) throws Exception { return json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class); }
    private JsonNode ok(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(status); return body(response);
    }
    private void assertCode(MockHttpServletResponse response, String code) throws Exception {
        assertThat(response.getStatus()).isBetween(400, 499); assertThat(body(response).path("code").asText()).isEqualTo(code);
    }
    private String assignee(String subject) {
        return "role:ORG_PERSON_" + jdbc.queryForObject("SELECT id FROM organization_person WHERE tenant_id='demo' AND subject=?", String.class, subject);
    }
    private Graph parentGraph(String childKey) {
        var properties = new SubprocessPolicy(childKey, 1, Map.of("total", "amount")).properties();
        return linear(List.of(new Node("first", "前置子审批", NodeType.SUB_PROCESS, properties),
                new Node("finance", "父级复核", NodeType.USER_TASK, Map.of("assigneeRule", assignee("finance"))),
                new Node("last", "后置子审批", NodeType.SUB_PROCESS, properties)));
    }
    private static FormSchema parentSchema(boolean sensitiveAmount) {
        return new FormSchema(2, List.of(new FormSchema.Field("amount", "金额", FormSchema.FieldType.NUMBER, true, null, null, null, null, null,
                null, null, sensitiveAmount, Map.of("first", FieldVisibility.READ_ONLY, "finance", FieldVisibility.READ_ONLY, "last", FieldVisibility.READ_ONLY)),
                new FormSchema.Field("secret", "敏感说明", FormSchema.FieldType.TEXT, false, null, null, null, null, null,
                        null, null, true, Map.of("first", FieldVisibility.HIDDEN, "finance", FieldVisibility.MASKED, "last", FieldVisibility.HIDDEN))));
    }
    private static FormSchema.Field field(String key) { return new FormSchema.Field(key, key, FormSchema.FieldType.NUMBER, true, null, null, null, null, null); }
    private static String key() { return "public-call-" + UUID.randomUUID(); }
    private static Graph linear(List<Node> middle) {
        var nodes = new ArrayList<Node>(); nodes.add(new Node("start", "开始", NodeType.START, Map.of())); nodes.addAll(middle); nodes.add(new Node("end", "结束", NodeType.END, Map.of()));
        var edges = new ArrayList<Edge>();
        for (int i = 1; i < nodes.size(); i++) edges.add(new Edge("edge" + i, nodes.get(i - 1).id(), nodes.get(i).id(), ""));
        return new Graph(nodes, edges);
    }
}
