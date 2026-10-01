package io.agentflow.definition;

import io.agentflow.auth.AuthService;
import io.agentflow.common.JsonUtil;
import io.agentflow.template.ClasspathProcessTemplateCatalog;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static io.agentflow.definition.DefinitionModels.*;

/**
 * 未保存设计试算不落库，并与同图的真实 Flowable 路由一致。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:definition-preview;DB_CLOSE_DELAY=-1", "agentflow.auth.demo-enabled=true"})
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class DefinitionPreviewIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired AuthService auth;
    @Autowired ClasspathProcessTemplateCatalog catalog;
    @Autowired DefinitionApplicationService definitions;
    @Autowired RuntimeService runtime;
    @Autowired TaskService tasks;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.flowable.engine.RepositoryService engineRepository;

    @Test
    void subprocessPreflightIsReadOnlyAndLimitsDependencyDetailsToDesigners() throws Exception {
        String key = "preflight-" + UUID.randomUUID();
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("call", "子审批", NodeType.SUB_PROCESS, new SubprocessPolicy("missing-" + UUID.randomUUID(), 1, Map.of()).properties()),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "call", ""), new Edge("b", "call", "end", "")));
        var body = Map.of("key", key, "graph", graph);
        var before = snapshot();
        mvc.perform(post("/api/v1/process-definitions/validate").header("Authorization", token("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.write(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("errors", org.hamcrest.Matchers.contains("SUBPROCESS_DEFINITION_UNAVAILABLE:call")));
        mvc.perform(post("/api/v1/process-definitions/validate").header("Authorization", token("employee"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.write(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("errors", org.hamcrest.Matchers.empty()));
        assertThat(snapshot()).isEqualTo(before);
        mvc.perform(post("/api/v1/process-definitions").header("Authorization", token("admin"))
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("key", key, "name", "草稿待完善依赖", "graph", graph))))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("DRAFT"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_definition WHERE process_key=?", Integer.class, key)).isEqualTo(1);
    }

    @Test
    void fieldPreviewUsesNodeRestrictionsAndDoesNotWriteBusinessOrEngineState() throws Exception {
        var schema = Map.of("schemaVersion", 2, "fields", List.of(
                Map.of("key", "amount", "label", "金额", "type", "NUMBER", "required", false, "sensitive", true,
                        "nodeAccess", Map.of("manager", "READ_ONLY", "finance", "HIDDEN")),
                Map.of("key", "items", "label", "明细", "type", "TABLE", "required", false, "columns", List.of(
                        Map.of("key", "account", "label", "账号", "type", "TEXT", "required", false, "sensitive", true)))));
        var values = Map.of("amount", "125.50", "items", List.of(Map.of("account", "private-account")));
        var before = snapshot();
        mvc.perform(post("/api/v1/process-definitions/field-preview").header("Authorization", token("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("formSchema", schema, "values", values, "nodeIds", List.of("manager")))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("payload.amount").value("125.50"))
                .andExpect(jsonPath("payload.items[0].account").value("已脱敏"))
                .andExpect(jsonPath("restricted").value(true));
        for (var nodes : List.of(List.of(), List.of("manager", "finance"))) {
            var response = mvc.perform(post("/api/v1/process-definitions/field-preview").header("Authorization", token("admin"))
                            .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("formSchema", schema, "values", values, "nodeIds", nodes))))
                    .andExpect(status().isOk()).andExpect(jsonPath("payload.amount").doesNotExist())
                    .andExpect(jsonPath("schema.fields[0].key").value("items")).andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("125.50", "private-account");
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void fieldPreviewRequiresDesignerAndBoundedValidTestInput() throws Exception {
        var body = Map.of("formSchema", Map.of("schemaVersion", 1, "fields", List.of()), "values", Map.of(), "nodeIds", List.of());
        var before = snapshot();
        mvc.perform(post("/api/v1/process-definitions/field-preview").contentType(MediaType.APPLICATION_JSON).content(json.write(body)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/process-definitions/field-preview").header("Authorization", token("employee"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.write(body))).andExpect(status().isForbidden());
        var invalid = new java.util.LinkedHashMap<String, Object>(body);
        for (var nodes : List.of(List.of(" "), List.of("n".repeat(129)),
                java.util.stream.IntStream.range(0, 201).mapToObj(index -> "node" + index).toList())) {
            invalid.put("nodeIds", nodes);
            mvc.perform(post("/api/v1/process-definitions/field-preview").header("Authorization", token("admin"))
                            .contentType(MediaType.APPLICATION_JSON).content(json.write(invalid))).andExpect(status().isBadRequest());
        }
        invalid.put("nodeIds", List.of()); invalid.put("values", Map.of("undeclared", "must-not-be-echoed"));
        var response = mvc.perform(post("/api/v1/process-definitions/field-preview").header("Authorization", token("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.write(invalid))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("details.fieldErrors.undeclared").value("UNKNOWN_FIELD")).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("must-not-be-echoed");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void previewIsReadOnlyAndReturnsTypedDecisionTrace() throws Exception {
        var template = catalog.get("leave-request");
        var before = snapshot();
        mvc.perform(post("/api/v1/process-definitions/simulate").header("Authorization", token("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("graph", template.graph(),
                                "formSchema", template.formSchema(), "values", values("4")))))
                .andExpect(status().isOk()).andExpect(jsonPath("path[3]").value("review"))
                .andExpect(jsonPath("decisions[0].branches[0].outcome").value("MATCHED"));
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void permissionsAndInvalidTestDataCannotCreateResources() throws Exception {
        var template = catalog.get("leave-request");
        String body = json.write(Map.of("graph", template.graph(), "formSchema", template.formSchema(), "values", Map.of()));
        var before = snapshot();
        mvc.perform(post("/api/v1/process-definitions/simulate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/process-definitions/simulate").header("Authorization", token("employee"))
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/process-definitions/simulate").header("Authorization", token("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("details.fieldErrors.durationDays").value("REQUIRED"));
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void structuralErrorsIdentifyObjectsAndAreAlsoRejectedWhenCreatingDrafts() throws Exception {
        Graph bad = new Graph(List.of(new Node("s", "开始", NodeType.START, Map.of()), new Node("e", "结束", NodeType.END, Map.of())),
                List.of(new Edge("one", "s", "e", ""), new Edge("two", "s", "e", "")));
        String body = json.write(Map.of("graph", bad, "values", Map.of()));
        mvc.perform(post("/api/v1/process-definitions/simulate").header("Authorization", token("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("details.definitionErrors[0]").value("SINGLE_OUTGOING_REQUIRED:s"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> definitions.create("demo", "invalid-preview", "错误图", bad))
                .isInstanceOf(DefinitionValidationException.class);
    }

    @Test
    void unsavedChangedConditionMatchesActualEngineAtBothSidesOfThreshold() {
        var template = catalog.get("leave-request");
        Graph changed = new Graph(template.graph().nodes(), template.graph().edges().stream().map(edge ->
                edge.condition().isBlank() ? edge : new Edge(edge.id(), edge.source(), edge.target(), "durationDays > 2", false)).toList());
        var draft = definitions.create("demo", "preview-" + UUID.randomUUID(), "模拟一致性", changed, template.formSchema());
        var published = definitions.publish(new io.agentflow.common.Actor("demo", "test-admin", java.util.Set.of("ADMIN")), draft.id(), draft.revision(), "集成测试发布");
        for (String days : List.of("2", "2.5")) {
            var preview = definitions.simulatePreview(changed, template.formSchema(), new EvaluationContext(values(days)));
            var instance = runtime.startProcessInstanceByKeyAndTenantId(published.key(), Map.of("formData", values(days),
                    "formFieldTypes", template.formSchema().fieldTypes()), "demo");
            var manager = tasks.createTaskQuery().processInstanceId(instance.getId()).singleResult();
            assertThat(manager.getTaskDefinitionKey()).isEqualTo("manager");
            tasks.complete(manager.getId());
            var review = tasks.createTaskQuery().processInstanceId(instance.getId()).singleResult();
            assertThat(review != null).isEqualTo(preview.path().contains("review"));
            if (review != null) { assertThat(review.getTaskDefinitionKey()).isEqualTo("review"); tasks.complete(review.getId()); }
            assertThat(runtime.createProcessInstanceQuery().processInstanceId(instance.getId()).count()).isZero();
        }
    }

    @Test
    void upgradesWithoutWritesAndStrictlyBindsGraphVersion() throws Exception {
        var template = catalog.get("leave-request");
        String oldGraph = json.write(template.graph()).replace(",\"conditionLanguageVersion\":1", "");
        String body = "{\"graph\":" + oldGraph + "}";
        var before = snapshot();
        mvc.perform(post("/api/v1/process-definitions/upgrade-conditions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/process-definitions/upgrade-conditions").header("Authorization", token("employee"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/process-definitions/upgrade-conditions").header("Authorization", token("admin"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("conditionLanguageVersion").value(2));
        assertThat(json.read(oldGraph, Graph.class).conditionLanguageVersion()).isEqualTo(1);
        for (String version : List.of("0", "3", "-1", "null", "\"2\"", "2.5", "2147483648", "true")) {
            String bad = "{\"graph\":" + oldGraph.substring(0, oldGraph.length() - 1) + ",\"conditionLanguageVersion\":" + version + "}}";
            mvc.perform(post("/api/v1/process-definitions/upgrade-conditions").header("Authorization", token("admin"))
                    .contentType(MediaType.APPLICATION_JSON).content(bad)).andExpect(status().is4xxClientError());
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void reportsSyntaxPositionAndRejectsUnknownEnumBeforeSavingOrPublishing() throws Exception {
        var template = catalog.get("leave-request");
        for (String condition : List.of("durationDays >", "leaveType IN [\"UNKNOWN\"]")) {
            Graph graph = conditionGraph(template.graph(), condition, 2);
            String body = json.write(Map.of("graph", graph, "formSchema", template.formSchema()));
            var result = mvc.perform(post("/api/v1/process-definitions/validate").header("Authorization", token("admin"))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn();
            assertThat(result.getResponse().getContentAsString()).contains(condition.endsWith(">") ? "INVALID_CONDITION_AT:" : "INVALID_CONDITION:");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> definitions.create("demo", "invalid-v2", "无效条件", graph, template.formSchema()))
                    .isInstanceOf(DefinitionValidationException.class);
        }
    }

    @Test
    void v2EngineMatchesSimulationAcrossMembershipGroupingAndExactThreshold() {
        var template = catalog.get("leave-request");
        Graph graph = conditionGraph(template.graph(), "(durationDays > 2 && leaveType IN [\"ANNUAL\"]) || !(reason EXISTS)", 2);
        var draft = definitions.create("demo", "v2-" + UUID.randomUUID(), "新版条件", graph, template.formSchema());
        assertThat(definitions.get("demo", draft.id()).graph()).isEqualTo(graph);
        var published = definitions.publish(new io.agentflow.common.Actor("demo", "test-admin", java.util.Set.of("ADMIN")), draft.id(), draft.revision(), "条件验证");
        var definition = engineRepository.createProcessDefinitionQuery().processDefinitionKey(published.key()).processDefinitionTenantId("demo").singleResult();
        var model = engineRepository.getBpmnModel(definition.getId());
        assertThat(model.getMainProcess().findFlowElementsOfType(org.flowable.bpmn.model.SequenceFlow.class))
                .anySatisfy(flow -> assertThat(flow.getConditionExpression()).contains("', 2)"));
        for (String days : List.of("2", "2.000000000000000001")) {
            var preview = definitions.simulatePreview(graph, template.formSchema(), new EvaluationContext(values(days)));
            assertEngineRoute(published.key(), Map.of("formData", values(days), "formFieldTypes", template.formSchema().fieldTypes()), preview.path().contains("review"));
        }
    }

    @Test
    void historicalTwoArgumentBpmnKeepsConnectorTextLiteralAfterEngineUpgrade() {
        var template = catalog.get("leave-request");
        Graph graph = conditionGraph(template.graph(), "reason == a && b", 1);
        var draft = DefinitionDraft.create(UUID.randomUUID(), "demo", "old-bpmn-" + UUID.randomUUID(), "历史两参数表达式", graph, template.formSchema());
        String historicalXml = FlowableDefinitionDeploymentAdapter.RestrictedBpmnWriter.write(draft).replace("', 1)", "')");
        assertThat(historicalXml).doesNotContain("', 1)");
        engineRepository.createDeployment().tenantId("demo").addString("legacy.bpmn20.xml", historicalXml).deploy();
        assertEngineRoute(draft.key(), Map.of("formData", Map.of("reason", "a && b"), "formFieldTypes", template.formSchema().fieldTypes()), true);
        assertEngineRoute(draft.key(), Map.of("formData", Map.of("reason", "a"), "formFieldTypes", template.formSchema().fieldTypes()), false);
    }

    private Graph conditionGraph(Graph base, String condition, int languageVersion) {
        return new Graph(base.nodes(), base.edges().stream().map(edge -> edge.condition().isBlank() ? edge
                : new Edge(edge.id(), edge.source(), edge.target(), condition, false)).toList(), languageVersion);
    }

    private void assertEngineRoute(String key, Map<String, Object> variables, boolean shouldReview) {
        var instance = runtime.startProcessInstanceByKeyAndTenantId(key, variables, "demo");
        var manager = tasks.createTaskQuery().processInstanceId(instance.getId()).singleResult();
        assertThat(manager.getTaskDefinitionKey()).isEqualTo("manager");
        tasks.complete(manager.getId());
        var review = tasks.createTaskQuery().processInstanceId(instance.getId()).singleResult();
        assertThat(review != null).isEqualTo(shouldReview);
        if (review != null) { assertThat(review.getTaskDefinitionKey()).isEqualTo("review"); tasks.complete(review.getId()); }
        assertThat(runtime.createProcessInstanceQuery().processInstanceId(instance.getId()).count()).isZero();
    }

    @Test
    void incompleteNumericCoverageCanBeSavedButCannotPublishOrLeaveEngineArtifacts() throws Exception {
        var template = catalog.get("leave-request");
        Graph graph = coverageGraph("durationDays > 8", "durationDays <= 5", false);
        var draft = definitions.create("demo", "gap-" + UUID.randomUUID(), "区间遗漏", graph, template.formSchema());
        var before = snapshot();
        String body = json.write(Map.of("graph", graph, "formSchema", template.formSchema()));
        mvc.perform(post("/api/v1/process-definitions/validate").header("Authorization", token("admin"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("errors[0]").value("BRANCH_COVERAGE_GAP:route"))
                .andExpect(jsonPath("branchDiagnostics[0].severity").value("ERROR"))
                .andExpect(jsonPath("branchDiagnostics[0].sampleValue").value("6.5"));
        assertThat(snapshot()).isEqualTo(before);
        mvc.perform(post("/api/v1/process-definitions/" + draft.id() + "/publish?expectedRevision=0")
                .header("Authorization", token("admin")).header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("changeNote", "不能发布遗漏分支"))))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("details.definitionErrors[0]").value("BRANCH_COVERAGE_GAP:route"));
        assertThat(snapshot()).isEqualTo(before);
        assertThat(definitions.get("demo", draft.id()).revision()).isZero();
        assertThat(definitions.get("demo", draft.id()).status()).isEqualTo(DraftStatus.DRAFT);
        assertThat(engineRepository.createProcessDefinitionQuery().processDefinitionKey(draft.key()).count()).isZero();
        assertThat(definitions.simulatePreview(graph, template.formSchema(), new EvaluationContext(values("4"))).path()).doesNotContain("review");
        Graph fixed = coverageGraph("durationDays > 8", "", true);
        var updated = definitions.update("demo", draft.id(), draft.name(), fixed, template.formSchema(), draft.revision());
        assertThat(definitions.publish(new io.agentflow.common.Actor("demo", "admin", java.util.Set.of("ADMIN")), updated.id(), updated.revision(), "补齐默认分支").status())
                .isEqualTo(DraftStatus.PUBLISHED);
    }

    @Test
    void overlapRemainsWarningAndPublishedRecordPreservesActualOrderAndEvidence() {
        var template = catalog.get("leave-request");
        Graph graph = coverageGraph("durationDays >= 2", "durationDays <= 2", false);
        var inspection = definitions.inspect("demo", graph, template.formSchema());
        assertThat(inspection.errors()).isEmpty();
        assertThat(inspection.branchDiagnostics()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo(BranchCoverageAnalyzer.Code.BRANCH_OVERLAP);
            assertThat(issue.sampleValue()).isEqualTo("2");
            assertThat(issue.edgeIds()).containsExactly("route_review", "route_end");
        });
        var draft = definitions.create("demo", "overlap-" + UUID.randomUUID(), "顺序命中", graph, template.formSchema());
        var published = definitions.publish(new io.agentflow.common.Actor("demo", "admin", java.util.Set.of("ADMIN")), draft.id(), draft.revision(), "保留先复核的业务优先级");
        var evidence = definitions.publication("demo", published.id()).orElseThrow().validation();
        assertThat(evidence.checks()).contains(DefinitionPublication.Check.BRANCH_COVERAGE);
        assertThat(evidence.branchDiagnostics()).isEqualTo(inspection.branchDiagnostics());
        assertEngineRoute(published.key(), Map.of("formData", values("2"), "formFieldTypes", template.formSchema().fieldTypes()), true);
        assertThat(definitions.simulatePreview(graph, template.formSchema(), new EvaluationContext(values("2"))).path()).contains("review");
        assertThat(definitions.publication("demo", published.id()).orElseThrow().validation()).isEqualTo(evidence);
        var legacy = json.read("{\"nodeCount\":2,\"edgeCount\":1,\"fieldCount\":0,\"formBound\":false,\"checks\":[\"GRAPH_STRUCTURE\"]}", DefinitionPublication.ValidationSummary.class);
        assertThat(legacy.branchDiagnostics()).isEmpty();
        assertThat(legacy.checks()).doesNotContain(DefinitionPublication.Check.BRANCH_COVERAGE);
    }

    private Graph coverageGraph(String first, String second, boolean fallback) {
        var base = catalog.get("leave-request").graph();
        return new Graph(base.nodes(), base.edges().stream().map(edge -> switch (edge.id()) {
            case "route_review" -> new Edge(edge.id(), edge.source(), edge.target(), first);
            case "route_end" -> new Edge(edge.id(), edge.source(), edge.target(), second, fallback);
            default -> edge;
        }).toList(), 2);
    }

    private Map<String, Object> values(String days) {
        return Map.of("leaveType", "ANNUAL", "startDate", "2026-09-24", "durationDays", days, "reason", "仅用于模拟验收");
    }

    private Map<String, Integer> snapshot() {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String table : List.of("approval_definition", "approval_application", "audit_event", "request_idempotency", "ACT_RU_EXECUTION", "ACT_RU_TASK")) {
            counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
        }
        return counts;
    }

    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
}
