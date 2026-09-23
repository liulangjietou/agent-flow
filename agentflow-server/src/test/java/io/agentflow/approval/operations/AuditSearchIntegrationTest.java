package io.agentflow.approval.operations;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 审计检索验证实际 SQL、权限、历史缺失元数据与稳定分页。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:audit-search;DB_CLOSE_DELAY=-1", "agentflow.auth.demo-enabled=true"})
@AutoConfigureMockMvc
class AuditSearchIntegrationTest {
    private static final String PATH = "/api/v1/operations/audit";
    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean AuthService auth;

    @Test
    void requiresAdminAndNeverLinksAnotherTenantsApplicationOrReturnsPayload() throws Exception {
        String actor = UUID.randomUUID().toString(), app = application("other", "其他租户秘密");
        String unlinked = event("demo", app, "Task", "APPROVE", actor, "2020-01-01T00:00:00Z");
        event("other", app, "Task", "APPROVE", actor, "2020-01-01T00:00:00Z");
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        for (String user : List.of("alice", "manager", "finance")) mvc.perform(get(PATH).header("Authorization", token(user))).andExpect(status().isForbidden());
        doReturn(new Actor("demo", "designer", Set.of("PROCESS_ADMIN"))).when(auth).authenticate("process-only");
        mvc.perform(get(PATH).header("Authorization", "Bearer process-only")).andExpect(status().isForbidden());
        var page = read(Map.of("actor", actor));
        assertThat(page.path("items").findValuesAsText("id")).containsExactly(unlinked);
        assertThat(page.path("items").get(0).path("applicationId").isMissingNode()).isTrue();
        assertThat(page.toString()).doesNotContain("其他租户秘密", "secret-value", "payload", "comment");
        assertThat(read(Map.of("applicationId", app)).path("items")).isEmpty();
        doReturn(new Actor("other", "admin", Set.of("ADMIN"))).when(auth).authenticate("other-admin");
        String other = mvc.perform(get(PATH).param("actor", actor).header("Authorization", "Bearer other-admin"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(other).contains("其他租户秘密").doesNotContain(unlinked);
    }

    @Test
    void keepsLegacyUnlinkedAndUnknownEventsWithoutInventingAnActor() throws Exception {
        String app = application("demo", "旧申请");
        String legacy = event("demo", app, "Application", null, null, "1901-01-01T00:00:00Z");
        jdbc.update("UPDATE audit_event SET application_id=NULL WHERE id=?", legacy);
        String unknown = event("demo", null, "Task", null, null, "1901-01-01T00:00:00Z");
        var page = read(Map.of("from", "1901-01-01", "to", "1901-01-01"));
        assertThat(page.path("items").findValuesAsText("id")).containsExactlyInAnyOrder(legacy, unknown);
        for (JsonNode item : page.path("items")) {
            assertThat(item.path("actor").isMissingNode()).isTrue(); assertThat(item.path("action").isMissingNode()).isTrue();
            assertThat(item.path("applicationId").asText("")).isEqualTo(item.path("id").asText().equals(legacy) ? app : "");
        }
        assertThat(read(Map.of("applicationId", app)).path("items").findValuesAsText("id")).containsExactly(legacy);
    }

    @Test
    void combinesActorActionSourceApplicationAndUtcDatesWithLiteralSearch() throws Exception {
        String app = application("demo", "合同 100%_!"), actor = UUID.randomUUID().toString();
        String first = event("demo", app, "Task", "RETURN", actor, "2020-01-02T00:00:00Z");
        String last = event("demo", app, "Task", "RETURN", actor, "2020-01-02T23:59:59.999999Z");
        event("demo", app, "Task", "RETURN", actor, "2020-01-01T23:59:59.999999Z");
        event("demo", app, "Task", "RETURN", actor, "2020-01-03T00:00:00Z");
        event("demo", app, "Task", "APPROVE", actor, "2020-01-02T00:00:00Z");
        event("demo", app, "Task", "RETURN", "someone-else", "2020-01-02T00:00:00Z");
        event("demo", app, "Application", "RETURN", actor, "2020-01-02T00:00:00Z");
        event("demo", application("demo", "合同 100abc"), "Task", "RETURN", actor, "2020-01-02T00:00:00Z");
        var before = jdbc.queryForList("SELECT * FROM audit_event ORDER BY id");
        var filters = Map.of("actor", actor, "action", "RETURN", "source", "Task", "q", "%_!", "from", "2020-01-02", "to", "2020-01-02");
        assertThat(read(filters).path("items").findValuesAsText("id")).containsExactly(last, first);
        assertThat(read(Map.of("actor", actor, "q", app)).path("items")).hasSize(6);
        assertThat(read(Map.of("actor", actor, "applicationId", app)).path("items")).hasSize(6);
        assertThat(read(Map.of("actor", "alice' OR '1'='1")).path("items")).isEmpty();
        assertThat(jdbc.queryForList("SELECT * FROM audit_event ORDER BY id")).isEqualTo(before);
    }

    @Test
    void pagesTiedEventsWithoutDuplicatesWhileNewEventsArrive() throws Exception {
        String app = application("demo", "分页申请"), actor = UUID.randomUUID().toString(); var expected = new HashSet<String>();
        for (int index = 0; index < 7; index++) expected.add(event("demo", app, "Task", "APPROVE", actor, "2020-01-01T00:00:00Z"));
        var first = read(Map.of("actor", actor, "limit", "2")); var found = new ArrayList<>(first.path("items").findValuesAsText("id"));
        String cursor = first.path("nextCursor").asText();
        String inserted = event("demo", app, "Task", "APPROVE", actor, "2020-01-02T00:00:00Z");
        jdbc.update("UPDATE approval_application SET title='当前标题已改变' WHERE id=?", app);
        while (!cursor.isEmpty()) {
            var next = read(Map.of("actor", actor, "limit", "2", "cursor", cursor));
            found.addAll(next.path("items").findValuesAsText("id")); cursor = next.path("nextCursor").asText("");
            assertThat(next.path("items").findValuesAsText("currentTitle")).allMatch("当前标题已改变"::equals);
        }
        assertThat(found).hasSize(7).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected).doesNotContain(inserted);
        assertThat(read(Map.of("actor", actor)).path("items").get(0).path("id").asText()).isEqualTo(inserted);
    }

    @Test
    void rejectsInvalidAndCrossContextCursors() throws Exception {
        String actorId = UUID.randomUUID().toString();
        for (int index = 0; index < 2; index++) event("demo", null, "Task", "APPROVE", actorId, "2020-01-01T00:00:00Z");
        String cursor = read(Map.of("actor", actorId, "limit", "1")).path("nextCursor").asText();
        for (Map<String, String> invalid : List.of(Map.of("tenantId", "other"), Map.of("limit", "0"), Map.of("limit", "101"),
                Map.of("action", "COMPLETE"), Map.of("source", "Engine"), Map.of("applicationId", "1-1-1-1-1"), Map.of("applicationId", ""),
                Map.of("from", "2020-02-30"), Map.of("from", "2020-01-02", "to", "2020-01-01"), Map.of("from", "0000-01-01"),
                Map.of("to", "+10000-01-01"), Map.of("cursor", ""), Map.of("cursor", "not-a-cursor"), Map.of("q", "x".repeat(101)),
                Map.of("actor", "a\nb"), Map.of("actor", actorId, "action", "APPROVE", "cursor", cursor))) {
            var request = get(PATH).header("Authorization", token("admin")); invalid.forEach(request::param);
            mvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_AUDIT_QUERY"));
        }
        for (Actor actor : List.of(new Actor("other", "admin", Set.of("ADMIN")), new Actor("demo", "other-admin", Set.of("ADMIN")),
                new Actor("demo", "admin", Set.of("ADMIN", "EXTRA")))) {
            doReturn(actor).when(auth).authenticate("changed-actor");
            mvc.perform(get(PATH).param("actor", actorId).param("cursor", cursor).header("Authorization", "Bearer changed-actor"))
                    .andExpect(status().isBadRequest());
        }
    }

    private String application(String tenant, String title) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_application (id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version) VALUES (?,?,?,'audit-test',1,'alice',?,'secret-value','DRAFT',1,1)", id, tenant, "AUDIT-" + id, title);
        return id;
    }
    private String event(String tenant, String app, String type, String action, String actor, String time) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO audit_event (id,tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,payload_json,application_id,action,actor_id,occurred_at) VALUES (?,?,?,?,?,1,'secret-value',?,?,?,?)",
                id, tenant, UUID.randomUUID().toString(), type, "Application".equals(type) ? app : "legacy-task", app, action, actor, Timestamp.from(Instant.parse(time)));
        return id;
    }
    private JsonNode read(Map<String, String> filters) throws Exception {
        var request = get(PATH).header("Authorization", token("admin")); filters.forEach(request::param);
        return json.read(mvc.perform(request).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
}
