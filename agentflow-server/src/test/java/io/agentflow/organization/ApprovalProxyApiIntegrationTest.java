package io.agentflow.organization;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.api.idempotency.JdbcIdempotencyRepository;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.definition.DefinitionModels.DefinitionDraft;
import io.agentflow.definition.DefinitionModels.Edge;
import io.agentflow.definition.DefinitionModels.Graph;
import io.agentflow.definition.DefinitionModels.Node;
import io.agentflow.definition.DefinitionModels.NodeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理入口的真实认证、租户、幂等原号恢复、最新状态和分页语义。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=false", "agentflow.sla.reminders-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ApprovalProxyApiIntegrationTest {
    private static final String API = "/api/v1/organization/approval-proxies";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_PROXY_HTTP_TEST_URL", "jdbc:h2:mem:approval-proxy-api;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_PROXY_TEST_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_PROXY_TEST_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_PROXY_TEST_PASSWORD", ""));
    }

    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired OrganizationService organization;
    @Autowired OrganizationRepository directory;
    @Autowired ApprovalProxyRepository proxies;
    @Autowired DefinitionApplicationService definitions;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean AuthService auth;
    @MockitoSpyBean JdbcIdempotencyRepository idempotency;
    private Actor admin;
    private String token;
    private OrganizationPerson principal;
    private OrganizationPerson substitute;
    private DefinitionDraft definition;
    private Instant start;
    private Instant end;

    @BeforeEach
    void setup() {
        admin = new Actor("proxy-api-" + UUID.randomUUID(), "administrator", Set.of("ADMIN"));
        token = identity(admin);
        organization.initialize(admin);
        principal = organization.createPerson(admin, "issuer:principal/中文", "原审批人", true, true);
        substitute = organization.createPerson(admin, "substitute", "代理人", true, true);
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("review", "审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:ORG_PERSON_" + principal.id())),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("e1", "start", "review", ""), new Edge("e2", "review", "end", "")));
        var draft = definitions.create(admin.tenantId(), "proxy-api-" + UUID.randomUUID(), "代理流程", graph);
        definition = definitions.publish(admin, draft.id(), draft.revision(), "接口测试");
        start = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
        end = start.plusSeconds(3600);
    }

    @Test
    void creationReturnsOnlyStableReceiptAndReadsExposeScopeWithCurrentPeople() throws Exception {
        var receipt = body(write("", input(), UUID.randomUUID().toString(), token, 201));
        assertThat(receipt.size()).isEqualTo(2);
        var value = read("/" + receipt.path("proxyId").asText(), token, 200);
        assertThat(value.path("status").asText()).isEqualTo("SCHEDULED");
        assertThat(value.path("definitionVersion").asLong()).isEqualTo(definition.version());
        assertThat(value.path("processKey").asText()).isEqualTo(definition.key());
        assertThat(value.path("principal").path("subject").asText()).isEqualTo(principal.subject());
        assertThat(value.path("substitute").path("approvalEligible").asBoolean()).isTrue();
        var original = value.path("proxy");
        organization.updatePerson(admin, substitute.id(), "新显示名", false, true, 1);
        var updated = read("/" + receipt.path("proxyId").asText(), token, 200);
        assertThat(updated.path("proxy")).isEqualTo(original);
        assertThat(updated.path("substitute").path("displayName").asText()).isEqualTo("新显示名");
        assertThat(updated.path("substitute").path("approvalEligible").asBoolean()).isFalse();
        var page = read("?personId=" + principal.id(), token, 200);
        assertThat(page.path("items")).hasSize(1);
        assertThat(page.path("items").get(0).path("observedAt")).isEqualTo(page.path("observedAt"));
    }

    @Test
    void originalCreationAndRevocationKeysRecoverOriginalReceiptsWithoutRestoringAuthority() throws Exception {
        String key = UUID.randomUUID().toString();
        var original = body(write("", input(), key, token, 201).andExpect(header().string("Idempotency-Replayed", "false")));
        assertThat(body(write("", input(), key, token, 201).andExpect(header().string("Idempotency-Replayed", "true")))).isEqualTo(original);
        String id = original.path("proxyId").asText();
        String revokeKey = UUID.randomUUID().toString();
        var revokeBody = Map.of("expectedRevision", 1, "reason", "提前返岗");
        var revoked = body(write("/" + id + "/revoke", revokeBody, revokeKey, token, 200));
        assertThat(revoked.path("revision").asLong()).isEqualTo(2);
        assertThat(body(write("/" + id + "/revoke", revokeBody, revokeKey, token, 200)
                .andExpect(header().string("Idempotency-Replayed", "true")))).isEqualTo(revoked);
        assertThat(body(write("", input(), key, token, 201))).isEqualTo(original);
        var current = read("/" + id, token, 200);
        assertThat(current.path("status").asText()).isEqualTo("REVOKED");
        assertThat(current.path("proxy").path("revocation").path("actor").asText()).isEqualTo(admin.userId());
        assertThat(current.path("proxy").path("revocation").path("reason").asText()).isEqualTo("提前返岗");
        assertThat(directory.changes(admin.tenantId(), null, 100).stream().filter(change -> change.kind().equals("APPROVAL_PROXY"))).hasSize(2);
        assertThat(body(write("/" + id + "/revoke", revokeBody, UUID.randomUUID().toString(), token, 409)).path("code").asText())
                .isEqualTo("CONCURRENCY_CONFLICT");
    }

    @Test
    void authorizationIsRecheckedBeforeSuccessfulIdempotencyReplay() throws Exception {
        String key = UUID.randomUUID().toString();
        var created = body(write("", input(), key, token, 201));
        doReturn(new Actor(admin.tenantId(), admin.userId(), Set.of("APPROVER"))).when(auth).authenticate(token);
        read("", token, 403);
        read("/" + created.path("proxyId").asText(), token, 403);
        write("", input(), key, token, 403);
        write("/" + created.path("proxyId").asText() + "/revoke", Map.of("expectedRevision", 1, "reason", "无权"), UUID.randomUUID().toString(), token, 403);
        assertThat(proxies.list(admin.tenantId(), null, "", 10)).hasSize(1);
        assertThat(proxies.find(admin.tenantId(), UUID.fromString(created.path("proxyId").asText())).orElseThrow().revocation()).isNull();
    }

    @Test
    void isolatesTenantsAndRejectsUserSelectedTenantOrActorFilters() throws Exception {
        var created = body(write("", input(), UUID.randomUUID().toString(), token, 201));
        var other = new Actor("foreign-" + UUID.randomUUID(), "administrator", Set.of("ADMIN"));
        String otherToken = identity(other);
        organization.initialize(other);
        assertThat(read("", otherToken, 200).path("items")).isEmpty();
        read("/" + created.path("proxyId").asText(), otherToken, 404);
        write("/" + created.path("proxyId").asText() + "/revoke", Map.of("expectedRevision", 1, "reason", "跨租户"), UUID.randomUUID().toString(), otherToken, 404);
        assertThat(body(write("", input(), UUID.randomUUID().toString(), otherToken, 422)).path("code").asText()).isEqualTo("APPROVAL_PROXY_DEFINITION_REQUIRED");
        for (String query : List.of("tenantId=" + admin.tenantId(), "actor=administrator", "afterId=invalid", "limit=101", "limit=0")) read("?" + query, token, 400);
        mvc.perform(get(API)).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsUnknownAuthorityFieldsAndMalformedOrEmptyScopeBeforeAnyGrantIsSaved() throws Exception {
        for (String field : List.of("tenantId", "createdBy", "roles", "revocation", "revision")) {
            var bad = input(); bad.put(field, "forged");
            write("", bad, UUID.randomUUID().toString(), token, 400);
        }
        var invalid = input(); invalid.put("endsAt", start.toString());
        assertThat(body(write("", invalid, UUID.randomUUID().toString(), token, 422)).path("code").asText()).isEqualTo("INVALID_APPROVAL_PROXY");
        invalid = input(); invalid.put("principalId", substitute.id().toString());
        write("", invalid, UUID.randomUUID().toString(), token, 422);
        invalid = input(); invalid.remove("definitionId");
        write("", invalid, UUID.randomUUID().toString(), token, 422);
        invalid = input(); invalid.put("startsAt", "yesterday");
        write("", invalid, UUID.randomUUID().toString(), token, 400);
        mvc.perform(post(API).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(json.write(input())))
                .andExpect(status().isBadRequest());
        assertThat(proxies.list(admin.tenantId(), null, "", 10)).isEmpty();
    }

    @Test
    void pagesByStableIdAndPersonFilterWithoutDuplicates() throws Exception {
        var ids = new java.util.HashSet<String>();
        for (int n = 0; n < 3; n++) {
            var body = input(); body.put("startsAt", start.plusSeconds(n * 3600L).toString()); body.put("endsAt", end.plusSeconds(n * 3600L).toString());
            ids.add(body(write("", body, UUID.randomUUID().toString(), token, 201)).path("proxyId").asText());
        }
        var seen = new java.util.HashSet<String>();
        String cursor = "";
        do {
            var page = read("?limit=1&personId=" + substitute.id() + (cursor.isEmpty() ? "" : "&afterId=" + cursor), token, 200);
            assertThat(page.path("items")).hasSize(1);
            assertThat(seen.add(page.path("items").get(0).path("proxy").path("id").asText())).isTrue();
            cursor = page.path("nextAfterId").asText("");
        } while (!cursor.isEmpty());
        assertThat(seen).isEqualTo(ids);
        assertThat(read("?personId=" + UUID.randomUUID(), token, 200).path("items")).isEmpty();
    }

    @Test
    void failedReceiptPersistenceRollsBackGrantAndAuditThenOriginalKeyCanBeRetried() throws Exception {
        String key = UUID.randomUUID().toString();
        long revision = directory.revision(admin.tenantId());
        doThrow(new IllegalStateException("Receipt persistence failed")).when(idempotency).complete(eq(admin.tenantId()), eq(key), anyInt(), anyString());
        assertThatThrownBy(() -> write("", input(), key, token, 500))
                .isInstanceOf(jakarta.servlet.ServletException.class).hasRootCauseMessage("Receipt persistence failed");
        assertThat(proxies.list(admin.tenantId(), null, "", 10)).isEmpty();
        assertThat(directory.revision(admin.tenantId())).isEqualTo(revision);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_idempotency WHERE tenant_id=? AND idempotency_key=?", Long.class, admin.tenantId(), key)).isZero();
        reset(idempotency);
        write("", input(), key, token, 201).andExpect(header().string("Idempotency-Replayed", "false"));
        assertThat(proxies.list(admin.tenantId(), null, "", 10)).hasSize(1);
        assertThat(directory.revision(admin.tenantId())).isEqualTo(revision + 1);
    }

    @Test
    void reusedCreationKeyCannotChangeOriginalPeopleOrPeriod() throws Exception {
        String key = UUID.randomUUID().toString();
        var created = body(write("", input(), key, token, 201));
        var changed = input(); changed.put("endsAt", end.plusSeconds(1).toString());
        assertThat(body(write("", changed, key, token, 409)).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        var persisted = proxies.find(admin.tenantId(), UUID.fromString(created.path("proxyId").asText())).orElseThrow();
        assertThat(persisted.endsAt()).isEqualTo(end);
    }

    @Test
    void malformedRevocationCannotChangeScopeOrConsumeOriginalRevision() throws Exception {
        var created = body(write("", input(), UUID.randomUUID().toString(), token, 201));
        String id = created.path("proxyId").asText();
        String route = "/" + id + "/revoke";
        write(route, Map.of("reason", "缺少修订"), UUID.randomUUID().toString(), token, 400);
        write(route, Map.of("expectedRevision", 0, "reason", "非法修订"), UUID.randomUUID().toString(), token, 400);
        write(route, Map.of("expectedRevision", 1, "reason", " "), UUID.randomUUID().toString(), token, 422);
        write(route, Map.of("expectedRevision", 1, "reason", "更改期限", "endsAt", end.plusSeconds(1).toString()),
                UUID.randomUUID().toString(), token, 400);
        var preserved = proxies.find(admin.tenantId(), UUID.fromString(id)).orElseThrow();
        assertThat(preserved.revocation()).isNull();
        assertThat(preserved.revision()).isEqualTo(1);
        assertThat(preserved.endsAt()).isEqualTo(end);
        write(route, Map.of("expectedRevision", 1, "reason", "有效撤销"), UUID.randomUUID().toString(), token, 200);
    }

    private LinkedHashMap<String, Object> input() {
        return new LinkedHashMap<>(Map.of("definitionId", definition.id().toString(), "principalId", principal.id().toString(),
                "substituteId", substitute.id().toString(), "startsAt", start.toString(), "endsAt", end.toString(), "reason", "休假代办"));
    }

    private String identity(Actor actor) { String value = UUID.randomUUID().toString(); doReturn(actor).when(auth).authenticate(value); return value; }
    private JsonNode read(String suffix, String bearer, int expected) throws Exception {
        var result = mvc.perform(get(API + suffix).header("Authorization", "Bearer " + bearer)).andExpect(status().is(expected));
        if (expected == 200) result.andExpect(header().string("Cache-Control", "no-store"));
        return body(result);
    }
    private ResultActions write(String suffix, Object input, String key, String bearer, int expected) throws Exception {
        return mvc.perform(post(API + suffix).header("Authorization", "Bearer " + bearer).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json.write(input))).andExpect(status().is(expected));
    }
    private JsonNode body(ResultActions result) throws Exception { return json.read(result.andReturn().getResponse().getContentAsString(), JsonNode.class); }
}
