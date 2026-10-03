package io.agentflow.finance;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.ExpenseCategoryCatalog;
import io.agentflow.expense.ExpenseConfigurationService;
import io.agentflow.expense.ExpenseLine;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 真实认证、幂等事务与数据库约束覆盖管理配置，可在独立 PostgreSQL 数据库重复执行。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "spring.datasource.url=jdbc:h2:mem:account-mapping-api;DB_CLOSE_DELAY=-1",
        "agentflow.advances.overdue.reminders-enabled=false", "agentflow.timers.enabled=false", "agentflow.finance-gateway.enabled=false",
        "agentflow.attachments.directory=/fyoung/tmp/agentflow-account-mapping-api-test-files"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class AccountMappingConfigurationApiTest {
    private static final String API = "/api/v1/admin/account-mappings";
    private static final UUID ENTITY = UUID.fromString("11111111-1111-4111-8111-111111111111");
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountMappingConfigurationService service;
    @Autowired ExpenseConfigurationService expenses;
    @Autowired FinanceGatewayConfiguration gateway;
    private String admin;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_ACCOUNT_MAPPING_TEST_URL", "jdbc:h2:mem:account-mapping-api;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_ACCOUNT_MAPPING_TEST_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_ACCOUNT_MAPPING_TEST_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_ACCOUNT_MAPPING_TEST_PASSWORD", ""));
    }

    @BeforeEach void resetConfigurationOnly() {
        jdbc.update("UPDATE account_mapping_scope SET active_revision=0,active_mapping_id=NULL,active_mapping_version=NULL");
        for (var table : List.of("account_mapping_activation", "account_mapping_version", "account_mapping_draft_revision", "account_mapping_draft", "account_mapping_scope")) jdbc.update("DELETE FROM " + table);
        jdbc.update("UPDATE expense_configuration SET active_revision=0,active_policy_id=NULL,active_policy_version=NULL");
        for (var table : List.of("expense_policy_activation", "expense_policy_version", "expense_policy_draft_revision", "expense_policy_draft", "expense_category_revision", "expense_configuration")) jdbc.update("DELETE FROM " + table);
        var target = new FinanceGatewayConfiguration.Target(); target.setEndpoint("http://127.0.0.1:9/finance"); target.setAllowUnauthenticatedLoopback(true);
        gateway.setEnabled(true); gateway.setTenants(new java.util.LinkedHashMap<>(Map.of("demo", target, "mapping-other", target)));
        admin = token("admin");
    }

    @Test void readsDoNotInitializeOrPretendToPublishAnEmptyScope() throws Exception {
        var current = read(current(ENTITY, "CNY"));
        assertThat(current.path("activeRevision").asLong()).isZero(); assertThat(current.path("categoryRevision").asLong()).isZero();
        assertThat(current.path("activeMapping").isNull()).isTrue();
        assertThat(read(API).path("items").isEmpty()).isTrue();
        assertThat(count("expense_configuration")).isZero(); assertThat(count("account_mapping_scope")).isZero();
    }

    @Test void draftsCanBeSavedOfflineButPublishingRequiresAServerConfiguredTarget() throws Exception {
        gateway.setEnabled(false);
        save("offline", 0, definition(ENTITY, "CNY", "2241"));
        write(post(API + "/offline/publish"), admin, publication(1, 0, 0)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("code").value("FINANCE_GATEWAY_UNAVAILABLE"));
        assertThat(count("account_mapping_version")).isZero();
        gateway.setEnabled(true);
        var published = publish("offline", 1, 0, 0);
        assertThat(published.path("activeMapping").path("targetDigest").asText()).isEqualTo(gateway.destination("demo").orElseThrow().digest("demo"));
        assertThat(jdbc.queryForMap("SELECT category_revision FROM account_mapping_version").get("category_revision")).isNull();
    }

    @Test void publicationAndDraftHistorySurviveRevisionsAndKeepAuditFacts() throws Exception {
        save("office", 0, definition(ENTITY, "CNY", "2241")); var first = publish("office", 1, 0, 0);
        save("office", 1, definition(ENTITY, "CNY", "2242"));
        assertThat(read(API + "/office/versions/1")).isEqualTo(first.path("activeMapping"));
        assertThat(read(current(ENTITY, "CNY"))).isEqualTo(first);
        assertThat(read(API + "/office/draft/versions/1").at("/definition/entries/0/accountCode").asText()).isEqualTo("2241");
        var second = publish("office", 2, 0, 1);
        assertThat(second.at("/activeMapping/version").asLong()).isEqualTo(2);
        assertThat(second.at("/activeMapping/publishedBy").asText()).isEqualTo("admin");
        assertThat(read(API + "/office/versions?limit=1").path("nextBeforeVersion").asLong()).isEqualTo(2);
        assertThat(read(API + "/office/versions?limit=1&beforeVersion=2").path("items").get(0).path("version").asLong()).isEqualTo(1);
        var history = read(API + "/activations" + scope(ENTITY, "CNY") + "&limit=1");
        assertThat(history.path("nextBeforeVersion").asLong()).isEqualTo(2);
        assertThat(history.path("items").get(0).path("key").asText()).isEqualTo("office");
        assertThat(read(API + "/office/versions/1")).isEqualTo(first.path("activeMapping"));
    }

    @Test void eachLegalEntityAndCurrencyHasItsOwnActivationRevision() throws Exception {
        UUID other = UUID.randomUUID();
        save("cny", 0, definition(ENTITY, "CNY", "2241")); save("usd", 0, definition(ENTITY, "USD", "2242")); save("another", 0, definition(other, "CNY", "2243"));
        for (var key : List.of("cny", "usd", "another")) assertThat(publish(key, 1, 0, 0).path("activeRevision").asLong()).isEqualTo(1);
        save("switch", 0, definition(ENTITY, "CNY", "2244")); publish("switch", 1, 0, 1);
        assertThat(read(current(ENTITY, "CNY")).path("activeRevision").asLong()).isEqualTo(2);
        assertThat(read(current(ENTITY, "USD")).path("activeRevision").asLong()).isEqualTo(1);
        assertThat(read(current(other, "CNY")).path("activeRevision").asLong()).isEqualTo(1);
        assertThat(read(API + "/activations" + scope(ENTITY, "CNY")).path("items")).hasSize(2);
        write(put(API + "/cny/draft"), admin, draftBody(1, definition(other, "CNY", "2241"))).andExpect(status().isConflict()).andExpect(jsonPath("code").value("ACCOUNT_MAPPING_SCOPE_IMMUTABLE"));
    }

    @Test void publicationRequiresAllThreeCurrentVersionsAndActiveExpenseCategories() throws Exception {
        categories(true); save("expense", 0, expenseDefinition());
        for (var versions : List.of(List.of(2L, 1L, 0L), List.of(1L, 0L, 0L), List.of(1L, 1L, 1L))) {
            write(post(API + "/expense/publish"), admin, publication(versions.get(0), versions.get(1), versions.get(2))).andExpect(status().isConflict()).andExpect(jsonPath("code").value("CONCURRENCY_CONFLICT"));
        }
        publish("expense", 1, 1, 0);
        write(post(API + "/expense/publish"), admin, publication(1, 1, 1)).andExpect(status().isConflict()).andExpect(jsonPath("code").value("ACCOUNT_MAPPING_UNCHANGED"));
        save("expense", 1, new AccountMappingDefinition("新名称", ENTITY, "CNY", expenseDefinition().entries())); categories(false);
        write(post(API + "/expense/publish"), admin, publication(2, 2, 1)).andExpect(status().isConflict()).andExpect(jsonPath("code").value("ACCOUNT_MAPPING_NOT_PUBLISHABLE"));
        assertThat(read(API + "/expense/versions/1").at("/definition/entries/0/accountCode").asText()).isEqualTo("6602");
    }

    @Test void idempotencyReplaysOriginalPublicationAfterFurtherDraftChanges() throws Exception {
        save("office", 0, definition(ENTITY, "CNY", "2241")); String key = UUID.randomUUID().toString(); var payload = json.write(publication(1, 0, 0));
        var first = write(post(API + "/office/publish"), admin, key, payload).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        save("office", 1, definition(ENTITY, "CNY", "2242"));
        var replay = write(post(API + "/office/publish"), admin, key, payload).andExpect(status().isOk()).andExpect(header().string("Idempotency-Replayed", "true")).andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first); assertThat(count("account_mapping_version")).isEqualTo(1);
        write(post(API + "/office/publish"), admin, key, json.write(publication(2, 0, 1))).andExpect(status().isConflict());
    }

    @Test void activationFailureRollsBackPublicationDraftCursorAndIdempotentReceipt() throws Exception {
        save("office", 0, definition(ENTITY, "CNY", "2241")); var before = read(API + "/office/draft");
        String key = UUID.randomUUID().toString(); var payload = json.write(publication(1, 0, 0));
        jdbc.execute("ALTER TABLE account_mapping_activation ADD CONSTRAINT test_mapping_failure CHECK (revision < 1)");
        try { assertThatThrownBy(() -> write(post(API + "/office/publish"), admin, key, payload)).hasRootCauseInstanceOf(java.sql.SQLException.class); }
        finally { jdbc.execute("ALTER TABLE account_mapping_activation DROP CONSTRAINT test_mapping_failure"); }
        assertThat(read(API + "/office/draft")).isEqualTo(before);
        assertThat(count("account_mapping_version")).isZero(); assertThat(count("account_mapping_activation")).isZero();
        assertThat(read(current(ENTITY, "CNY")).path("activeRevision").asLong()).isZero();
        write(post(API + "/office/publish"), admin, key, payload).andExpect(status().isOk()).andExpect(header().string("Idempotency-Replayed", "false"));
    }

    @Test void twoKeysCannotConcurrentlyReplaceTheSameConfirmedActiveRevision() throws Exception {
        save("first", 0, definition(ENTITY, "CNY", "2241")); save("second", 0, definition(ENTITY, "CNY", "2242"));
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (var key : List.of("first", "second")) futures.add(pool.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Publication did not start");
                return write(post(API + "/" + key + "/publish"), admin, publication(1, 0, 0)).andReturn().getResponse().getStatus();
            }));
            start.countDown(); assertThat(List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
            assertThat(count("account_mapping_version")).isEqualTo(1); assertThat(count("account_mapping_activation")).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void roleChecksPrecedeReadsWritesAndSuccessfulIdempotentReplay() throws Exception {
        String key = UUID.randomUUID().toString(); var payload = json.write(draftBody(0, definition(ENTITY, "CNY", "2241")));
        write(put(API + "/office/draft"), admin, key, payload).andExpect(status().isOk());
        mvc.perform(get(API)).andExpect(status().isUnauthorized());
        for (var identity : List.of(token("alice"), token("finance"), restrictedAdmin())) {
            for (var path : List.of(API, current(ENTITY, "CNY"), API + "/office/draft", API + "/office/versions", API + "/activations" + scope(ENTITY, "CNY"))) {
                mvc.perform(get(path).header("Authorization", identity)).andExpect(status().isForbidden());
            }
            write(put(API + "/office/draft"), identity, key, payload).andExpect(status().isForbidden());
        }
    }

    @Test void foreignTenantCannotBeEnumeratedReadOrOverwritten() throws Exception {
        var foreign = new Actor("mapping-other", "finance-admin", Set.of(AccountMappingConfigurationController.CONFIGURATION_ROLE));
        service.saveDraft(foreign, "foreign", 0, definition(ENTITY, "CNY", "2241"), "外部租户配置"); service.publish(foreign, "foreign", 1, 0, 0, "发布");
        for (var path : List.of(API + "/foreign/draft", API + "/foreign/draft/versions/1", API + "/foreign/versions", API + "/foreign/versions/1")) {
            mvc.perform(get(path).header("Authorization", admin)).andExpect(status().isNotFound());
        }
        assertThat(read(API).path("items").isEmpty()).isTrue(); assertThat(read(current(ENTITY, "CNY")).path("activeRevision").asLong()).isZero();
        assertThat(read(API + "/activations" + scope(ENTITY, "CNY")).path("items").isEmpty()).isTrue();
        write(put(API + "/foreign/draft"), admin, draftBody(1, definition(ENTITY, "CNY", "2242"))).andExpect(status().isNotFound());
    }

    @Test void directoryPaginationAndScopeFiltersDoNotExposeAccountCodes() throws Exception {
        for (var key : List.of("one", "two", "three")) save(key, 0, definition(ENTITY, "CNY", "2241"));
        save("other", 0, definition(UUID.randomUUID(), "USD", "2242"));
        var seen = new HashSet<String>(); String path = API + scope(ENTITY, "CNY") + "&limit=2";
        while (true) {
            var page = read(path);
            for (var value : page.path("items")) { assertThat(seen.add(value.path("key").asText())).isTrue(); assertThat(value.has("definition")).isFalse(); }
            if (page.path("nextAfterKey").isNull()) break;
            path = API + scope(ENTITY, "CNY") + "&limit=2&afterKey=" + page.path("nextAfterKey").asText();
        }
        assertThat(seen).containsExactlyInAnyOrder("one", "two", "three");
    }

    @Test void databaseRejectsActivationAcrossScopesAndRetainsReferencedCategories() throws Exception {
        categories(true); save("expense", 0, expenseDefinition()); var current = publish("expense", 1, 1, 0);
        UUID other = UUID.randomUUID(); save("other", 0, definition(other, "CNY", "2241"));
        assertThatThrownBy(() -> jdbc.update("UPDATE account_mapping_scope SET active_revision=1,active_mapping_id=?,active_mapping_version=1 WHERE tenant_id='demo' AND legal_entity_id=?",
                current.at("/activeMapping/mappingId").asText(), other.toString())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM expense_category_revision WHERE tenant_id='demo' AND revision=1")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void corruptPublishedProjectionIsUnavailableInsteadOfFallingBack() throws Exception {
        save("office", 0, definition(ENTITY, "CNY", "2241")); publish("office", 1, 0, 0);
        jdbc.update("UPDATE account_mapping_version SET definition_digest=? WHERE tenant_id='demo'", "f".repeat(64));
        mvc.perform(get(current(ENTITY, "CNY")).header("Authorization", admin)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("code").value("ACCOUNT_MAPPING_CONFIGURATION_INCONSISTENT"));
    }

    @ParameterizedTest @ValueSource(strings = {"0.5", "1e0", "\"0\"", "true", "null", "-1"})
    void revisionsCannotBeCoercedOrSilentlyTruncated(String revision) throws Exception {
        var valid = json.write(draftBody(0, definition(ENTITY, "CNY", "2241")));
        write(put(API + "/invalid/draft"), admin, UUID.randomUUID().toString(), valid.replace("\"expectedRevision\":0", "\"expectedRevision\":" + revision)).andExpect(status().isBadRequest());
        assertThat(count("account_mapping_draft")).isZero();
    }

    @Test void unknownFieldsNestedCoercionDuplicateKeysAndTrailingJsonFailBeforeWriting() throws Exception {
        var valid = json.write(draftBody(0, definition(ENTITY, "CNY", "2241")));
        for (var input : List.of(valid.replace("\"accountCode\":\"2241\"", "\"accountCode\":2241"), valid.replace("\"role\":\"EMPLOYEE_PAYABLE\"", "\"role\":0"),
                valid.replace("\"selector\":\"\"", "\"selector\":false"), valid.replace("\"currency\":\"CNY\"", "\"currency\":\"CNY\",\"targetDigest\":\"other\""),
                valid.replace("\"expectedRevision\":0", "\"expectedRevision\":0,\"tenantId\":\"other\""),
                valid.replace("\"expectedRevision\":0", "\"expectedRevision\":0,\"expectedRevision\":1"), valid + " {}")) {
            assertThat(input).isNotEqualTo(valid);
            write(put(API + "/invalid/draft"), admin, UUID.randomUUID().toString(), input).andExpect(status().isBadRequest());
        }
        assertThat(count("account_mapping_draft")).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"tenantId=other", "limit=0", "limit=101", "limit=1&limit=2", "afterKey=", "legalEntityId=1-1-1-1-1", "currency=JPY"})
    void directoryQueryRejectsUnknownRepeatedAndMalformedValues(String query) throws Exception {
        mvc.perform(get(API + "?" + query).header("Authorization", admin)).andExpect(status().isBadRequest());
    }

    @Test void currentAndWritesDoNotAcceptMissingScopesOrTargetOverrides() throws Exception {
        mvc.perform(get(API + "/current").header("Authorization", admin)).andExpect(status().isBadRequest());
        mvc.perform(get(API + "/current?currency=CNY").header("Authorization", admin)).andExpect(status().isBadRequest());
        save("office", 0, definition(ENTITY, "CNY", "2241"));
        write(post(API + "/office/publish?tenantId=other"), admin, publication(1, 0, 0)).andExpect(status().isBadRequest());
        var input = json.write(publication(1, 0, 0)).replace("\"expectedDraftRevision\":1", "\"expectedDraftRevision\":1,\"targetDigest\":\"other\"");
        write(post(API + "/office/publish"), admin, UUID.randomUUID().toString(), input).andExpect(status().isBadRequest());
        assertThat(count("account_mapping_version")).isZero();
    }

    private void categories(boolean active) {
        var actor = new Actor("demo", "admin", Set.of(AccountMappingConfigurationController.CONFIGURATION_ROLE));
        expenses.saveCategories(actor, expenses.categories("demo").version(), List.of(new ExpenseCategoryCatalog.Category("OFFICE", "办公", List.of(ExpenseLine.Unit.ITEM), active)), "维护类别");
    }
    private JsonNode save(String key, long revision, AccountMappingDefinition definition) throws Exception { return body(write(put(API + "/" + key + "/draft"), admin, draftBody(revision, definition)).andExpect(status().isOk())); }
    private JsonNode publish(String key, long draft, long categories, long active) throws Exception { return body(write(post(API + "/" + key + "/publish"), admin, publication(draft, categories, active)).andExpect(status().isOk())); }
    private JsonNode read(String path) throws Exception { return body(mvc.perform(get(path).header("Authorization", admin)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))); }
    private JsonNode body(ResultActions result) throws Exception { return json.read(result.andReturn().getResponse().getContentAsString(), JsonNode.class); }
    private ResultActions write(MockHttpServletRequestBuilder request, String identity, Object body) throws Exception { return write(request, identity, UUID.randomUUID().toString(), json.write(body)); }
    private ResultActions write(MockHttpServletRequestBuilder request, String identity, String key, String body) throws Exception { return mvc.perform(request.header("Authorization", identity).header("Idempotency-Key", key).contentType("application/json").content(body)); }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    @SuppressWarnings("unchecked")
    private String restrictedAdmin() {
        var login = auth.login("demo", "admin", "demo"); var tokens = (Map<String, Actor>) ReflectionTestUtils.getField(auth, "tokens");
        tokens.put(login.token(), new Actor("demo", "admin", Set.of("ADMIN", "FINANCE"))); return "Bearer " + login.token();
    }
    private static String scope(UUID entity, String currency) { return "?legalEntityId=" + entity + "&currency=" + currency; }
    private static String current(UUID entity, String currency) { return API + "/current" + scope(entity, currency); }
    private static Map<String, Object> draftBody(long revision, AccountMappingDefinition definition) { return Map.of("expectedRevision", revision, "definition", definition, "comment", "保存科目配置"); }
    private static Map<String, Object> publication(long draft, long categories, long active) { return Map.of("expectedDraftRevision", draft, "expectedCategoryRevision", categories, "expectedActiveRevision", active, "comment", "核对后发布"); }
    private static AccountMappingDefinition definition(UUID entity, String currency, String code) { return new AccountMappingDefinition("员工往来", entity, currency, List.of(new AccountMappingPort.Entry(new AccountMappingPort.Key(AccountMappingPort.Role.EMPLOYEE_PAYABLE, ""), code))); }
    private static AccountMappingDefinition expenseDefinition() { return new AccountMappingDefinition("费用科目", ENTITY, "CNY", List.of(new AccountMappingPort.Entry(new AccountMappingPort.Key(AccountMappingPort.Role.EXPENSE, "OFFICE"), "6602"))); }
}
