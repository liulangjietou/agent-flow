package io.agentflow.definition;

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
 * 目录查询使用真实 SQL 验证摘要隔离、状态权限、稳定分页和只读迁移。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:definition-catalog;DB_CLOSE_DELAY=-1", "agentflow.auth.demo-enabled=true"})
@AutoConfigureMockMvc
class DefinitionCatalogIntegrationTest {
    private static final String PATH = "/api/v1/process-definitions/search";
    private static final String CREATED = "2020-01-01T00:00:00Z";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonUtil json;
    @MockitoSpyBean AuthService auth;

    @Test
    void enforcesTenantAndPublishedOnlyVisibilityWithoutLoadingGraphOrForm() throws Exception {
        String key = "scope-" + UUID.randomUUID();
        String draft = seed("demo", key, "草稿", null, CREATED);
        String published = seed("demo", key, "已发布", 1L, CREATED);
        seed("other", key, "其他租户", 1L, CREATED);
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        var page = read("admin", Map.of("processKey", key));
        assertThat(page.path("items").findValuesAsText("id")).containsExactlyInAnyOrder(draft, published);
        assertThat(page.toString()).doesNotContain("graph", "formSchema", "secret", "tenantId");
        for (String user : List.of("alice", "manager", "finance")) {
            assertThat(read(user, Map.of("processKey", key)).path("items").findValuesAsText("id")).containsExactly(published);
            mvc.perform(get(PATH).param("status", "DRAFT").header("Authorization", token(user))).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/process-definitions/" + draft).header("Authorization", token(user))).andExpect(status().isNotFound());
        }
        doReturn(new Actor("demo", "designer", Set.of("PROCESS_ADMIN"))).when(auth).authenticate("designer-token");
        var designer = readToken("Bearer designer-token", Map.of("processKey", key, "status", "DRAFT"));
        assertThat(designer.path("items").findValuesAsText("id")).containsExactly(draft);
        doReturn(new Actor("other", "admin", Set.of("ADMIN"))).when(auth).authenticate("other-token");
        assertThat(readToken("Bearer other-token", Map.of("processKey", key)).toString()).contains("其他租户").doesNotContain(draft, published);
    }

    @Test
    void combinesNameKeyVersionAndStatusWithLiteralWildcardCharacters() throws Exception {
        String key = "contract-" + UUID.randomUUID();
        String wanted = seed("demo", key, "合同 100%_!", 2L, CREATED);
        seed("demo", key, "合同 100%_!", 1L, CREATED);
        seed("demo", key, "合同 100%_!", null, CREATED);
        seed("demo", key, "合同 100abc", 3L, CREATED);
        var before = jdbc.queryForList("SELECT * FROM approval_definition WHERE process_key=? ORDER BY id", key);
        assertThat(read("admin", Map.of("q", "%_!", "processKey", key, "version", "2", "status", "PUBLISHED"))
                .path("items").findValuesAsText("id")).containsExactly(wanted);
        assertThat(read("admin", Map.of("q", key.toUpperCase(java.util.Locale.ROOT))).path("items")).hasSize(4);
        assertThat(read("admin", Map.of("processKey", "' OR '1'='1")).path("items")).isEmpty();
        assertThat(jdbc.queryForList("SELECT * FROM approval_definition WHERE process_key=? ORDER BY id", key)).isEqualTo(before);
    }

