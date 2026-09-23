package io.agentflow.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.auth.AuthService;
import org.junit.jupiter.api.Test;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.definition.DefinitionModels;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static io.agentflow.support.MutationRequests.post;

/**
 * 版本化表单的真实接口、数据库和流程引擎集成验证。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:versioned-forms;DB_CLOSE_DELAY=-1", "agentflow.auth.demo-enabled=true"})
@AutoConfigureMockMvc
class VersionedFormsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @org.springframework.boot.test.mock.mockito.SpyBean AuthService auth;
    @Autowired JdbcTemplate jdbc;
    @Autowired RuntimeService runtime;
    @Autowired TaskService tasks;
    @Autowired DefinitionApplicationService definitions;

    @Test
    void detailRowsFreezeAcrossReturnAndResubmissionAndRejectCellErrorsBeforeStarting() throws Exception {
        Map<String, Object> schema = tableSchema();
        JsonNode definition = published("detail-" + UUID.randomUUID(), schema, graph());
        JsonNode draft = create(definition, Map.of("items", List.of(Map.of("name", "待补数量"))));
        String id = draft.path("id").asText();
        var failed = send("POST", application(id, "submit"), "alice", Map.of("expectedVersion", 1));
        assertThat(failed.getStatus()).isEqualTo(422);
        assertThat(tree(failed).at("/details/fieldErrors/items[0].quantity").asText()).isEqualTo("REQUIRED");
        assertThat(runtime.createProcessInstanceQuery().variableValueEquals("applicationId", id).count()).isZero();
        assertThat(getApplication(id).path("version").asLong()).isEqualTo(1);
        Map<String, Object> firstValues = Map.of("items", List.of(Map.of("name", "显示器", "quantity", "0002.00"), Map.of("name", "键盘", "quantity", "3")));
        ok(send("PUT", "/api/v1/applications/" + id, "alice", Map.of("expectedVersion", 1, "title", "设备申请", "payload", firstValues)), 200);
        ok(send("POST", application(id, "submit"), "alice", Map.of("expectedVersion", 2)), 200);
        JsonNode original = rounds(id).get(0);
        decide(id, "RETURN", 3);
        Map<String, Object> changedValues = Map.of("items", List.of(Map.of("name", "键盘", "quantity", "1")));
        ok(send("PUT", "/api/v1/applications/" + id, "alice", Map.of("expectedVersion", 4, "title", "补正明细", "payload", changedValues)), 200);
        ok(send("POST", application(id, "submit"), "alice", Map.of("expectedVersion", 5)), 200);
        assertThat(rounds(id).get(0).path("payload")).isEqualTo(original.path("payload"));
        assertThat(rounds(id).get(0).path("formSchema")).isEqualTo(original.path("formSchema"));
        assertThat(rounds(id).get(1).path("payload")).isEqualTo(mapper.valueToTree(changedValues));
        assertThat(getApplication(id).path("formSchema")).isEqualTo(mapper.valueToTree(schema));
    }

    @Test
    void tablePresenceSimulationMatchesRealEngine() throws Exception {
        Map<String, Object> schema = Map.of("schemaVersion", 2, "fields", List.of(Map.of("key", "items", "label", "明细", "type", "TABLE", "required", false,
                "columns", List.of(field("name", "名称", "TEXT", true)))));
        for (String operator : List.of("EXISTS", "NOT_EXISTS")) {
            JsonNode definition = published("table-route-" + UUID.randomUUID(), schema, conditionalGraph("items " + operator));
            for (boolean present : List.of(true, false)) {
                Map<String, Object> values = Map.of("items", present ? List.of(Map.of("name", "设备")) : List.of());
                String selected = present == operator.equals("EXISTS") ? "selected" : "fallback";
                var simulated = ok(send("POST", "/api/v1/process-definitions/" + definition.path("id").asText() + "/simulate", "admin", Map.of("values", values)), 200);
                assertThat(simulated.path("path")).isEqualTo(mapper.valueToTree(List.of("start", "gate", selected, "end")));
                JsonNode application = create(definition, values); String id = application.path("id").asText();
                ok(send("POST", application(id, "submit"), "alice", Map.of("expectedVersion", 1)), 200);
                assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult().getTaskDefinitionKey()).isEqualTo(selected);
            }
        }
    }

    @Test
    void tableSchemaBoundaryRejectsCoercionUnknownColumnsAndNestedTables() throws Exception {
        for (String mutation : List.of("stringRows", "fractionRows", "unknownColumn", "nestedTable", "badColumns", "versionOne")) {
            var schema = mapper.valueToTree(tableSchema()).deepCopy();
            var table = (com.fasterxml.jackson.databind.node.ObjectNode) schema.path("fields").get(0);
            switch (mutation) {
                case "stringRows" -> table.put("maxRows", "5");
                case "fractionRows" -> table.put("maxRows", 1.5);
                case "unknownColumn" -> ((com.fasterxml.jackson.databind.node.ObjectNode) table.path("columns").get(0)).put("hidden", true);
                case "nestedTable" -> ((com.fasterxml.jackson.databind.node.ObjectNode) table.path("columns").get(0)).put("type", "TABLE");
                case "badColumns" -> table.put("columns", "wrong");
                case "versionOne" -> ((com.fasterxml.jackson.databind.node.ObjectNode) schema).put("schemaVersion", 1);
                default -> throw new IllegalStateException(mutation);
            }
            var response = send("POST", "/api/v1/process-definitions", "admin", Map.of("key", "bad-table-" + UUID.randomUUID(),
                    "name", "错误明细", "graph", graph(), "formSchema", schema));
            assertThat(response.getStatus()).as(mutation).isEqualTo(422);
            assertThat(tree(response).path("code").asText()).isEqualTo("INVALID_FORM_SCHEMA");
        }
    }

    private Map<String, Object> tableSchema() {
        return Map.of("schemaVersion", 2, "fields", List.of(Map.of("key", "items", "label", "物品明细", "type", "TABLE", "required", true,
                "maxRows", 5, "columns", List.of(field("name", "名称", "TEXT", true),
                        Map.of("key", "quantity", "label", "数量", "type", "NUMBER", "required", true, "minimum", "0")))));
    }

    @Test
    void definitionPersistsAndReturnsItsFormSchema() throws Exception {
        Map<String, Object> schema = Map.of("schemaVersion", 1, "fields", List.of(
                Map.of("key", "reason", "label", "申请理由", "type", "TEXT", "required", true)));
        var response = mvc.perform(post("/api/v1/process-definitions").header("Authorization", token("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                                "key", "form-" + UUID.randomUUID(), "name", "动态表单", "graph", graph(), "formSchema", schema))))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode definition = mapper.readTree(response.getContentAsString());
        assertThat(definition.path("formSchema")).isEqualTo(mapper.valueToTree(schema));
    }

    @Test
    void validatesSixTypesAndDoesNotMutateAnIncompleteDraftOnSubmission() throws Exception {
        JsonNode definition = published("typed-" + UUID.randomUUID(), allTypes(), graph());
        JsonNode draft = create(definition, Map.of("confirmed", false));
        String id = draft.path("id").asText();
        var failed = send("POST", application(id, "submit"), "alice", Map.of("expectedVersion", 1));
        assertThat(failed.getStatus()).isEqualTo(422);
        assertThat(tree(failed).at("/details/fieldErrors/amount").asText()).isEqualTo("REQUIRED");
        assertThat(tree(failed).at("/details/fieldErrors/confirmed").isMissingNode()).isTrue();
        assertThat(getApplication(id).path("status").asText()).isEqualTo("DRAFT");
        assertThat(getApplication(id).path("version").asInt()).isEqualTo(1);
        assertThat(runtime.createProcessInstanceQuery().variableValueEquals("applicationId", id).count()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_submission_round WHERE application_id=?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE aggregate_id=? AND action='SUBMIT'", Integer.class, id)).isZero();
        Map<String, Object> payload = validPayload();
        JsonNode revised = ok(send("PUT", "/api/v1/applications/" + id, "alice", Map.of("expectedVersion", 1, "title", "完整六类型", "payload", payload)), 200);
        JsonNode submitted = ok(send("POST", application(id, "submit"), "alice", Map.of("expectedVersion", revised.path("version").asLong())), 200);
        assertThat(submitted.path("formSchema")).isEqualTo(definition.path("formSchema"));
        assertThat(submitted.path("payload").path("amount").asText()).isEqualTo("0009007199254740993.00");
        assertThat(submitted.path("payload").path("confirmed").booleanValue()).isFalse();
        JsonNode snapshot = rounds(id).get(0);
        assertThat(snapshot.path("formSchema")).isEqualTo(definition.path("formSchema"));
        assertThat(snapshot.path("payload")).isEqualTo(mapper.valueToTree(payload));
    }

    @Test
    void rejectsUnknownFieldsWrongTypesAndInvalidValuesWithoutSavingADraft() throws Exception {
        JsonNode definition = published("errors-" + UUID.randomUUID(), allTypes(), graph());
        List<Map.Entry<Map<String, Object>, Map<String, String>>> cases = List.of(
                Map.entry(Map.of("amount", 1.25), Map.of("amount", "INVALID_TYPE")),
                Map.entry(Map.of("amount", "1e30"), Map.of("amount", "INVALID_NUMBER")),
                Map.entry(Map.of("amount", " "), Map.of("amount", "INVALID_NUMBER")),
                Map.entry(Map.of("amount", "-1"), Map.of("amount", "BELOW_MINIMUM")),
                Map.entry(Map.of("amount", "100000000000000000000"), Map.of("amount", "ABOVE_MAXIMUM")),
                Map.entry(Map.of("date", "2026-02-29"), Map.of("date", "INVALID_DATE")),
                Map.entry(Map.of("choice", "other"), Map.of("choice", "INVALID_OPTION")),
                Map.entry(Map.of("confirmed", "false"), Map.of("confirmed", "INVALID_TYPE")),
                Map.entry(Map.of("reason", "x".repeat(31)), Map.of("reason", "TOO_LONG")),
                Map.entry(Map.of("tenantId", "forged", "nested", Map.of("x", 1)), Map.of("tenantId", "UNKNOWN_FIELD", "nested", "UNKNOWN_FIELD")));
        for (var testCase : cases) {
            String business = "INVALID-" + UUID.randomUUID();
            var response = send("POST", "/api/v1/applications", "alice", createBody(definition, business, testCase.getKey()));
            assertThat(response.getStatus()).isEqualTo(422);
            assertThat(tree(response).path("code").asText()).isEqualTo("FORM_VALIDATION_FAILED");
            assertThat(tree(response).at("/details/fieldErrors")).isEqualTo(mapper.valueToTree(testCase.getValue()));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_application WHERE business_no=?", Integer.class, business)).isZero();
        }
    }

    @Test
    void definitionSchemaIsSharedWithRevisionAndCannotBeRemovedByOldClients() throws Exception {
        JsonNode draft = definition("revision-" + UUID.randomUUID(), allTypes(), graph());
        String path = "/api/v1/process-definitions/" + draft.path("id").asText();
        JsonNode oldClientUpdated = ok(send("PUT", path, "admin", Map.of("name", "仅更新图", "graph", graph(), "expectedRevision", 0)), 200);
        assertThat(oldClientUpdated.path("formSchema")).isEqualTo(draft.path("formSchema"));
        Map<String, Object> update = new LinkedHashMap<>(Map.of("name", "显式 null", "graph", graph(), "expectedRevision", 1));
        update.put("formSchema", null);
        assertThat(ok(send("PUT", path, "admin", update), 200).path("formSchema")).isEqualTo(draft.path("formSchema"));
        Map<String, Object> empty = Map.of("schemaVersion", 1, "fields", List.of());
        assertThat(ok(send("PUT", path, "admin", Map.of("name", "无字段表单", "graph", graph(), "expectedRevision", 2, "formSchema", empty)), 200)
                .path("formSchema")).isEqualTo(mapper.valueToTree(empty));
        assertThat(send("PUT", path, "admin", Map.of("name", "过期变更", "graph", graph(), "expectedRevision", 1, "formSchema", allTypes())).getStatus()).isEqualTo(409);
        ok(send("POST", path + "/publish?expectedRevision=3", "admin", Map.of("changeNote", "测试表单版本发布")), 200);
        assertThat(send("PUT", path, "admin", Map.of("name", "覆盖发布", "graph", graph(), "expectedRevision", 4, "formSchema", allTypes())).getStatus()).isEqualTo(422);
    }

    @Test
    void publishesNewSchemaWithoutRebindingExistingApplicationOrHistoricalRounds() throws Exception {
        String key = "versions-" + UUID.randomUUID();
        JsonNode first = published(key, allTypes(), graph());
        JsonNode draft = create(first, validPayload());
        String id = draft.path("id").asText();
        ok(send("POST", application(id, "submit"), "alice", Map.of("expectedVersion", 1)), 200);
        JsonNode originalRound = rounds(id).get(0);
        JsonNode returned = decide(id, "RETURN", 2);
        Map<String, Object> secondSchema = Map.of("schemaVersion", 1, "fields", List.of(field("newReason", "新版本理由", "TEXT", true)));
        JsonNode second = published(key, secondSchema, graph());
        assertThat(second.path("version").asInt()).isEqualTo(2);
        Map<String, Object> revision = new LinkedHashMap<>(validPayload());
        revision.put("reason", "补正原表单"); revision.put("note", null);
        JsonNode revised = ok(send("PUT", "/api/v1/applications/" + id, "alice", Map.of("expectedVersion", returned.path("version").asLong(),
                "title", "补正内容", "payload", revision)), 200);
        assertThat(revised.path("formSchema")).isEqualTo(first.path("formSchema"));
        JsonNode resubmitted = ok(send("POST", application(id, "submit"), "alice", Map.of("expectedVersion", revised.path("version").asLong())), 200);
        assertThat(resubmitted.path("definitionVersion").asInt()).isEqualTo(1);
        assertThat(rounds(id).get(0).path("formSchema")).isEqualTo(originalRound.path("formSchema"));
        assertThat(rounds(id).get(0).path("payload")).isEqualTo(originalRound.path("payload"));
        assertThat(rounds(id).get(0).path("status").asText()).isEqualTo("RETURNED");
        assertThat(rounds(id).get(1).path("formSchema")).isEqualTo(first.path("formSchema"));
        assertThat(rounds(id).get(1).path("payload").path("note").isNull()).isTrue();
        assertThat(create(second, Map.of("newReason", "新表单")).path("formSchema")).isEqualTo(second.path("formSchema"));
    }

    @Test
    void simulationAndFlowableAgreeOnTypedEnumNumbersBooleanAndDate() throws Exception {
        List<String> conditions = List.of("choice == '1'", "amount > 9007199254740992", "confirmed == false", "date >= 2028-02-29");
        for (String condition : conditions) {
            JsonNode definition = published("route-" + UUID.randomUUID(), allTypes(), conditionalGraph(condition));
            JsonNode simulated = ok(send("POST", "/api/v1/process-definitions/" + definition.path("id").asText() + "/simulate", "admin",
                    Map.of("values", validPayload())), 200);
            String expected = condition.startsWith("choice") ? "fallback" : "selected";
            assertThat(simulated.path("path").toString()).contains(expected);
            JsonNode draft = create(definition, validPayload());
            String id = draft.path("id").asText();
            ok(send("POST", application(id, "submit"), "alice", Map.of("expectedVersion", 1)), 200);
            var task = tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult();
            assertThat(task.getTaskDefinitionKey()).isEqualTo(expected);
            Map<?, ?> types = (Map<?, ?>) runtime.getVariable(task.getProcessInstanceId(), "formFieldTypes");
            assertThat(types.get("choice")).isEqualTo("SELECT");
            assertThat(types.get("amount")).isEqualTo("NUMBER");
        }
    }

    @Test
    void rejectsUndeclaredConditionsAndInvalidLiteralsBeforePublishing() throws Exception {
        for (String condition : List.of("tenantId == 'demo'", "unknown EXISTS", "confirmed == 0", "reason > 'a'", "amount == 1e3", "choice == 'unknown'")) {
            Map<String, Object> body = Map.of("key", "invalid-rule-" + UUID.randomUUID(), "name", "错误条件", "graph", conditionalGraph(condition), "formSchema", allTypes());
            var response = send("POST", "/api/v1/process-definitions", "admin", body);
            assertThat(response.getStatus()).isEqualTo(422);
            assertThat(tree(response).path("code").asText()).isEqualTo("INVALID_DEFINITION");
            JsonNode validation = ok(send("POST", "/api/v1/process-definitions/validate", "admin", Map.of("graph", conditionalGraph(condition), "formSchema", allTypes())), 200);
            assertThat(validation.path("errors").toString()).contains("INVALID_CONDITION");
        }
    }

    @Test
    void rejectsUnknownSchemaPropertiesInsteadOfDiscardingConstraints() throws Exception {
        Map<String, Object> unknownRoot = new LinkedHashMap<>(allTypes());
        unknownRoot.put("readOnly", true);
        Map<String, Object> unknownField = new LinkedHashMap<>(field("amount", "金额", "NUMBER", true));
        unknownField.put("maximun", "100");
        Map<String, Object> unknownOption = Map.of("value", "a", "label", "选项", "disabled", true);
        for (Object schema : List.of(unknownRoot,
                Map.of("schemaVersion", 1, "fields", List.of(unknownField)),
                Map.of("schemaVersion", 1, "fields", List.of(Map.of("key", "choice", "label", "选项", "type", "SELECT", "required", true, "options", List.of(unknownOption)))))) {
            var response = send("POST", "/api/v1/process-definitions", "admin", Map.of("key", "unknown-schema-" + UUID.randomUUID(),
                    "name", "不能静默丢失约束", "graph", graph(), "formSchema", schema));
            assertThat(response.getStatus()).isEqualTo(422);
            assertThat(tree(response).path("code").asText()).isEqualTo("INVALID_FORM_SCHEMA");
        }
    }

    @Test
    void refusesJsonCoercionAndMalformedSchemasWithStableErrors() throws Exception {
        List<Object> schemas = List.of(
                Map.of("schemaVersion", 3, "fields", List.of()),
                Map.of("schemaVersion", 1, "fields", List.of(field("tenantId", "系统字段", "TEXT", true))),
                Map.of("schemaVersion", 1, "fields", List.of(Map.of("key", "n", "label", "数字", "type", "NUMBER", "required", true, "minimum", 0))),
                Map.of("schemaVersion", 1, "fields", List.of(Map.of("key", "n", "label", "数字", "type", "NUMBER", "required", "false"))),
                Map.of("schemaVersion", 1, "fields", List.of(field("n", "错误类型", "SCRIPT", false))),
                Map.of("schemaVersion", 1, "fields", List.of(field("a", "甲", "TEXT", true), field("a", "乙", "TEXT", true))));
        for (Object schema : schemas) {
            var response = send("POST", "/api/v1/process-definitions", "admin", Map.of("key", "schema-" + UUID.randomUUID(), "name", "错误表单", "graph", graph(), "formSchema", schema));
            assertThat(response.getStatus()).isEqualTo(422);
            assertThat(tree(response).path("code").asText()).isEqualTo("INVALID_FORM_SCHEMA");
        }
    }

    @Test
    void onlyPublishedTenantDefinitionsOrExactBundledLegacyVersionCanCreateApplications() throws Exception {
        JsonNode draft = definition("draft-only-" + UUID.randomUUID(), allTypes(), graph());
        assertThat(send("POST", "/api/v1/applications", "alice", createBody(draft, "DRAFT-" + UUID.randomUUID(), Map.of())).getStatus()).isEqualTo(422);
        String foreignKey = "foreign-" + UUID.randomUUID();
        // 本用例验证跨租户资源隔离，显式提供另一租户已配置的审批身份目录。
        org.mockito.Mockito.doReturn(new AuthService(true, "foreign-tenant").options("foreign-tenant"))
                .when(auth).options("foreign-tenant");
        var foreign = definitions.create("foreign-tenant", foreignKey, "其他租户流程", domainGraph());
        definitions.publish(new io.agentflow.common.Actor("foreign-tenant", "test-admin", java.util.Set.of("ADMIN")), foreign.id(), 0, "集成测试发布");
        for (Map<String, Object> target : List.of(Map.<String, Object>of("processKey", foreignKey, "definitionVersion", 1),
                Map.<String, Object>of("processKey", "missing-" + UUID.randomUUID(), "definitionVersion", 1),
                Map.<String, Object>of("processKey", "expense-reimbursement", "definitionVersion", 2))) {
            Map<String, Object> body = new LinkedHashMap<>(target);
            body.putAll(Map.of("businessNo", "DENIED-" + UUID.randomUUID(), "title", "不可绑定", "payload", Map.of()));
            assertThat(send("POST", "/api/v1/applications", "alice", body).getStatus()).isEqualTo(422);
        }
        var legacy = ok(send("POST", "/api/v1/applications", "alice", Map.of("businessNo", "LEGACY-" + UUID.randomUUID(), "processKey", "expense-reimbursement",
                "definitionVersion", 1, "title", "内置旧表单", "payload", Map.of("amount", 10))), 201);
        assertThat(legacy.has("formSchema")).isTrue(); assertThat(legacy.path("formSchema").isNull()).isTrue();
    }

    @Test
    void ignoresClientSuppliedApplicationSchemaAndReplaysBoundSchemaWithoutChangingDraft() throws Exception {
        JsonNode definition = published("tamper-" + UUID.randomUUID(), allTypes(), graph());
        Map<String, Object> body = new LinkedHashMap<>(createBody(definition, "REPLAY-" + UUID.randomUUID(), Map.of()));
        body.put("formSchema", Map.of("schemaVersion", 1, "fields", List.of()));
        String key = UUID.randomUUID().toString();
        var first = send("POST", "/api/v1/applications", "alice", body, key);
        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(tree(first).path("formSchema")).isEqualTo(definition.path("formSchema"));
        var replay = send("POST", "/api/v1/applications", "alice", body, key);
        assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
        String id = tree(first).path("id").asText();
        assertThat(send("POST", application(id, "submit"), "alice", Map.of("expectedVersion", 1)).getStatus()).isEqualTo(422);
    }

    @Test
    void legacyApplicationStillRevisesAndSubmitsAfterSchemaVersionWasPublished() throws Exception {
        String key = "legacy-existing-" + UUID.randomUUID();
        JsonNode original = published(key, null, graph());
        JsonNode draft = create(original, Map.of("oldField", Map.of("nested", 1)));
        published(key, allTypes(), graph());
        String id = draft.path("id").asText();
        Map<String, Object> payload = new LinkedHashMap<>(); payload.put("oldField", null);
        JsonNode revised = ok(send("PUT", "/api/v1/applications/" + id, "alice", Map.of("title", "保留旧表单", "expectedVersion", 1, "payload", payload)), 200);
        assertThat(revised.path("formSchema").isNull()).isTrue();
        ok(send("POST", application(id, "submit"), "alice", Map.of("expectedVersion", 2)), 200);
        assertThat(rounds(id).get(0).path("formSchema").isNull()).isTrue();
        assertThat(rounds(id).get(0).path("payload").path("oldField").isNull()).isTrue();
    }

    private Map<String, Object> allTypes() {
        return Map.of("schemaVersion", 1, "fields", List.of(
                Map.of("key", "reason", "label", "申请理由", "type", "TEXT", "required", true, "maxLength", 30),
                field("note", "备注", "TEXTAREA", false),
                Map.of("key", "amount", "label", "金额", "type", "NUMBER", "required", true, "minimum", "0", "maximum", "99999999999999999999.99"),
                field("date", "日期", "DATE", true),
                Map.of("key", "choice", "label", "选项", "type", "SELECT", "required", true, "options", List.of(Map.of("value", "01", "label", "第一项"), Map.of("value", "1", "label", "第二项"))),
                field("confirmed", "确认", "BOOLEAN", true)));
    }

    private Map<String, Object> field(String key, String label, String type, boolean required) {
        return Map.of("key", key, "label", label, "type", type, "required", required);
    }

    private Map<String, Object> validPayload() {
        return Map.of("reason", "申请理由", "note", "备注", "amount", "0009007199254740993.00", "date", "2028-02-29", "choice", "01", "confirmed", false);
    }

    private JsonNode definition(String key, Map<String, Object> schema, Map<String, Object> graph) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("key", key, "name", "动态表单", "graph", graph));
        body.put("formSchema", schema);
        return ok(send("POST", "/api/v1/process-definitions", "admin", body), 200);
    }

    private JsonNode published(String key, Map<String, Object> schema, Map<String, Object> graph) throws Exception {
        JsonNode draft = definition(key, schema, graph);
        return ok(send("POST", "/api/v1/process-definitions/" + draft.path("id").asText() + "/publish?expectedRevision=0", "admin", Map.of("changeNote", "测试表单版本发布")), 200);
    }

    private JsonNode create(JsonNode definition, Map<String, Object> payload) throws Exception {
        return ok(send("POST", "/api/v1/applications", "alice", createBody(definition, "FORM-" + UUID.randomUUID(), payload)), 201);
    }

    private Map<String, Object> createBody(JsonNode definition, String business, Map<String, Object> payload) {
        return Map.of("businessNo", business, "processKey", definition.path("key").asText(), "definitionVersion", definition.path("version").asLong(),
                "title", "表单申请", "payload", payload);
    }

    private JsonNode decide(String id, String action, long version) throws Exception {
        var task = tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult();
        return ok(send("POST", "/api/v1/tasks/" + task.getId() + "/actions", "manager", Map.of("action", action, "expectedVersion", version, "comment", "补正")), 200);
    }

    private JsonNode getApplication(String id) throws Exception {
        return ok(send("GET", "/api/v1/applications/" + id, "alice", null), 200);
    }

    private JsonNode rounds(String id) throws Exception {
        return ok(send("GET", "/api/v1/applications/" + id + "/rounds", "alice", null), 200);
    }

    private String application(String id, String operation) { return "/api/v1/applications/" + id + "/" + operation; }

    private MockHttpServletResponse send(String method, String path, String user, Object body) throws Exception {
        return send(method, path, user, body, UUID.randomUUID().toString());
    }

    private MockHttpServletResponse send(String method, String path, String user, Object body, String key) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path)
                .header("Authorization", token(user)).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(body == null ? "" : mapper.writeValueAsString(body))).andReturn().getResponse();
    }

    private JsonNode tree(MockHttpServletResponse response) throws Exception { return mapper.readTree(response.getContentAsString()); }

    private JsonNode ok(MockHttpServletResponse response, int expectedStatus) throws Exception {
        assertThat(response.getStatus()).withFailMessage("Unexpected HTTP %s: %s", response.getStatus(), response.getContentAsString()).isEqualTo(expectedStatus);
        return tree(response);
    }

    private DefinitionModels.Graph domainGraph() {
        return new DefinitionModels.Graph(List.of(new DefinitionModels.Node("start", "开始", DefinitionModels.NodeType.START, Map.of()),
                new DefinitionModels.Node("approve", "审批", DefinitionModels.NodeType.USER_TASK, Map.of("assigneeRule", "user:manager")),
                new DefinitionModels.Node("end", "结束", DefinitionModels.NodeType.END, Map.of())),
                List.of(new DefinitionModels.Edge("a", "start", "approve", ""), new DefinitionModels.Edge("b", "approve", "end", "")));
    }

    private Map<String, Object> conditionalGraph(String condition) {
        return Map.of("nodes", List.of(Map.of("id", "start", "name", "开始", "type", "START"),
                        Map.of("id", "gate", "name", "条件", "type", "EXCLUSIVE_GATEWAY"),
                        Map.of("id", "selected", "name", "命中", "type", "USER_TASK", "properties", Map.of("assigneeRule", "user:manager")),
                        Map.of("id", "fallback", "name", "默认", "type", "USER_TASK", "properties", Map.of("assigneeRule", "user:manager")),
                        Map.of("id", "end", "name", "结束", "type", "END")),
                "edges", List.of(Map.of("id", "a", "source", "start", "target", "gate"),
                        Map.of("id", "b", "source", "gate", "target", "selected", "condition", condition),
                        Map.of("id", "c", "source", "gate", "target", "fallback", "defaultBranch", true),
                        Map.of("id", "d", "source", "selected", "target", "end"), Map.of("id", "e", "source", "fallback", "target", "end")));
    }

    private Map<String, Object> graph() {
        return Map.of("nodes", List.of(Map.of("id", "start", "name", "开始", "type", "START"),
                        Map.of("id", "approve", "name", "审批", "type", "USER_TASK", "properties", Map.of("assigneeRule", "user:manager")),
                        Map.of("id", "end", "name", "结束", "type", "END")),
                "edges", List.of(Map.of("id", "a", "source", "start", "target", "approve"),
                        Map.of("id", "b", "source", "approve", "target", "end")));
    }

    private String token(String user) {
        return "Bearer " + auth.login("demo", user, "demo").token();
    }
}
