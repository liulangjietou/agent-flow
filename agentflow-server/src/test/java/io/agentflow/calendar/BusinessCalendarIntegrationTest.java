package io.agentflow.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.JsonUtil;
import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 通过真实 HTTP、JDBC 和幂等事务验证日历修订、租户隔离与只读试算。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:business-calendar;DB_CLOSE_DELAY=-1", "agentflow.auth.demo-enabled=true"})
@AutoConfigureMockMvc
class BusinessCalendarIntegrationTest {
    private static final String PATH = "/api/v1/business-calendars";
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired JdbcTemplate jdbc;
    @Autowired BusinessCalendarRepository repository;

    @Test
    void newRevisionPreservesOldRulesAndCalculatesAgainstTheExplicitVersion() throws Exception {
        JsonNode first = create("calendar-" + UUID.randomUUID()); String id = first.path("id").asText();
        assertThat(first.path("revision").asLong()).isEqualTo(1);
        assertThat(first.path("updatedBy").asText()).isEqualTo("admin"); assertThat(first.has("tenantId")).isFalse();
        var businessBefore = jdbc.queryForList("SELECT * FROM approval_application ORDER BY id");
        var auditBefore = jdbc.queryForList("SELECT * FROM audit_event ORDER BY id");
        var result = mvc.perform(write(put(PATH + "/" + id), "admin", UUID.randomUUID().toString(), Map.of("name", "调整工作时间", "rules", rules("UTC", "10:00"), "expectedRevision", 1)))
                .andExpect(status().isOk()).andReturn();
        JsonNode second = tree(result.getResponse().getContentAsString());
        assertThat(second.path("revision").asLong()).isEqualTo(2);
        assertThat(read(PATH + "/" + id + "/versions/1")).isEqualTo(first);
        assertThat(read(PATH + "/" + id)).isEqualTo(second);
        assertThat(calculate(id, 1).path("deadline").path("dueAt").asText()).isEqualTo("2026-09-21T09:30:00Z");
        assertThat(calculate(id, 2).path("deadline").path("dueAt").asText()).isEqualTo("2026-09-21T10:30:00Z");
        var page = read(PATH + "/" + id + "/versions?limit=1");
        assertThat(page.path("items").get(0).path("revision").asInt()).isEqualTo(2);
        assertThat(page.path("nextBeforeRevision").asLong()).isEqualTo(2);
        var last = read(PATH + "/" + id + "/versions?limit=1&beforeRevision=2");
        assertThat(last.path("items").get(0).path("revision").asInt()).isEqualTo(1);
        assertThat(last.has("nextBeforeRevision")).isTrue(); assertThat(last.path("nextBeforeRevision").isNull()).isTrue();
        assertThat(jdbc.queryForList("SELECT * FROM approval_application ORDER BY id")).isEqualTo(businessBefore);
        assertThat(jdbc.queryForList("SELECT * FROM audit_event ORDER BY id")).isEqualTo(auditBefore);
    }