    @Test
    void pagesTiedCreationTimesAndDraftUpdatesDoNotMoveRowsBetweenPages() throws Exception {
        String key = "page-" + UUID.randomUUID(); var expected = new HashSet<String>();
        for (int index = 0; index < 7; index++) expected.add(seed("demo", key, "分页" + index, null, CREATED));
        var first = read("admin", Map.of("processKey", key, "limit", "2"));
        var found = new ArrayList<>(first.path("items").findValuesAsText("id")); String cursor = first.path("nextCursor").asText();
        String inserted = seed("demo", key, "后来新建", null, "2020-01-02T00:00:00Z");
        jdbc.update("UPDATE approval_definition SET name='编辑后的草稿',updated_at=CURRENT_TIMESTAMP WHERE process_key=?", key);
        while (!cursor.isEmpty()) {
            var next = read("admin", Map.of("processKey", key, "limit", "2", "cursor", cursor));
            found.addAll(next.path("items").findValuesAsText("id")); cursor = next.path("nextCursor").asText("");
        }
        assertThat(found).hasSize(7).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected).doesNotContain(inserted);
        assertThat(read("admin", Map.of("processKey", key)).path("items").get(0).path("id").asText()).isEqualTo(inserted);
    }

    @Test
    void rejectsInvalidFiltersAndCrossActorOrChangedFilterCursors() throws Exception {
        String key = "cursor-" + UUID.randomUUID();
        for (int index = 0; index < 2; index++) seed("demo", key, "游标", null, CREATED);
        String cursor = read("admin", Map.of("processKey", key, "limit", "1")).path("nextCursor").asText();
        for (Map<String, String> invalid : List.of(Map.of("tenantId", "other"), Map.of("limit", "0"), Map.of("limit", "101"),
                Map.of("status", "UNKNOWN"), Map.of("version", "1"), Map.of("version", "0", "processKey", key),
                Map.of("version", "1", "processKey", key, "status", "DRAFT"), Map.of("q", "x".repeat(101)),
                Map.of("q", "a\nb"), Map.of("cursor", ""), Map.of("cursor", "invalid"),
                Map.of("processKey", key, "q", "changed", "cursor", cursor))) {
            var request = get(PATH).header("Authorization", token("admin")); invalid.forEach(request::param);
            mvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_DEFINITION_QUERY"));
        }
        for (Actor actor : List.of(new Actor("other", "admin", Set.of("ADMIN")), new Actor("demo", "another", Set.of("ADMIN")),
                new Actor("demo", "admin", Set.of("PROCESS_ADMIN")), new Actor("demo", "admin", Set.of("EMPLOYEE")))) {
            doReturn(actor).when(auth).authenticate("changed-token");
            mvc.perform(get(PATH).param("processKey", key).param("cursor", cursor).header("Authorization", "Bearer changed-token"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void indexMigrationPreservesRowsAndExistingMigrations() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:catalog-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).target("17").load().migrate(); var db = new JdbcTemplate(source);
        db.update("INSERT INTO approval_definition (id,tenant_id,process_key,name,version,revision,status,graph_json) VALUES (?,'demo','old','旧草稿',NULL,1,'DRAFT','{}')", UUID.randomUUID().toString());
        var rows = db.queryForList("SELECT * FROM approval_definition");
        var migrations = db.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        Flyway.configure().dataSource(source).target("18").load().migrate();
        assertThat(db.queryForList("SELECT * FROM approval_definition")).isEqualTo(rows);
        assertThat(db.queryForList("SELECT * FROM \"flyway_schema_history\" WHERE (\"version\" IS NULL OR \"version\"<>'18') ORDER BY \"installed_rank\"")).isEqualTo(migrations);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE INDEX_NAME='IDX_DEFINITION_TENANT_CREATED'", Integer.class)).isEqualTo(1);
        assertThat(Flyway.configure().dataSource(source).target("18").load().migrate().migrationsExecuted).isZero();
    }

    private String seed(String tenant, String key, String name, Long version, String time) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_definition (id,tenant_id,process_key,name,version,revision,status,graph_json,form_schema_json,created_at,updated_at) VALUES (?,?,?,?,?,1,?,?,NULL,?,?)",
                id, tenant, key, name, version, version == null ? "DRAFT" : "PUBLISHED", "{\"nodes\":[{\"id\":\"start\",\"type\":\"START\",\"name\":\"开始\",\"properties\":{}},{\"id\":\"end\",\"type\":\"END\",\"name\":\"结束\",\"properties\":{}}],\"edges\":[{\"id\":\"path\",\"source\":\"start\",\"target\":\"end\",\"condition\":\"\",\"defaultBranch\":false}]}", Timestamp.from(Instant.parse(time)), Timestamp.from(Instant.parse(time)));
        return id;
    }
    private JsonNode read(String user, Map<String, String> filters) throws Exception { return readToken(token(user), filters); }
    private JsonNode readToken(String bearer, Map<String, String> filters) throws Exception {
        var request = get(PATH).header("Authorization", bearer); filters.forEach(request::param);
        return json.read(mvc.perform(request).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
}
