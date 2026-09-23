package io.agentflow.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.JsonUtil;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V9 升级只索引可信的操作人事实，保留旧业务内容及全部原审计列。
 * @author owlzhangfq@gmail.com
 */
class WorkspaceMigrationTest {
    @Test
    void indexesOnlyExplicitSameTenantFactsWithoutRewritingOldAudit() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:workspace-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).target("9").load().migrate();
        var jdbc = new JdbcTemplate(source);
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO approval_application (id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version)
                VALUES (?, 'demo', 'OLD-WORKSPACE', 'legacy', 1, 'alice', '旧申请', '{"secret":"原内容"}', 'APPROVED', 1, 3)
                """, id);
        var json = new JsonUtil(new ObjectMapper());
        List<String> reliable = new ArrayList<>();
        for (String action : List.of("APPROVE", "RETURN", "REJECT", "TRANSFER", "DELEGATE")) {
            reliable.add(audit(jdbc, "demo", id, action, json.write(Map.of("actor", "manager", "applicationId", id, "action", action))));
        }
        audit(jdbc, "demo", id, "APPROVE", "malformed");
        audit(jdbc, "demo", id, "APPROVE", "null");
        audit(jdbc, "demo", id, "APPROVE", json.write(Map.of("applicationId", id, "action", "APPROVE")));
        audit(jdbc, "demo", id, "APPROVE", json.write(Map.of("actor", " ", "applicationId", id, "action", "APPROVE")));
        audit(jdbc, "demo", id, "APPROVE", json.write(Map.of("actor", "m".repeat(129), "applicationId", id, "action", "APPROVE")));
        audit(jdbc, "other", id, "APPROVE", json.write(Map.of("actor", "manager", "applicationId", id, "action", "APPROVE")));
        audit(jdbc, "demo", id, "APPROVE", json.write(Map.of("actor", "manager", "applicationId", UUID.randomUUID().toString(), "action", "APPROVE")));
        audit(jdbc, "demo", id, "APPROVE", json.write(Map.of("actor", "manager", "applicationId", id, "action", "RETURN")));
        audit(jdbc, "demo", id, "CLAIM", json.write(Map.of("actor", "manager", "applicationId", id, "action", "CLAIM")));
        audit(jdbc, "demo", id, null, json.write(Map.of("actor", "manager", "applicationId", id, "action", "APPROVE")));
        audit(jdbc, "demo", null, "APPROVE", json.write(Map.of("actor", "manager", "applicationId", id, "action", "APPROVE")));
        var applicationBefore = jdbc.queryForList("SELECT * FROM approval_application");
        var auditsBefore = jdbc.queryForList("SELECT * FROM audit_event ORDER BY id");
        var migrationsBefore = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        Flyway.configure().dataSource(source).target("10").load().migrate();
        assertThat(jdbc.queryForList("SELECT * FROM approval_application")).isEqualTo(applicationBefore);
        var auditsAfter = jdbc.queryForList("SELECT * FROM audit_event ORDER BY id");
        auditsAfter.forEach(row -> row.remove("ACTOR_ID"));
        assertThat(auditsAfter).isEqualTo(auditsBefore);
        assertThat(jdbc.queryForList("SELECT id FROM audit_event WHERE actor_id='manager'", String.class)).containsExactlyInAnyOrderElementsOf(reliable);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE actor_id IS NOT NULL", Integer.class)).isEqualTo(reliable.size());
        assertThat(jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" WHERE (\"version\" IS NULL OR \"version\"<>'10') ORDER BY \"installed_rank\""))
                .isEqualTo(migrationsBefore);
        assertThat(Flyway.configure().dataSource(source).target("10").load().migrate().migrationsExecuted).isZero();
    }

    private static String audit(JdbcTemplate jdbc, String tenant, String applicationId, String action, String payload) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO audit_event (id,tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,payload_json,application_id,action,occurred_at)
                VALUES (?,?,?,'Task','old-task',3,?,?,?,TIMESTAMP '2026-01-01 01:02:03')
                """, id, tenant, UUID.randomUUID().toString(), payload, applicationId, action);
        return id;
    }
}
