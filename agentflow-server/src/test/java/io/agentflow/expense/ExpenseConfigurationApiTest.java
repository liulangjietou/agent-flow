package io.agentflow.expense;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 实际认证、JSON、幂等事务和 JDBC 验证配置历史，兼容使用真实 PostgreSQL 运行同一用例。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "spring.datasource.url=jdbc:h2:mem:expense-configuration-api;DB_CLOSE_DELAY=-1",
        "agentflow.advances.overdue.reminders-enabled=false", "agentflow.timers.enabled=false",
        "agentflow.attachments.directory=/fyoung/tmp/agentflow-expense-configuration-api-test-files"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ExpenseConfigurationApiTest {
    private static final String CATEGORIES = "/api/v1/admin/expense-categories";
    private static final String POLICIES = "/api/v1/admin/expense-policies";
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExpenseConfigurationService service;
    private String admin;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_EXPENSE_CONFIG_TEST_URL", "jdbc:h2:mem:expense-configuration-api;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_EXPENSE_CONFIG_TEST_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_EXPENSE_CONFIG_TEST_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_EXPENSE_CONFIG_TEST_PASSWORD", ""));
    }

    @BeforeEach void resetConfigurationOnly() {
        jdbc.update("UPDATE expense_configuration SET active_revision=0,active_policy_id=NULL,active_policy_version=NULL");
        for (var table : List.of("expense_policy_activation", "expense_policy_version", "expense_policy_draft_revision", "expense_policy_draft", "expense_category_revision", "expense_configuration")) jdbc.update("DELETE FROM " + table);
        admin = token("admin");
    }

    @Test void fixedAllowanceCanBeSavedAndPublishedThroughTheStrictAdminJsonBoundary() throws Exception {
        write(put(CATEGORIES), admin, Map.of("expectedVersion", 0, "comment", "合成补贴类别", "categories",
                List.of(Map.of("code", "ALLOWANCE", "name", "补贴", "units", List.of("DAY"), "active", true)))).andExpect(status().isOk());
        write(put(POLICIES + "/allowance/draft"), admin, allowanceInput()).andExpect(status().isOk())
                .andExpect(jsonPath("definition.rules[0].constraints.fixedAllowance.dailyRate.value").value("100.00"));
        var publication = publish("allowance", 1, 1, 0);
        assertThat(publication.at("/activePolicy/definition/rules/0/constraints/fixedAllowance/dayCountBasis").asText()).isEqualTo("CALENDAR_DAYS_INCLUSIVE");
        assertThat(read(POLICIES + "/allowance/versions/1").at("/definition/rules/0/constraints/fixedAllowance/dailyRate/value").asText()).isEqualTo("100.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "basis-type", "amount-type", "missing-basis"})
    void fixedAllowanceJsonKeepsUnknownFieldAndScalarTypeGuards(String defect) throws Exception {
        var input = json.read(json.write(allowanceInput()), com.fasterxml.jackson.databind.node.ObjectNode.class);
        var fixed = (com.fasterxml.jackson.databind.node.ObjectNode) input.at("/definition/rules/0/constraints/fixedAllowance");
        switch (defect) {
            case "unknown" -> fixed.put("approved", true);
            case "basis-type" -> fixed.put("dayCountBasis", true);
            case "amount-type" -> ((com.fasterxml.jackson.databind.node.ObjectNode) fixed.get("dailyRate")).put("value", 100);
            case "missing-basis" -> fixed.remove("dayCountBasis");
            default -> throw new IllegalArgumentException("Unknown synthetic defect");
        }
        var response = write(put(POLICIES + "/allowance/draft"), admin, input);
        if (defect.equals("amount-type")) {
            response.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("code").value("INVALID_MONEY"));
        } else {
            response.andExpect(status().isBadRequest());
        }
        assertThat(count("expense_policy_draft")).isZero();
    }

    private Map<String, Object> allowanceInput() {
        return Map.of("expectedRevision", 0, "comment", "合成补贴规则", "definition", Map.of("name", "自然日补贴", "rules", List.of(
                Map.of("key", "daily", "name", "每日补贴", "match", Map.of("legalEntityIds", List.of(), "categoryCodes", List.of("ALLOWANCE"),
                        "cityTiers", List.of(), "employeeGrades", List.of(), "currency", "CNY"),
                        "constraints", Map.of("effect", "ALLOW", "allowedServiceLevels", List.of(), "priorRequestRequired", false,
                                "fixedAllowance", Map.of("dailyRate", Map.of("value", "100.00", "currency", "CNY"), "dayCountBasis", "CALENDAR_DAYS_INCLUSIVE"))))));
    }

    @Test void categoryHistoryRetainsDisabledIdentityAndDoesNotRewriteBusinessRecords() throws Exception {
        var applications = jdbc.queryForList("SELECT * FROM approval_application ORDER BY id");
        var audits = jdbc.queryForList("SELECT * FROM audit_event ORDER BY id");
        var first = saveCategories(0, "住宿", true);
        var second = saveCategories(1, "酒店住宿", false);
        assertThat(first.path("version").asLong()).isEqualTo(1);
        assertThat(second.path("version").asLong()).isEqualTo(2);
        assertThat(read(CATEGORIES + "/versions/1").path("catalog")).isEqualTo(first);
        assertThat(read(CATEGORIES + "/versions/2").path("updatedBy").asText()).isEqualTo("admin");
        assertThat(read(CATEGORIES + "/versions?limit=1").path("nextBeforeVersion").asLong()).isEqualTo(2);
        var tail = read(CATEGORIES + "/versions?limit=1&beforeVersion=2");
        assertThat(tail.path("items").get(0).path("version").asInt()).isEqualTo(1);
        assertThat(tail.path("nextBeforeVersion").isNull()).isTrue();
        write(put(CATEGORIES), admin, Map.of("expectedVersion", 2, "categories", List.of(), "comment", "删除历史代码"))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("EXPENSE_CATEGORY_REMOVAL_FORBIDDEN"));
        assertThat(read(CATEGORIES)).isEqualTo(second);
        assertThat(jdbc.queryForList("SELECT * FROM approval_application ORDER BY id")).isEqualTo(applications);
        assertThat(jdbc.queryForList("SELECT * FROM audit_event ORDER BY id")).isEqualTo(audits);
    }

    @Test void publishedVersionsRemainImmutableAndUnpublishedChangesStaySeparate() throws Exception {
        saveCategories(0, "住宿", true); saveDraft("travel", 0, "制度一");
        var published = publish("travel", 1, 1, 0);
        var first = read(POLICIES + "/travel/versions/1");
        assertThat(first.path("publishedBy").asText()).isEqualTo("admin");
        assertThat(first.path("categoryRevision").asLong()).isEqualTo(1);
        saveDraft("travel", 1, "制度二");
        assertThat(read(POLICIES + "/current")).isEqualTo(published);
        assertThat(read(POLICIES + "/travel/versions/1")).isEqualTo(first);
        assertThat(read(POLICIES + "/travel/draft/versions/1").path("definition").path("name").asText()).isEqualTo("制度一");
        assertThat(read(POLICIES + "/travel/draft/versions/2").path("definition").path("name").asText()).isEqualTo("制度二");
        var second = publish("travel", 2, 1, 1);
        assertThat(second.path("activeRevision").asInt()).isEqualTo(2);
        assertThat(second.path("activePolicy").path("version").asInt()).isEqualTo(2);
        var versions = read(POLICIES + "/travel/versions?limit=1");
        assertThat(versions.path("nextBeforeVersion").asInt()).isEqualTo(2);
        assertThat(read(POLICIES + "/travel/versions?limit=1&beforeVersion=2").path("items").get(0).path("version").asInt()).isEqualTo(1);
        assertThat(read(POLICIES + "/activations?limit=1").path("items").get(0).path("policyVersion").asInt()).isEqualTo(2);
    }

    @Test void publishedDefinitionPreservesEveryConfiguredDimension() throws Exception {
        saveCategories(0, "住宿", true);
        var definition = new ExpensePolicyDefinition("完整合成制度", List.of(new ExpensePolicyDefinition.Rule("hotel-rule", "合成规则",
                new ExpensePolicyDefinition.Match(List.of(UUID.randomUUID()), List.of("hotel"), List.of("tier-1"), List.of("grade-a"),
                        java.time.LocalDate.of(2026, 1, 1), java.time.LocalDate.of(2026, 12, 31), "CNY"),
                new ExpensePolicyDefinition.Constraints(ExpensePolicyDefinition.Effect.ALLOW,
                        new io.agentflow.finance.Money(new java.math.BigDecimal("123.45"), "CNY"), ExpenseLine.Unit.NIGHT, 90,
                        ExpensePolicyDefinition.AgeAction.REQUIRE_REASON, List.of("economy"), true))));
        write(put(POLICIES + "/complete/draft"), admin, Map.of("expectedRevision", 0, "definition", definition, "comment", "核对全部配置维度"))
                .andExpect(status().isOk());
        var published = publish("complete", 1, 1, 0);
        assertThat(published.path("activePolicy").path("definition")).isEqualTo(json.read(json.write(definition), JsonNode.class));
        assertThat(service.version("demo", "complete", 1).definition()).isEqualTo(definition);
    }

    @Test void publicationChecksAllThreeVersionsAndCannotPublishSameDraftAgain() throws Exception {
        saveCategories(0, "住宿", true); saveDraft("travel", 0, "制度");
        for (var versions : List.of(List.of(2, 1, 0), List.of(1, 2, 0), List.of(1, 1, 1))) {
            write(post(POLICIES + "/travel/publish"), admin, publication(versions.get(0), versions.get(1), versions.get(2)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("code").value("CONCURRENCY_CONFLICT"));
        }
        assertThat(count("expense_policy_version")).isZero(); assertThat(count("expense_policy_activation")).isZero();
        publish("travel", 1, 1, 0);
        write(post(POLICIES + "/travel/publish"), admin, publication(1, 1, 1))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("EXPENSE_CONFIGURATION_UNCHANGED"));
        assertThat(count("expense_policy_version")).isEqualTo(1);
    }

    @Test void emptyRulesAndInactiveCategoriesCannotBePublished() throws Exception {
        saveCategories(0, "住宿", true);
        write(put(POLICIES + "/empty/draft"), admin, Map.of("expectedRevision", 0, "definition", Map.of("name", "待补全", "rules", List.of()), "comment", "先保存"))
                .andExpect(status().isOk());
        write(post(POLICIES + "/empty/publish"), admin, publication(1, 1, 0))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("EXPENSE_POLICY_INCOMPLETE"));
        saveDraft("travel", 0, "制度"); saveCategories(1, "住宿", false);
        write(post(POLICIES + "/travel/publish"), admin, publication(1, 2, 0))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("EXPENSE_POLICY_CATEGORY_UNAVAILABLE"));
        assertThat(count("expense_policy_version")).isZero();
    }

    @Test void originalIdempotencyKeyReplaysExactPublicationAfterNewDraftExists() throws Exception {
        saveCategories(0, "住宿", true); saveDraft("travel", 0, "制度一");
        String key = UUID.randomUUID().toString(); var body = publication(1, 1, 0);
        String first = write(post(POLICIES + "/travel/publish"), admin, key, json.write(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        saveDraft("travel", 1, "制度二");
        String replay = write(post(POLICIES + "/travel/publish"), admin, key, json.write(body)).andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true")).andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        assertThat(count("expense_policy_version")).isEqualTo(1); assertThat(count("expense_policy_activation")).isEqualTo(1);
        write(post(POLICIES + "/travel/publish"), admin, key, json.write(publication(2, 1, 1))).andExpect(status().isConflict());
    }

    @Test void historyFailureRollsBackDraftPointerActivationAndSuccessfulReceipt() throws Exception {
        saveCategories(0, "住宿", true); saveDraft("travel", 0, "制度");
        var before = read(POLICIES + "/travel/draft"); var key = UUID.randomUUID().toString(); var payload = json.write(publication(1, 1, 0));
        jdbc.execute("ALTER TABLE expense_policy_activation ADD CONSTRAINT test_publication_failure CHECK (revision < 1)");
        try {
            assertThatThrownBy(() -> write(post(POLICIES + "/travel/publish"), admin, key, payload)).hasRootCauseInstanceOf(java.sql.SQLException.class);
        } finally { jdbc.execute("ALTER TABLE expense_policy_activation DROP CONSTRAINT test_publication_failure"); }
        assertThat(read(POLICIES + "/travel/draft")).isEqualTo(before);
        assertThat(read(POLICIES + "/current").path("activeRevision").asInt()).isZero();
        assertThat(count("expense_policy_version")).isZero(); assertThat(count("expense_policy_activation")).isZero();
        write(post(POLICIES + "/travel/publish"), admin, key, payload).andExpect(status().isOk()).andExpect(header().string("Idempotency-Replayed", "false"));
        assertThat(count("expense_policy_version")).isEqualTo(1);
    }

    @Test void distinctPoliciesCannotOverwriteTheSameActiveRevisionConcurrently() throws Exception {
        saveCategories(0, "住宿", true); saveDraft("first", 0, "制度一"); saveDraft("second", 0, "制度二");
        var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (var key : List.of("first", "second")) futures.add(pool.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Concurrent publication did not start");
                return write(post(POLICIES + "/" + key + "/publish"), admin, publication(1, 1, 0)).andReturn().getResponse().getStatus();
            }));
            start.countDown();
            assertThat(List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
            assertThat(count("expense_policy_version")).isEqualTo(1); assertThat(count("expense_policy_activation")).isEqualTo(1);
            assertThat(read(POLICIES + "/current").path("activeRevision").asInt()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void financialAndSystemRolesDoNotImplicitlyGrantConfigurationOrReplayAccess() throws Exception {
        String key = UUID.randomUUID().toString(); String payload = json.write(categoryBody(0, "住宿", true));
        write(put(CATEGORIES), admin, key, payload).andExpect(status().isOk());
        mvc.perform(get(CATEGORIES)).andExpect(status().isUnauthorized());
        for (var identity : List.of(token("finance"), token("alice"), restrictedAdmin())) {
            for (var path : List.of(CATEGORIES, CATEGORIES + "/versions/1", POLICIES, POLICIES + "/current", POLICIES + "/activations")) {
                mvc.perform(get(path).header("Authorization", identity)).andExpect(status().isForbidden());
            }
            write(put(CATEGORIES), identity, key, payload).andExpect(status().isForbidden());
        }
    }

    @Test void foreignTenantHistoryAndDraftsCannotBeReadOrEnumerated() throws Exception {
        var foreign = new Actor("configuration-other", "finance-admin", Set.of(ExpenseConfigurationController.CONFIGURATION_ROLE));
        var category = new ExpenseCategoryCatalog.Category("hotel", "其他企业住宿", List.of(ExpenseLine.Unit.NIGHT), true);
        service.saveCategories(foreign, 0, List.of(category), "企业配置");
        service.saveDraft(foreign, "foreign", 0, definition("其他企业"), "企业配置");
        service.publish(foreign, "foreign", 1, 1, 0, "企业发布");
        for (var path : List.of(POLICIES + "/foreign/draft", POLICIES + "/foreign/versions", POLICIES + "/foreign/versions/1", CATEGORIES + "/versions/1")) {
            mvc.perform(get(path).header("Authorization", admin)).andExpect(status().isNotFound());
        }
        assertThat(read(POLICIES).path("items").isEmpty()).isTrue();
        assertThat(read(POLICIES + "/activations").path("items").isEmpty()).isTrue();
        assertThat(read(CATEGORIES).path("version").asInt()).isZero();
        write(put(POLICIES + "/foreign/draft"), admin, Map.of("expectedRevision", 1, "definition", definition("越权"), "comment", "越权"))
                .andExpect(status().isNotFound());
    }

    @Test void stableDirectoryPaginationDoesNotExposeCompleteRuleBodies() throws Exception {
        for (var key : List.of("one", "two", "three")) saveDraft(key, 0, key);
        var seen = new HashSet<String>(); String path = POLICIES + "?limit=2";
        while (true) {
            var page = read(path);
            for (var item : page.path("items")) { assertThat(seen.add(item.path("key").asText())).isTrue(); assertThat(item.has("definition")).isFalse(); }
            if (page.path("nextAfterKey").isNull()) break;
            path = POLICIES + "?limit=2&afterKey=" + page.path("nextAfterKey").asText();
        }
        assertThat(seen).containsExactlyInAnyOrder("one", "two", "three");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.9", "\"0\"", "true", "null", "-1"})
    void malformedCategoryRevisionFailsBeforeAnyWrite(String revision) throws Exception {
        var input = "{\"expectedVersion\":" + revision + ",\"categories\":[],\"comment\":\"配置\"}";
        write(put(CATEGORIES), admin, UUID.randomUUID().toString(), input).andExpect(status().isBadRequest());
        assertThat(count("expense_category_revision")).isZero();
    }

    @Test void unknownAndCoercedNestedValuesAreRejectedBeforeSaving() throws Exception {
        var valid = json.write(Map.of("expectedRevision", 0, "definition", definition("制度"), "comment", "配置"));
        for (var input : List.of(valid.replace("\"effect\":\"ALLOW\"", "\"effect\":\"ALLOW\",\"script\":\"x\""),
                valid.replace("\"priorRequestRequired\":false", "\"priorRequestRequired\":\"false\""),
                valid.replace("\"name\":\"制度\"", "\"name\":12"), valid.replace("\"categoryCodes\":[\"hotel\"]", "\"categoryCodes\":[42]"),
                valid.replace("\"rules\":[", "\"unknownRuleMode\":true,\"rules\":["))) {
            assertThat(input).isNotEqualTo(valid);
            write(put(POLICIES + "/invalid/draft"), admin, UUID.randomUUID().toString(), input).andExpect(status().isBadRequest());
        }
        assertThat(count("expense_policy_draft")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenantId=other", "limit=0", "limit=101", "limit=2&limit=3", "afterKey=", "afterKey=UPPER"})
    void invalidOrDuplicatedDirectoryQueryFails(String query) throws Exception {
        mvc.perform(get(POLICIES + "?" + query).header("Authorization", admin)).andExpect(status().isBadRequest());
    }

    @Test void writesCannotSilentlyAcceptTenantOverridesOrUnknownFields() throws Exception {
        write(put(CATEGORIES + "?tenantId=other"), admin, categoryBody(0, "住宿", true)).andExpect(status().isBadRequest());
        var input = json.write(categoryBody(0, "住宿", true)).replace("\"active\":true", "\"active\":true,\"deleted\":false");
        write(put(CATEGORIES), admin, UUID.randomUUID().toString(), input).andExpect(status().isBadRequest());
        assertThat(count("expense_category_revision")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "trailing"})
    void configurationWriteRejectsAmbiguousJsonDocuments(String kind) throws Exception {
        var valid = json.write(categoryBody(0, "住宿", true));
        var input = kind.equals("duplicate") ? valid.replace("\"expectedVersion\":0", "\"expectedVersion\":0,\"expectedVersion\":0") : valid + "\n{}";
        assertThat(input).isNotEqualTo(valid);
        write(put(CATEGORIES), admin, UUID.randomUUID().toString(), input).andExpect(status().isBadRequest());
        assertThat(count("expense_category_revision")).isZero();
    }

    private JsonNode saveCategories(long expected, String name, boolean active) throws Exception { return body(write(put(CATEGORIES), admin, categoryBody(expected, name, active)).andExpect(status().isOk())); }
    private JsonNode saveDraft(String key, long expected, String name) throws Exception { return body(write(put(POLICIES + "/" + key + "/draft"), admin, Map.of("expectedRevision", expected, "definition", definition(name), "comment", "保存制度草稿")).andExpect(status().isOk())); }
    private JsonNode publish(String key, int draft, int categories, int active) throws Exception { return body(write(post(POLICIES + "/" + key + "/publish"), admin, publication(draft, categories, active)).andExpect(status().isOk())); }
    private JsonNode read(String path) throws Exception { return body(mvc.perform(get(path).header("Authorization", admin)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))); }
    private JsonNode body(ResultActions result) throws Exception { return json.read(result.andReturn().getResponse().getContentAsString(), JsonNode.class); }
    private ResultActions write(MockHttpServletRequestBuilder request, String identity, Object value) throws Exception { return write(request, identity, UUID.randomUUID().toString(), json.write(value)); }
    private ResultActions write(MockHttpServletRequestBuilder request, String identity, String key, String value) throws Exception { return mvc.perform(request.header("Authorization", identity).header("Idempotency-Key", key).contentType("application/json").content(value)); }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    @SuppressWarnings("unchecked")
    private String restrictedAdmin() {
        var login = auth.login("demo", "admin", "demo");
        var tokens = (Map<String, Actor>) ReflectionTestUtils.getField(auth, "tokens");
        tokens.put(login.token(), new Actor("demo", "admin", Set.of("ADMIN", "FINANCE")));
        return "Bearer " + login.token();
    }
    private static Map<String, Object> categoryBody(long expected, String name, boolean active) { return Map.of("expectedVersion", expected, "categories", List.of(Map.of("code", "hotel", "name", name, "units", List.of("NIGHT"), "active", active)), "comment", "维护住宿类别"); }
    private static Map<String, Object> publication(int draft, int categories, int active) { return Map.of("expectedDraftRevision", draft, "expectedCategoryRevision", categories, "expectedActiveRevision", active, "comment", "核对后发布并生效"); }
    private static ExpensePolicyDefinition definition(String name) { return new ExpensePolicyDefinition(name, List.of(new ExpensePolicyDefinition.Rule("hotel-rule", "住宿规则",
            new ExpensePolicyDefinition.Match(List.of(), List.of("hotel"), List.of(), List.of(), null, null, "CNY"),
            new ExpensePolicyDefinition.Constraints(ExpensePolicyDefinition.Effect.ALLOW, null, null, null, null, List.of(), false)))); }
}