    @Test
    void idempotencyReplaysOneCreationAndOneRevisionWithoutOverwritingHistory() throws Exception {
        String key = UUID.randomUUID().toString(), businessKey = "retry-" + UUID.randomUUID();
        var body = Map.of("key", businessKey, "name", "幂等日历", "rules", rules("UTC", "09:00"));
        var first = mvc.perform(write(post(PATH), "admin", key, body)).andExpect(status().isCreated()).andReturn();
        var again = mvc.perform(write(post(PATH), "admin", key, body)).andExpect(status().isCreated()).andExpect(header().string("Idempotency-Replayed", "true")).andReturn();
        assertThat(tree(again.getResponse().getContentAsString())).isEqualTo(tree(first.getResponse().getContentAsString()));
        String id = tree(first.getResponse().getContentAsString()).path("id").asText(), updateKey = UUID.randomUUID().toString();
        var update = Map.of("name", "第二版", "rules", rules("UTC", "10:00"), "expectedRevision", 1);
        mvc.perform(write(put(PATH + "/" + id), "admin", updateKey, update)).andExpect(status().isOk());
        mvc.perform(write(put(PATH + "/" + id), "admin", updateKey, update)).andExpect(status().isOk()).andExpect(header().string("Idempotency-Replayed", "true"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_calendar_version WHERE calendar_id=?", Integer.class, id)).isEqualTo(2);
        mvc.perform(write(post(PATH), "admin", UUID.randomUUID().toString(), body)).andExpect(status().isConflict()).andExpect(jsonPath("code").value("CALENDAR_KEY_CONFLICT"));
    }

    @Test
    void staleSaveLeavesNoPartialVersionAndCanRetryAfterReload() throws Exception {
        String id = create("stale-" + UUID.randomUUID()).path("id").asText();
        var old = repository.find("demo", UUID.fromString(id)).orElseThrow();
        repository.update(old.revise("先保存", old.rules(), 1, "admin", Instant.now()), 1);
        assertThatThrownBy(() -> repository.update(old.revise("后到的并发请求", old.rules(), 1, "admin", Instant.now()), 1))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("CONCURRENCY_CONFLICT");
        String requestKey = UUID.randomUUID().toString();
        mvc.perform(write(put(PATH + "/" + id), "admin", requestKey, Map.of("name", "旧请求", "rules", rules("UTC", "09:00"), "expectedRevision", 1)))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_calendar_version WHERE calendar_id=?", Integer.class, id)).isEqualTo(2);
        mvc.perform(write(put(PATH + "/" + id), "admin", requestKey, Map.of("name", "核对后保存", "rules", rules("UTC", "09:00"), "expectedRevision", 2)))
                .andExpect(status().isOk()).andExpect(jsonPath("revision").value(3));
    }

    @Test
    void historyInsertFailureRollsBackHeadRevisionAndMetadata() throws Exception {
        String id = create("atomic-" + UUID.randomUUID()).path("id").asText();
        var old = repository.find("demo", UUID.fromString(id)).orElseThrow();
        var malformed = new BusinessCalendar(old.id(), old.tenantId(), old.key(), "不可半保存", 1, old.rules(), "admin", Instant.now());
        assertThatThrownBy(() -> repository.update(malformed, 1)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(repository.find("demo", old.id()).orElseThrow()).isEqualTo(old);
    }

    @Test
    void administratorsCannotReadUpdateCalculateOrListAnotherTenantsCalendar() throws Exception {
        var foreign = BusinessCalendar.create("other", "foreign", "外部日历", domainRules(), "admin", Instant.now()); repository.create(foreign);
        String id = foreign.id().toString();
        for (String path : List.of(PATH + "/" + id, PATH + "/" + id + "/versions", PATH + "/" + id + "/versions/1")) {
            mvc.perform(get(path).header("Authorization", token("admin"))).andExpect(status().isNotFound());
        }
        mvc.perform(write(put(PATH + "/" + id), "admin", UUID.randomUUID().toString(), Map.of("name", "越权", "rules", rules("UTC", "09:00"), "expectedRevision", 1))).andExpect(status().isNotFound());
        mvc.perform(post(PATH + "/" + id + "/calculate").header("Authorization", token("admin")).contentType("application/json").content(json.write(Map.of("revision", 1, "startLocal", "2026-09-21T09:00:00", "workingMinutes", 30))))
                .andExpect(status().isNotFound());
        assertThat(read(PATH + "?limit=100").toString()).doesNotContain(id);
        mvc.perform(get(PATH).param("tenantId", "other").header("Authorization", token("admin"))).andExpect(status().isBadRequest());
    }

    @Test
    void employeesCannotManageOrReplayAnAdministratorsSuccessfulRequest() throws Exception {
        String key = UUID.randomUUID().toString();var body = Map.of("key", "roles-" + UUID.randomUUID(), "name", "权限日历", "rules", rules("UTC", "09:00"));
        var result = mvc.perform(write(post(PATH), "admin", key, body)).andExpect(status().isCreated()).andReturn();
        String id = tree(result.getResponse().getContentAsString()).path("id").asText();
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        for (String path : List.of(PATH, PATH + "/" + id, PATH + "/" + id + "/versions/1")) mvc.perform(get(path).header("Authorization", token("alice"))).andExpect(status().isForbidden());
        mvc.perform(write(post(PATH), "alice", key, body)).andExpect(status().isForbidden());
        mvc.perform(post(PATH + "/" + id + "/calculate").header("Authorization", token("alice")).contentType("application/json").content(json.write(Map.of("revision", 1, "startLocal", "2026-09-21T09:00:00", "workingMinutes", 30))))
                .andExpect(status().isForbidden());
    }

    @Test
    void directoryPagesUseStableKeysWithoutDuplicatingRows() throws Exception {
        for (int i = 0; i < 3; i++) create("paging-" + i + "-" + UUID.randomUUID());
        String path = PATH + "?limit=2";var seen = new HashSet<String>();
        while (true) {
            JsonNode page = read(path);page.path("items").forEach(item -> assertThat(seen.add(item.path("id").asText())).isTrue());
            if (!page.hasNonNull("nextAfterKey")) break;
            path = PATH + "?limit=2&afterKey=" + page.path("nextAfterKey").asText();
        }
        assertThat(seen.size()).isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM business_calendar WHERE tenant_id='demo'", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"limit=0", "limit=101", "limit=bad", "afterKey=", "tenantId=other"})
    void unsupportedOrMalformedDirectoryFiltersFailFast(String input) throws Exception {
        String[] pair = input.split("=", -1);
        mvc.perform(get(PATH).param(pair[0], pair[1]).header("Authorization", token("admin"))).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_CALENDAR_QUERY"));
    }

    @Test
    void invalidRulesAndCalculationDoNotWritePartialCalendarRows() throws Exception {
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM business_calendar", Integer.class);
        mvc.perform(write(post(PATH), "admin", UUID.randomUUID().toString(), Map.of("key", "bad-" + UUID.randomUUID(), "name", "无效", "rules", rules("Unknown/Zone", "09:00"))))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("code").value("INVALID_CALENDAR_RULES"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_calendar", Integer.class)).isEqualTo(before);
        String id = create("invalid-calc-" + UUID.randomUUID()).path("id").asText();
        mvc.perform(post(PATH + "/" + id + "/calculate").header("Authorization", token("admin")).contentType("application/json").content(json.write(Map.of("revision", 1, "startLocal", "2026-09-21T09:00:00", "workingMinutes", 0))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_CALENDAR_CALCULATION"));
    }

    private JsonNode create(String key) throws Exception {
        var result = mvc.perform(write(post(PATH), "admin", UUID.randomUUID().toString(), Map.of("key", key, "name", "日历测试", "rules", rules("UTC", "09:00"))))
                .andExpect(status().isCreated()).andReturn(); return tree(result.getResponse().getContentAsString());
    }
    private JsonNode calculate(String id, long revision) throws Exception {
        var result = mvc.perform(post(PATH + "/" + id + "/calculate").header("Authorization", token("admin")).contentType("application/json").content(json.write(Map.of("revision", revision, "startLocal", "2026-09-21T08:00:00", "workingMinutes", 30))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn();return tree(result.getResponse().getContentAsString());
    }
    private JsonNode read(String path) throws Exception { return tree(mvc.perform(get(path).header("Authorization", token("admin"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()); }
    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, String user, String key, Object body) { return request.header("Authorization", token(user)).header("Idempotency-Key", key).contentType("application/json").content(json.write(body)); }
    private JsonNode tree(String body) { return json.read(body, JsonNode.class); }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private Map<String, Object> rules(String zone, String start) { return Map.of("zoneId", zone, "weeklyHours", Map.of("MONDAY", List.of(Map.of("start", start, "end", "18:00"))), "overrides", List.of()); }
    private CalendarRules domainRules() { return new CalendarRules("UTC", Map.of(DayOfWeek.MONDAY, List.of(new CalendarRules.Period("09:00", "18:00"))), List.of()); }
}
