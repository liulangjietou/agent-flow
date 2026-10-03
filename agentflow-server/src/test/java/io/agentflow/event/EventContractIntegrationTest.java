package io.agentflow.event;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 真实 MVC、认证、幂等与 JDBC 事务覆盖发布及启停，H2/PostgreSQL 使用相同业务断言。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.webhooks.worker-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class EventContractIntegrationTest {
    private static final String PATH = "/api/v1/event-contracts";
    private static final String OPTIONS = "/api/v1/process-definitions/event-contract-options";
    private static final Actor ADMIN = new Actor("demo", "admin", Set.of("ADMIN"));
    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired EventContractService service;
    @Autowired EventContractRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean AuthService auth;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_EVENT_CONTRACT_URL", "jdbc:h2:mem:event-contract;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"));
        properties.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_EVENT_CONTRACT_DRIVER", "org.h2.Driver"));
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_EVENT_CONTRACT_USER", "sa"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_EVENT_CONTRACT_PASSWORD", ""));
    }

    @BeforeEach
    void principals() {
        doReturn(ADMIN).when(auth).authenticate("event-admin");
        doReturn(new Actor("demo", "designer", Set.of("PROCESS_ADMIN"))).when(auth).authenticate("event-designer");
        doReturn(new Actor("demo", "alice", Set.of("EMPLOYEE"))).when(auth).authenticate("event-employee");
        doReturn(new Actor("other", "admin", Set.of("ADMIN"))).when(auth).authenticate("event-other");
    }

    @Test
    void newPublicationAndVersionAvailabilityHaveIndependentImmutableHistory() throws Exception {
        String key = key(); var first = publish(key, 0);
        var secondInput = publication(1); secondInput.put("sourceKey", "warehouse"); secondInput.put("eventType", "ReceiptConfirmed");
        write(PATH + "/" + key + "/versions", secondInput, "event-admin", UUID.randomUUID().toString(), 201);
        assertThat(read(versionPath(key, 1), "event-admin")).isEqualTo(first);
        var disabled = write(versionPath(key, 1) + "/availability", availability(1, false), "event-admin", UUID.randomUUID().toString(), 200);
        assertThat(disabled.path("availability").path("revision").asLong()).isEqualTo(2);
        assertThat(disabled.path("publicationReason")).isEqualTo(first.path("publicationReason"));
        assertThat(disabled.path("publishedAt")).isEqualTo(first.path("publishedAt"));
        assertThat(read(versionPath(key, 2), "event-admin").path("availability").path("enabled").asBoolean()).isTrue();
        write(versionPath(key, 1) + "/availability", availability(2, true), "event-admin", UUID.randomUUID().toString(), 200);
        var history = read(versionPath(key, 1) + "/history?limit=2", "event-admin");
        assertThat(history.path("items").findValues("revision").stream().map(JsonNode::asLong)).containsExactly(3L, 2L);
        assertThat(history.path("nextBeforeRevision").asLong()).isEqualTo(2);
        var last = read(versionPath(key, 1) + "/history?limit=2&beforeRevision=2", "event-admin");
        assertThat(last.path("items").get(0).path("reason").asText()).isEqualTo("核对后发布");
        assertThat(last.path("nextBeforeRevision").isNull()).isTrue();
        var versions = read(PATH + "/" + key + "/versions?limit=1", "event-admin");
        assertThat(versions.path("items").get(0).path("version").asLong()).isEqualTo(2);
        assertThat(versions.path("nextBeforeVersion").asLong()).isEqualTo(2);
        assertThat(read(PATH + "/" + key + "/versions?beforeVersion=2", "event-admin").path("items").get(0).path("version").asLong()).isEqualTo(1);
    }

    @Test
    void originalRequestReplayDoesNotPublishTwiceAndRechecksCurrentRole() throws Exception {
        String key = key(), requestId = UUID.randomUUID().toString(), path = PATH + "/" + key + "/versions";
        var first = write(path, publication(0), "event-admin", requestId, 201);
        var replay = mvc.perform(post(path).header("Authorization", "Bearer event-admin").header("Idempotency-Key", requestId)
                .contentType("application/json").content(json.write(publication(0)))).andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true")).andReturn();
        assertThat(json.read(replay.getResponse().getContentAsString(), JsonNode.class)).isEqualTo(first);
        assertThat(count("event_contract_version", key)).isEqualTo(1); assertThat(count("event_contract_availability_history", key)).isEqualTo(1);
        write(path, publication(0), "event-designer", requestId, 403);
        doReturn(new Actor("demo", "admin", Set.of("EMPLOYEE"))).when(auth).authenticate("event-admin");
        write(path, publication(0), "event-admin", requestId, 403);
    }

    @Test
    void availabilityReplayReturnsOriginalReceiptEvenAfterALaterChange() throws Exception {
        String key = key(); publish(key, 0); String path = versionPath(key, 1) + "/availability", requestId = UUID.randomUUID().toString();
        var first = write(path, availability(1, false), "event-admin", requestId, 200);
        write(path, availability(2, true), "event-admin", UUID.randomUUID().toString(), 200);
        assertThat(write(path, availability(1, false), "event-admin", requestId, 200)).isEqualTo(first);
        assertThat(repository.find("demo", key, 1).orElseThrow().availability().revision()).isEqualTo(3);
        assertThat(count("event_contract_availability_history", key)).isEqualTo(3);
    }

    @Test
    void designerCanReadOnlySelectionMetadataAndCannotManageAnEventContract() throws Exception {
        String key = key(); publish(key, 0);
        var option = read(OPTIONS + "/" + key + "/versions/1", "event-designer");
        assertThat(option.path("key").asText()).isEqualTo(key);
        assertThat(option.toString()).doesNotContain("publicationReason", "changedBy", "tenantId");
        read(OPTIONS, "event-designer"); read(OPTIONS + "/" + key + "/versions", "event-designer");
        for (String path : List.of(PATH, versionPath(key, 1), versionPath(key, 1) + "/history")) {
            mvc.perform(get(path).header("Authorization", "Bearer event-designer")).andExpect(status().isForbidden());
        }
        write(versionPath(key, 1) + "/availability", availability(1, false), "event-designer", UUID.randomUUID().toString(), 403);
        for (String path : List.of(PATH, OPTIONS, OPTIONS + "/" + key + "/versions/1")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", "Bearer event-employee")).andExpect(status().isForbidden());
        }
    }

    @Test
    void sameKeyInAnotherTenantCannotRevealOrModifyThisTenantsContract() throws Exception {
        String key = key(); var original = publish(key, 0);
        for (String path : List.of(versionPath(key, 1), versionPath(key, 1) + "/history", PATH + "/" + key + "/versions",
                OPTIONS + "/" + key + "/versions/1", OPTIONS + "/" + key + "/versions")) {
            mvc.perform(get(path).header("Authorization", "Bearer event-other")).andExpect(status().isNotFound());
        }
        write(versionPath(key, 1) + "/availability", availability(1, false), "event-other", UUID.randomUUID().toString(), 404);
        var foreign = publication(0); foreign.put("sourceKey", "foreign");
        write(PATH + "/" + key + "/versions", foreign, "event-other", UUID.randomUUID().toString(), 201);
        assertThat(read(versionPath(key, 1), "event-admin")).isEqualTo(original);
        assertThat(read(versionPath(key, 1), "event-other").path("sourceKey").asText()).isEqualTo("foreign");
        assertThat(read(PATH, "event-other").toString()).doesNotContain("erp");
    }

    @Test
    void concurrentPublishersAndAvailabilityChangesHaveExactlyOneWinner() throws Exception {
        String key = key();
        concurrent(() -> service.publish(ADMIN, key, 0, "验收", "erp", "GoodsAccepted", "并发发布"));
        concurrent(() -> service.publish(ADMIN, key, 1, "验收第二版", "warehouse", "ReceiptConfirmed", "并发发布第二版"));
        concurrent(() -> service.changeAvailability(ADMIN, key, 1, 1, false, "并发停用"));
        assertThat(repository.latest("demo", key).orElseThrow().contract().version()).isEqualTo(2);
        assertThat(count("event_contract_version", key)).isEqualTo(2);
        assertThat(count("event_contract_availability_history", key)).isEqualTo(3);
        assertThat(repository.find("demo", key, 1).orElseThrow().availability().enabled()).isFalse();
    }

    @Test
    void invalidAndStaleWritesDoNotChangeThePublicationOrHistory() throws Exception {
        String key = key(); var first = publish(key, 0); String versions = PATH + "/" + key + "/versions";
        write(versions, publication(0), "event-admin", UUID.randomUUID().toString(), 409);
        write(versions, publication(2), "event-admin", UUID.randomUUID().toString(), 409);
        write(versionPath(key, 1) + "/availability", availability(2, false), "event-admin", UUID.randomUUID().toString(), 409);
        write(versionPath(key, 1) + "/availability", availability(1, true), "event-admin", UUID.randomUUID().toString(), 409);
        for (Map<String, Object> invalid : List.<Map<String, Object>>of(Map.of("tenantId", "other"), Map.of("sourceKey", "${bean}"), Map.of("reason", " "),
                Map.of("expectedVersion", Long.MAX_VALUE), Map.of("expectedVersion", -1), Map.of("name", " "))) {
            var body = publication(1); body.putAll(invalid);
            write(versions, body, "event-admin", UUID.randomUUID().toString(), 400);
        }
        write(versionPath(key, 1) + "/availability", Map.of("expectedRevision", 1, "reason", "缺少明确状态"), "event-admin", UUID.randomUUID().toString(), 400);
        write(versionPath(key, 1) + "/availability", Map.of("expectedRevision", 1, "enabled", false, "reason", "停用", "sourceKey", "other"), "event-admin", UUID.randomUUID().toString(), 400);
        assertThat(read(versionPath(key, 1), "event-admin")).isEqualTo(first);
        assertThat(count("event_contract_version", key)).isEqualTo(1); assertThat(count("event_contract_availability_history", key)).isEqualTo(1);
    }

    @Test
    void historyInsertFailureRollsBackCurrentAvailabilityAndOuterFailureRollsBackAPublication() {
        String key = key(); var original = service.publish(ADMIN, key, 0, "验收", "erp", "GoodsAccepted", "发布");
        jdbc.update("""
                INSERT INTO event_contract_availability_history (tenant_id,contract_key,contract_version,revision,enabled,changed_by,changed_at,reason)
                VALUES ('demo',?,1,2,FALSE,'fixture',CURRENT_TIMESTAMP,'模拟历史冲突')
                """, key);
        assertThatThrownBy(() -> service.changeAvailability(ADMIN, key, 1, 1, false, "不能半保存"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(repository.find("demo", key, 1).orElseThrow()).isEqualTo(original);
        var transaction = new TransactionTemplate(transactions);
        String newKey = key();
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.publish(ADMIN, newKey, 0, "回滚发布", "erp", "GoodsAccepted", "不得留下半成品");
            throw new DomainException("SIMULATED_FAILURE", "Simulated receipt failure");
        })).isInstanceOf(DomainException.class);
        assertThat(repository.latest("demo", newKey)).isEmpty(); assertThat(count("event_contract_version", newKey)).isZero();
        assertThat(count("event_contract_availability_history", newKey)).isZero();
    }

    @Test
    void directoryPaginationIncludesDisabledLatestAndRejectsForeignFilters() throws Exception {
        String key = key(); publish(key, 0); publish(key, 1);
        write(versionPath(key, 2) + "/availability", availability(1, false), "event-admin", UUID.randomUUID().toString(), 200);
        var seen = new HashSet<String>(); String path = OPTIONS + "?limit=2";
        do {
            var page = read(path, "event-designer");
            for (var option : page.path("items")) {
                assertThat(seen.add(option.path("key").asText())).isTrue();
                if (option.path("key").asText().equals(key)) {
                    assertThat(option.path("version").asLong()).isEqualTo(2); assertThat(option.path("enabled").asBoolean()).isFalse();
                }
            }
            path = page.path("nextAfterKey").isNull() ? null : OPTIONS + "?limit=2&afterKey=" + page.path("nextAfterKey").asText();
        } while (path != null);
        assertThat(seen).contains(key);
        for (String query : List.of("limit=0", "limit=101", "limit=01", "afterKey=", "tenantId=other")) {
            mvc.perform(get(OPTIONS + "?" + query).header("Authorization", "Bearer event-designer")).andExpect(status().isBadRequest());
        }
        for (String query : List.of("beforeVersion=0", "beforeVersion=9223372036854775808", "beforeRevision=1", "tenantId=other")) {
            mvc.perform(get(PATH + "/" + key + "/versions?" + query).header("Authorization", "Bearer event-admin")).andExpect(status().isBadRequest());
        }
    }

    @Test
    void absentCursorDoesNotExcludeTheLargestSupportedVersionOrRevision() {
        String key = key();
        // 用边界夹具定位空游标与合法最大值混用，常见的 v1/v2 分页无法覆盖这个分支。
        jdbc.update("INSERT INTO event_contract (tenant_id,contract_key,latest_version) VALUES ('demo',?,?)", key, Long.MAX_VALUE - 1);
        var contract = EventContract.publish("demo", key, Long.MAX_VALUE, "边界版本", "erp", "GoodsAccepted", "admin", "边界验证", Instant.now());
        repository.publish(contract, Long.MAX_VALUE - 1);
        assertThat(repository.versions("demo", key, null, 10)).extracting(value -> value.contract().version()).containsExactly(Long.MAX_VALUE);
        jdbc.update("UPDATE event_contract_version SET availability_revision=? WHERE tenant_id='demo' AND contract_key=?", Long.MAX_VALUE - 1, key);
        service.changeAvailability(ADMIN, key, Long.MAX_VALUE, Long.MAX_VALUE - 1, false, "最大修订仍须出现在第一页");
        assertThat(repository.history("demo", key, Long.MAX_VALUE, null, 10)).extracting(EventContractAvailability::revision).containsExactly(Long.MAX_VALUE, 1L);
        assertThat(repository.versions("demo", key, Long.MAX_VALUE, 10)).isEmpty();
        assertThat(repository.history("demo", key, Long.MAX_VALUE, Long.MAX_VALUE, 10)).extracting(EventContractAvailability::revision).containsExactly(1L);
    }

    private void concurrent(Runnable action) throws Exception {
        var pool = Executors.newFixedThreadPool(2); var barrier = new CyclicBarrier(2);
        java.util.concurrent.Callable<String> attempt = () -> {
            barrier.await(); try { action.run(); return "OK"; } catch (DomainException conflict) { return conflict.code(); }
        };
        try {
            var first = pool.submit(attempt); var second = pool.submit(attempt);
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS))).containsExactlyInAnyOrder("OK", "CONCURRENCY_CONFLICT");
        } finally { pool.shutdownNow(); }
    }
    private JsonNode publish(String key, long expected) throws Exception { return write(PATH + "/" + key + "/versions", publication(expected), "event-admin", UUID.randomUUID().toString(), 201); }
    private JsonNode read(String path, String token) throws Exception {
        return json.read(mvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
    private JsonNode write(String path, Object body, String token, String idempotency, int status) throws Exception {
        return json.read(mvc.perform(post(path).header("Authorization", "Bearer " + token).header("Idempotency-Key", idempotency)
                .contentType("application/json").content(json.write(body))).andExpect(status().is(status)).andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
    private int count(String table, String key) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id='demo' AND contract_key=?", Integer.class, key); }
    private static String key() { return "event-" + UUID.randomUUID(); }
    private static String versionPath(String key, long version) { return PATH + "/" + key + "/versions/" + version; }
    private static Map<String, Object> publication(long expected) {
        return new HashMap<>(Map.of("expectedVersion", expected, "name", "验收完成", "sourceKey", "erp", "eventType", "GoodsAccepted", "reason", "核对后发布"));
    }
    private static Map<String, Object> availability(long revision, boolean enabled) { return Map.of("expectedRevision", revision, "enabled", enabled, "reason", enabled ? "核对后恢复" : "来源维护停用"); }
}
