package io.agentflow.servicetask;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.ServiceTaskPolicy;
import io.agentflow.definition.SubprocessPolicy;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 从公开目录、设计、模拟、发布到实际提交和子调用；外部执行使用真实回环 HTTP。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.service-tasks.worker-enabled=false", "agentflow.timers.enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ServiceTaskPublicApiIntegrationTest {
    private static final String DEFINITIONS = "/api/v1/process-definitions";
    private static final String OPTIONS = DEFINITIONS + "/service-task-options";
    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @MockitoSpyBean AuthService auth;
    @Autowired ServiceTaskGatewayConfiguration configuration;
    @Autowired ServiceTaskCatalog catalog;
    @Autowired ServiceTaskOperationService operations;
    @Autowired JdbcServiceTaskOperationRepository operationStore;
    @Autowired ServiceTaskGateway gateway;
    @Autowired JdbcTemplate jdbc;
    @Autowired TaskService tasks;
    @Autowired RuntimeService runtime;
    private ServiceTaskTestProvider provider;
    private ServiceTaskGatewayConfiguration.Operation declaration;
    private ServiceTaskContract contract;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_SERVICE_PUBLIC_URL", "jdbc:h2:mem:service-public;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"));
        properties.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_SERVICE_PUBLIC_DRIVER", "org.h2.Driver"));
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_SERVICE_PUBLIC_USER", "sa"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_SERVICE_PUBLIC_PASSWORD", ""));
    }

    @BeforeEach
    void install() throws Exception {
        provider = new ServiceTaskTestProvider(json);
        declaration = provider.declaration("public." + UUID.randomUUID());
        configuration.setEnabled(true); configuration.setTenants(Map.of("demo", List.of(declaration))); catalog.install();
        contract = configuration.find("demo", declaration.getKey(), 1).orElseThrow().contract();
        doReturn(new Actor("demo", "designer", Set.of("PROCESS_ADMIN"))).when(auth).authenticate("catalog-designer");
        doReturn(new Actor("other", "other-admin", Set.of("ADMIN"))).when(auth).authenticate("catalog-other");
    }
    @AfterEach void close() { provider.close(); configuration.setEnabled(false); }

    @Test
    void directoryIsTenantScopedAndNeverDisclosesDeploymentTargetOrCredentials() throws Exception {
        var foreign = provider.declaration(contract.key()); foreign.setVersion(2); foreign.setName("另一租户专用契约");
        configuration.setTenants(Map.of("demo", List.of(declaration), "other", List.of(foreign))); catalog.install();
        var response = send(get(option(1)), "catalog-designer", null);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        var value = tree(response, 200);
        assertThat(value.path("version").isTextual()).isTrue();
        assertThat(value.path("contractDigest").asText()).isEqualTo(contract.digest());
        assertThat(value.toString()).doesNotContain("endpoint", "token", "targetDigest", "tenantId", "127.0.0.1", "synthetic-service-token", foreign.getName());
        assertThat(send(get(option(1)), "alice", null).getStatus()).isEqualTo(403);
        assertThat(send(get(option(2)), "admin", null).getStatus()).isEqualTo(404);
        assertThat(tree(send(get(option(2)), "catalog-other", null), 200).path("name").asText()).isEqualTo(foreign.getName());
        assertThat(send(get(option(1)), "catalog-other", null).getStatus()).isEqualTo(404);
    }

    @Test
    void latestDisabledVersionDoesNotFallBackAndLongVersionsRemainExactAcrossPages() throws Exception {
        var latest = provider.declaration(contract.key()); latest.setVersion(Long.MAX_VALUE); latest.setEnabled(false);
        configuration.setTenants(Map.of("demo", List.of(declaration, latest))); catalog.install();
        var directory = tree(send(get(OPTIONS).param("limit", "100"), "admin", null), 200);
        var current = java.util.stream.StreamSupport.stream(directory.path("items").spliterator(), false)
                .filter(item -> item.path("key").asText().equals(contract.key())).findFirst().orElseThrow();
        assertThat(current.path("version").asText()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(current.path("enabled").asBoolean()).isFalse();
        var page = tree(send(get(OPTIONS + "/" + contract.key() + "/versions").param("limit", "1"), "admin", null), 200);
        assertThat(page.path("items").get(0).path("version").asText()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(page.path("nextBeforeVersion").isTextual()).isTrue();
        var next = tree(send(get(OPTIONS + "/" + contract.key() + "/versions").param("beforeVersion", page.path("nextBeforeVersion").asText()), "admin", null), 200);
        assertThat(next.path("items").size()).isEqualTo(1); assertThat(next.path("items").get(0).path("version").asText()).isEqualTo("1");
        assertThat(tree(send(get(option(Long.MAX_VALUE)), "admin", null), 200).path("version").asText()).isEqualTo(Long.toString(Long.MAX_VALUE));
        for (String query : List.of("limit=0", "limit=101", "limit=01", "afterKey=", "tenantId=other")) {
            assertThat(send(get(OPTIONS + "?" + query), "admin", null).getStatus()).as(query).isEqualTo(400);
        }
        assertThat(send(get(OPTIONS).param("limit", "1", "2"), "admin", null).getStatus()).isEqualTo(400);
        assertThat(send(get(OPTIONS + "/" + contract.key() + "/versions/9223372036854775808"), "admin", null).getStatus()).isEqualTo(400);
    }

    @Test
    void publicDesignSimulationPublicationAndSubmissionUseOnlyTheSelectedInputs() throws Exception {
        var graph = graph("service", "review"); var schema = schema(false, null); var draft = create(graph, schema);
        assertThat(tree(send(post(DEFINITIONS + "/validate"), "admin", design(draft.path("key").asText(), graph, schema)), 200).path("errors").size()).isZero();
        var simulation = tree(send(post(DEFINITIONS + "/simulate"), "admin", Map.of("graph", graph, "formSchema", schema, "values", payload())), 200);
        assertThat(simulation.path("path").toString()).isEqualTo("[\"start\",\"service\",\"review\",\"end\"]");
        assertThat(provider.calls).isEmpty(); assertThat(countOperations()).isZero();
        var published = publish(draft); String id = application(published, payload()); submit(id, 200);
        var operation = operation(id); complete(operation);
        assertThat(provider.commands.get(operation.input().command().id()).inputs()).containsExactlyEntriesOf(Map.of("memo", "public-original"));
        assertThat(provider.calls.get(0).request().toString()).doesNotContain("never-send");
        approve(id); assertThat(app(id).path("status").asText()).isEqualTo("APPROVED");
        assertThat(provider.effects).hasValue(1);
    }

    @Test
    void unknownContractsAndHiddenOrMaskedFieldsCannotBeSavedEvenByAdministrator() throws Exception {
        var original = graph("service", "review"); var nodes = new ArrayList<>(original.nodes());
        var unknown = new LinkedHashMap<>(serviceProperties()); unknown.put(ServiceTaskPolicy.KEY_PROPERTY, "missing." + UUID.randomUUID());
        nodes.replaceAll(node -> node.id().equals("service") ? new Node(node.id(), node.name(), node.type(), unknown) : node);
        assertError(send(post(DEFINITIONS), "admin", design(key(), new Graph(nodes, original.edges()), schema(false, null))), "SERVICE_TASK_CONTRACT_UNAVAILABLE:service");
        for (FieldVisibility visibility : List.of(FieldVisibility.HIDDEN, FieldVisibility.MASKED)) {
            assertError(send(post(DEFINITIONS), "admin", design(key(), original, schema(true, visibility))), "SERVICE_TASK_INPUT_NOT_READABLE:service");
        }
        assertError(send(post(DEFINITIONS), "admin", design(key(), original, schema(true, null))), "SERVICE_TASK_INPUT_NOT_READABLE:service");
        var readable = create(original, schema(true, FieldVisibility.READ_ONLY));
        var update = new LinkedHashMap<>(design(readable.path("key").asText(), original, schema(true, FieldVisibility.HIDDEN)));
        update.put("expectedRevision", readable.path("revision").asLong());
        assertError(send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(DEFINITIONS + "/" + readable.path("id").asText()), "admin", update), "SERVICE_TASK_INPUT_NOT_READABLE:service");
        assertThat(tree(send(get(DEFINITIONS + "/" + readable.path("id").asText()), "admin", null), 200).path("revision").asLong()).isZero();
        assertThat(provider.calls).isEmpty();
    }

    @Test
    void sensitivityCannotBeDowngradedAndUserSettingsCannotSupplyAnExecutableTarget() throws Exception {
        var publicContract = provider.declaration("public." + UUID.randomUUID());
        publicContract.setParameters(List.of(new ServiceTaskContract.Parameter("memo", ServiceTaskContract.Type.TEXT, true, false)));
        configuration.setTenants(Map.of("demo", List.of(publicContract))); catalog.install();
        contract = configuration.find("demo", publicContract.getKey(), 1).orElseThrow().contract();
        assertError(send(post(DEFINITIONS), "admin", design(key(), graph("service", "review"), schema(true, FieldVisibility.READ_ONLY))), "SERVICE_TASK_INPUT_SENSITIVITY_LOSS:service");
        for (String property : List.of("url", "delegateExpression", "script", "resultVariable")) {
            var unsafe = new LinkedHashMap<>(serviceProperties()); unsafe.put(property, "untrusted");
            var graph = graph("service", "review"); var nodes = new ArrayList<>(graph.nodes());
            nodes.replaceAll(node -> node.id().equals("service") ? new Node(node.id(), node.name(), node.type(), unsafe) : node);
            assertError(send(post(DEFINITIONS), "admin", design(key(), new Graph(nodes, graph.edges()), schema(false, null))), "INVALID_SERVICE_TASK_POLICY:service");
        }
    }

    @Test
    void disabledOriginalContractBlocksPublicationSimulationAndNewSubmission() throws Exception {
        var graph = graph("review", "service"); var schema = schema(false, null); var draft = create(graph, schema);
        declaration.setEnabled(false);
        assertError(send(post(DEFINITIONS + "/" + draft.path("id").asText() + "/publish?expectedRevision=0"), "admin", Map.of("changeNote", "验证停用")), "SERVICE_TASK_CONTRACT_UNAVAILABLE:service");
        assertError(send(post(DEFINITIONS + "/simulate"), "admin", Map.of("graph", graph, "formSchema", schema, "values", payload())), "SERVICE_TASK_CONTRACT_UNAVAILABLE:service");
        declaration.setEnabled(true); var published = publish(draft); String id = application(published, payload());
        declaration.setEnabled(false); submit(id, 422);
        assertThat(app(id).path("status").asText()).isEqualTo("DRAFT");
        assertThat(runtime.createProcessInstanceQuery().variableValueEquals("applicationId", id).count()).isZero();
        assertThat(countOperations()).isZero(); assertThat(provider.calls).isEmpty();
        declaration.setEnabled(true); submit(id, 200); assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", id).count()).isEqualTo(1);
    }

    @Test
    void invalidMappedValueFailsBeforeTheFirstHumanTaskInsteadOfAtALaterServiceNode() throws Exception {
        var published = publish(create(graph("review", "service"), schema(false, null)));
        String id = application(published, Map.of("reason", "x".repeat(9000)));
        var response = send(post("/api/v1/applications/" + id + "/submit"), "alice", Map.of("expectedVersion", 1));
        assertError(response, "INVALID_SERVICE_TASK_INPUTS:service");
        assertThat(app(id).path("status").asText()).isEqualTo("DRAFT");
        assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", id).count()).isZero(); assertThat(countOperations()).isZero();
    }

    @Test
    void publicSubprocessServiceUsesChildIdentityAndHonorsParentPause() throws Exception {
        var child = publish(create(graph("service", "review"), schema(false, null)));
        var parent = publish(create(parentGraph(child.path("key").asText()), schema(false, null)));
        String id = application(parent, payload()); submit(id, 200);
        String childId = childId(id); var operation = operation(childId);
        assertThat(operation.input().command().binding().applicationId().toString()).isEqualTo(childId).isNotEqualTo(id);
        control(id, "pause"); assertThat(operations.claim("demo", operation.input().command().id(), Instant.now())).isNull();
        assertThat(provider.calls).isEmpty(); control(id, "resume"); complete(operation); approve(childId);
        assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", id).count()).isEqualTo(1);
        approve(id); assertThat(app(id).path("status").asText()).isEqualTo("APPROVED"); assertThat(provider.effects).hasValue(1);
    }

    @Test
    void childActivationRechecksContractAndRollsBackTheWholeFirstSubmission() throws Exception {
        var child = publish(create(graph("service", "review"), schema(false, null)));
        var parent = publish(create(parentGraph(child.path("key").asText()), schema(false, null)));
        String id = application(parent, payload()); declaration.setEnabled(false); submit(id, 422);
        assertThat(app(id).path("status").asText()).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_subprocess_call WHERE tenant_id='demo' AND parent_application_id=?", Integer.class, id)).isZero();
        assertThat(countOperations()).isZero(); assertThat(provider.calls).isEmpty();
        declaration.setEnabled(true); submit(id, 200); assertThat(operation(childId(id)).attempts()).isZero();
    }

    @Test
    void disabledInstalledReferenceCanRemainInADraftButBlocksParentPublication() throws Exception {
        var child = publish(create(graph("service", "review"), schema(false, null)));
        declaration.setEnabled(false);
        var disabledDraft = create(graph("service", "review"), schema(false, null));
        assertThat(disabledDraft.path("graph").path("nodes").get(1).path("properties").path(ServiceTaskPolicy.DIGEST_PROPERTY).asText()).isEqualTo(contract.digest());
        var parent = create(parentGraph(child.path("key").asText()), schema(false, null));
        var response = send(post(DEFINITIONS + "/" + parent.path("id").asText() + "/publish?expectedRevision=0"), "admin", Map.of("changeNote", "核对子操作可用性"));
        assertError(response, "SERVICE_TASK_CONTRACT_UNAVAILABLE");
        assertThat(countOperations()).isZero(); assertThat(provider.calls).isEmpty();
    }

    private JsonNode create(Graph graph, FormSchema schema) throws Exception { return tree(send(post(DEFINITIONS), "admin", design(key(), graph, schema)), 200); }
    private JsonNode publish(JsonNode draft) throws Exception {
        return tree(send(post(DEFINITIONS + "/" + draft.path("id").asText() + "/publish?expectedRevision=" + draft.path("revision").asLong()), "admin", Map.of("changeNote", "核对原操作、字段及人工审批后发布")), 200);
    }
    private String application(JsonNode definition, Map<String, Object> payload) throws Exception {
        return tree(send(post("/api/v1/applications"), "alice", Map.of("businessNo", key(), "processKey", definition.path("key").asText(),
                "definitionVersion", definition.path("version").asLong(), "title", "公开服务任务验收", "payload", payload)), 201).path("id").asText();
    }
    private void submit(String id, int expected) throws Exception { tree(send(post("/api/v1/applications/" + id + "/submit"), "alice", Map.of("expectedVersion", app(id).path("version").asLong())), expected); }
    private JsonNode app(String id) throws Exception { return tree(send(get("/api/v1/applications/" + id), "alice", null), 200); }
    private void approve(String id) throws Exception {
        String task = tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult().getId();
        tree(send(post("/api/v1/tasks/" + task + "/actions"), "finance", Map.of("action", "APPROVE", "expectedVersion", app(id).path("version").asLong(), "comment", "核对实际业务后批准")), 200);
    }
    private void control(String id, String action) throws Exception {
        tree(send(post("/api/v1/applications/" + id + "/rounds/1/runtime/" + action), "admin", Map.of("expectedVersion", app(id).path("version").asLong(), "reason", "验证父子运行控制")), 200);
    }
    private ServiceTaskOperation operation(String id) {
        String operation = jdbc.queryForObject("SELECT id FROM service_task_operation WHERE tenant_id='demo' AND application_id=?", String.class, id);
        return operationStore.find("demo", UUID.fromString(operation)).orElseThrow().operation();
    }
    private void complete(ServiceTaskOperation operation) {
        UUID id = operation.input().command().id(); var claimed = operations.claim("demo", id, Instant.now()); assertThat(claimed).isNotNull();
        operations.finish(claimed, gateway.execute(claimed.input()), Instant.now());
        assertThat(operationStore.find("demo", id).orElseThrow().operation().status()).isEqualTo(ServiceTaskOperation.Status.APPLIED);
        assertThat(operations.claim("demo", id, Instant.now())).isNull();
        assertThat(operationStore.find("demo", id).orElseThrow().progress()).isEqualTo(JdbcServiceTaskOperationRepository.Progress.ADVANCED);
    }
    private String childId(String parent) { return jdbc.queryForObject("SELECT child_application_id FROM approval_subprocess_call WHERE tenant_id='demo' AND parent_application_id=?", String.class, parent); }
    private int countOperations() { return jdbc.queryForObject("SELECT COUNT(*) FROM service_task_operation WHERE tenant_id='demo' AND operation_key=?", Integer.class, contract.key()); }
    private String option(long version) { return OPTIONS + "/" + contract.key() + "/versions/" + version; }
    private MockHttpServletResponse send(MockHttpServletRequestBuilder request, String user, Object input) throws Exception {
        String token = user.startsWith("catalog-") ? user : auth.login("demo", user, "demo").token();
        request.header("Authorization", "Bearer " + token).header("Idempotency-Key", UUID.randomUUID().toString());
        if (input != null) request.contentType("application/json").content(json.write(input));
        return mvc.perform(request).andReturn().getResponse();
    }
    private JsonNode tree(MockHttpServletResponse response, int status) {
        assertThat(response.getStatus()).as(response.getContentAsByteArray().length == 0 ? "empty response" : new String(response.getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(status);
        return json.read(new String(response.getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8), JsonNode.class);
    }
    private void assertError(MockHttpServletResponse response, String error) { assertThat(tree(response, 422).path("details").path("definitionErrors").toString()).contains(error); }
    private static String key() { return "service-public-" + UUID.randomUUID(); }
    private static Map<String, Object> payload() { return Map.of("reason", "public-original", "secret", "never-send"); }
    private static Map<String, Object> design(String key, Graph graph, FormSchema schema) { return Map.of("key", key, "name", "公开服务任务流程", "graph", graph, "formSchema", schema); }
    private Map<String, String> serviceProperties() {
        return Map.of(ServiceTaskPolicy.KEY_PROPERTY, contract.key(), ServiceTaskPolicy.VERSION_PROPERTY, Long.toString(contract.version()),
                ServiceTaskPolicy.DIGEST_PROPERTY, contract.digest(), ServiceTaskPolicy.INPUT_PREFIX + "memo", "reason");
    }
    private Graph graph(String... steps) {
        var nodes = new ArrayList<Node>(); var edges = new ArrayList<Edge>(); nodes.add(new Node("start", "开始", NodeType.START, Map.of())); String previous = "start";
        for (String step : steps) {
            nodes.add(new Node(step, step, step.equals("service") ? NodeType.SERVICE_TASK : NodeType.USER_TASK, step.equals("service") ? serviceProperties() : Map.of("assigneeRule", "user:finance")));
            edges.add(new Edge("edge" + edges.size(), previous, step, "")); previous = step;
        }
        nodes.add(new Node("end", "结束", NodeType.END, Map.of())); edges.add(new Edge("edge" + edges.size(), previous, "end", "")); return new Graph(nodes, edges);
    }
    private Graph parentGraph(String childKey) {
        return new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("call", "独立子审批", NodeType.SUB_PROCESS, new SubprocessPolicy(childKey, 1, Map.of("reason", "reason")).properties()),
                new Node("review", "父审批", NodeType.USER_TASK, Map.of("assigneeRule", "user:finance")), new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "call", ""), new Edge("b", "call", "review", ""), new Edge("c", "review", "end", "")));
    }
    private FormSchema schema(boolean sensitive, FieldVisibility visibility) {
        return new FormSchema(2, List.of(new FormSchema.Field("reason", "说明", FormSchema.FieldType.TEXT, true,
                null, null, null, null, null, null, null, sensitive, visibility == null ? null : Map.of("service", visibility)),
                new FormSchema.Field("secret", "未映射隐私", FormSchema.FieldType.TEXT, false, null, null, null, null, null, null, null, true, null)));
    }
}
