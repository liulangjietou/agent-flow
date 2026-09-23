package io.agentflow.approval.operations;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
 * 管理员检索覆盖真实 SQL、权限、日期边界和分页期间的数据变化。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:application-search;DB_CLOSE_DELAY=-1", "agentflow.auth.demo-enabled=true"})
@AutoConfigureMockMvc
class ApplicationSearchIntegrationTest {
    private static final String PATH = "/api/v1/operations/applications";
    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean AuthService auth;

    @Test
    void requiresTenantAdminAndDoesNotReturnApplicationContents() throws Exception {
        String key = "scope-" + UUID.randomUUID();
        String expected = seed("demo", key, "alice", "DRAFT", "2020-01-01T00:00:00Z", "检索摘要", 1);
        seed("other", key, "alice", "DRAFT", "2020-01-01T00:00:00Z", "其他租户", 1);
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        for (String user : List.of("alice", "manager", "finance")) mvc.perform(get(PATH).header("Authorization", token(user))).andExpect(status().isForbidden());
        doReturn(new Actor("demo", "designer", Set.of("PROCESS_ADMIN"))).when(auth).authenticate("process-only");
        mvc.perform(get(PATH).header("Authorization", "Bearer process-only")).andExpect(status().isForbidden());
        var page = read(Map.of("processKey", key));
        assertThat(page.path("items").findValuesAsText("id")).containsExactly(expected);
        assertThat(page.toString()).doesNotContain("payload", "formSchema", "secret-value", "runtimeDefinitionId");
        assertThat(page.path("items").get(0).path("createdBy").asText()).isEqualTo("alice");
        doReturn(new Actor("other", "admin", Set.of("ADMIN"))).when(auth).authenticate("other-admin");
        var other = mvc.perform(get(PATH).param("processKey", key).header("Authorization", "Bearer other-admin"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(other).contains("其他租户").doesNotContain(expected);
    }

    @Test
    void combinesExactFiltersAndUtcCreationDatesWithoutChangingRecords() throws Exception {
        String key = "filters-" + UUID.randomUUID();
        String expected = seed("demo", key, "alice", "RETURNED", "2020-01-02T00:00:00Z", "合同 100%_!", 2);
        seed("demo", key, "alice", "RETURNED", "2020-01-02T23:59:59.999999Z", "合同 100%_!", 2);
        seed("demo", key, "alice", "RETURNED", "2020-01-01T23:59:59.999999Z", "合同 100%_!", 2);
        seed("demo", key, "alice", "RETURNED", "2020-01-03T00:00:00Z", "合同 100%_!", 2);
        seed("demo", key, "bob", "RETURNED", "2020-01-02T00:00:00Z", "合同 100%_!", 2);
        seed("demo", key, "alice", "APPROVED", "2020-01-02T00:00:00Z", "合同 100%_!", 2);
        seed("demo", key, "alice", "RETURNED", "2020-01-02T00:00:00Z", "合同 100%_!", 1);
        seed("demo", key, "alice", "RETURNED", "2020-01-02T00:00:00Z", "合同 100abc", 2);
        var filters = Map.of("processKey", key, "definitionVersion", "2", "applicant", "alice", "status", "RETURNED", "q", "%_!", "from", "2020-01-02", "to", "2020-01-02");
        var before = jdbc.queryForList("SELECT * FROM approval_application WHERE process_key=? ORDER BY id", key);
        var page = read(filters);
        assertThat(page.path("items")).hasSize(2); assertThat(page.path("items").findValuesAsText("id")).contains(expected);
        assertThat(read(Map.of("q", expected)).path("items").findValuesAsText("id")).containsExactly(expected); // 业务单号含此标识。
        assertThat(read(Map.of("processKey", key, "applicant", "alice' OR '1'='1")).path("items")).isEmpty();
        assertThat(jdbc.queryForList("SELECT * FROM approval_application WHERE process_key=? ORDER BY id", key)).isEqualTo(before);
    }

    @Test
    void pagesTiedCreationTimesWithoutDuplicatesAndUpdatesDoNotMoveTheCursor() throws Exception {
        String key = "pages-" + UUID.randomUUID(); var expected = new HashSet<String>();
        for (int index = 0; index < 7; index++) expected.add(seed("demo", key, "alice", "DRAFT", "2020-01-01T00:00:00Z", "分页 " + index, 1));
        var first = read(Map.of("processKey", key, "limit", "2")); var found = new ArrayList<>(first.path("items").findValuesAsText("id"));
        String cursor = first.path("nextCursor").asText();
        String inserted = seed("demo", key, "alice", "DRAFT", "2020-01-02T00:00:00Z", "后来新建", 1);
        jdbc.update("UPDATE approval_application SET updated_at=CURRENT_TIMESTAMP,status='CANCELLED' WHERE process_key=?", key);
        while (!cursor.isEmpty()) {
            var next = read(Map.of("processKey", key, "limit", "2", "cursor", cursor));
            found.addAll(next.path("items").findValuesAsText("id")); cursor = next.path("nextCursor").asText("");
        }
        assertThat(found).hasSize(7).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected).doesNotContain(inserted);
        assertThat(read(Map.of("processKey", key)).path("items").get(0).path("id").asText()).isEqualTo(inserted);
    }

    @Test
    void rejectsInvalidQueriesAndCursorsFromOtherFiltersAccountsOrRoles() throws Exception {
        String key = "cursor-" + UUID.randomUUID();
        for (int index = 0; index < 2; index++) seed("demo", key, "alice", "DRAFT", "2020-01-01T00:00:00Z", "游标", 1);
        String cursor = read(Map.of("processKey", key, "limit", "1")).path("nextCursor").asText();
        for (Map<String, String> invalid : List.of(Map.of("tenantId", "other"), Map.of("limit", "0"), Map.of("limit", "101"),
                Map.of("status", "UNKNOWN"), Map.of("definitionVersion", "1"), Map.of("processKey", key, "definitionVersion", "0"),
                Map.of("from", "2020-02-30"), Map.of("from", "2020-01-02", "to", "2020-01-01"), Map.of("from", "0000-01-01"),
                Map.of("to", "+10000-01-01"), Map.of("cursor", ""), Map.of("cursor", "not-a-cursor"),
                Map.of("q", "x".repeat(101)), Map.of("applicant", "a\nb"), Map.of("processKey", key, "q", "changed", "cursor", cursor))) {
            var request = get(PATH).header("Authorization", token("admin")); invalid.forEach(request::param);
            mvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_APPLICATION_QUERY"));
        }
        for (Actor actor : List.of(new Actor("other", "admin", Set.of("ADMIN")), new Actor("demo", "other-admin", Set.of("ADMIN")),
                new Actor("demo", "admin", Set.of("ADMIN", "EXTRA")))) {
            doReturn(actor).when(auth).authenticate("changed-actor");
            mvc.perform(get(PATH).param("processKey", key).param("cursor", cursor).header("Authorization", "Bearer changed-actor"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void indexMigrationPreservesExistingRowsAndEarlierMigrations() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:search-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).target("14").load().migrate(); var db = new JdbcTemplate(source);
        db.update("INSERT INTO approval_application (id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version) VALUES (?,'demo','SEARCH-OLD','old',1,'alice','旧数据','{}','DRAFT',1,1)", UUID.randomUUID().toString());
        var rows = db.queryForList("SELECT * FROM approval_application"); var migrations = db.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        Flyway.configure().dataSource(source).target("15").load().migrate();
        assertThat(db.queryForList("SELECT * FROM approval_application")).isEqualTo(rows);
        assertThat(db.queryForList("SELECT * FROM \"flyway_schema_history\" WHERE (\"version\" IS NULL OR \"version\"<>'15') ORDER BY \"installed_rank\"")).isEqualTo(migrations);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE INDEX_NAME='IDX_APPLICATION_TENANT_CREATED'", Integer.class)).isEqualTo(1);
        assertThat(Flyway.configure().dataSource(source).target("15").load().migrate().migrationsExecuted).isZero();
    }

    private String seed(String tenant, String key, String applicant, String state, String time, String title, int version) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_application (id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,'secret-value',?,1,1,?,?)",
                id, tenant, "SEARCH-" + id, key, version, applicant, title, state, Timestamp.from(Instant.parse(time)), Timestamp.from(Instant.parse(time)));
        return id;
    }
    private JsonNode read(Map<String, String> filters) throws Exception {
        var request = get(PATH).header("Authorization", token("admin")); filters.forEach(request::param);
        return json.read(mvc.perform(request).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
}
