package io.agentflow.approval.operations;

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
 * V16 仅补全可信的空操作人索引，保留原审计与旧迁移记录。
 * @author owlzhangfq@gmail.com
 */
class AuditSearchMigrationTest {
    @Test
    void indexesOnlyConsistentFactsAndPreservesEveryOriginalColumn() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:audit-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).target("15").load().migrate(); var jdbc = new JdbcTemplate(source);
        String app = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_application (id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version) VALUES (?,'demo','AUDIT-OLD','old',1,'alice','原内容','{}','DRAFT',1,1)", app);
        var json = new JsonUtil(new ObjectMapper()); var reliable = new ArrayList<String>();
        for (String action : List.of("CREATE", "REVISE", "SUBMIT", "WITHDRAW", "CANCEL", "CLAIM", "RELEASE", "TRANSFER", "DELEGATE", "RESOLVE", "RETURN", "REJECT", "APPROVE")) {
            String type = List.of("CREATE", "REVISE", "SUBMIT", "WITHDRAW", "CANCEL").contains(action) ? "Application" : "Task";
            reliable.add(event(jdbc, "demo", app, type, app, action, json.write(Map.of("actor", "alice", "applicationId", app, "action", action))));
        }
        // 无申请列的旧申请事件允许用原聚合标识关联；旧任务不根据正文猜测关联。
        reliable.add(event(jdbc, "demo", null, "Application", app, "CREATE", payload(json, "alice", app, "CREATE")));
        event(jdbc, "demo", null, "Task", app, "CLAIM", payload(json, "alice", app, "CLAIM"));
        for (String invalid : List.of("malformed", "null", "[]", "{}", payload(json, " ", app, "CREATE"), payload(json, "a".repeat(129), app, "CREATE"),
                payload(json, "a\nb", app, "CREATE"), payload(json, "alice", UUID.randomUUID().toString(), "CREATE"), payload(json, "alice", app, "SUBMIT"))) {
            event(jdbc, "demo", app, "Application", app, "CREATE", invalid);
        }
        event(jdbc, "other", app, "Application", app, "CREATE", payload(json, "alice", app, "CREATE"));
        event(jdbc, "demo", app, "Application", "mismatched", "CREATE", payload(json, "alice", app, "CREATE"));
        event(jdbc, "demo", app, "Application", app, null, payload(json, "alice", app, "CREATE"));
        event(jdbc, "demo", app, "Application", app, "APPROVE", payload(json, "alice", app, "APPROVE"));
        String existing = event(jdbc, "demo", app, "Application", app, "CREATE", payload(json, "alice", app, "CREATE"));
        jdbc.update("UPDATE audit_event SET actor_id='original-actor' WHERE id=?", existing);
        var apps = jdbc.queryForList("SELECT * FROM approval_application");
        var before = jdbc.queryForList("SELECT * FROM audit_event ORDER BY id"); before.forEach(row -> row.remove("ACTOR_ID"));
        var migrations = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        Flyway.configure().dataSource(source).target("16").load().migrate();
        assertThat(jdbc.queryForList("SELECT * FROM approval_application")).isEqualTo(apps);
        var after = jdbc.queryForList("SELECT * FROM audit_event ORDER BY id"); after.forEach(row -> row.remove("ACTOR_ID"));
        assertThat(after).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT id FROM audit_event WHERE actor_id='alice'", String.class)).containsExactlyInAnyOrderElementsOf(reliable);
        assertThat(jdbc.queryForObject("SELECT actor_id FROM audit_event WHERE id=?", String.class, existing)).isEqualTo("original-actor");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE actor_id IS NOT NULL", Integer.class)).isEqualTo(reliable.size() + 1);
        assertThat(jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" WHERE (\"version\" IS NULL OR \"version\"<>'16') ORDER BY \"installed_rank\"" )).isEqualTo(migrations);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE INDEX_NAME='IDX_AUDIT_TENANT_TIME'", Integer.class)).isEqualTo(1);
        assertThat(Flyway.configure().dataSource(source).target("16").load().migrate().migrationsExecuted).isZero();
    }
    private static String payload(JsonUtil json, String actor, String app, String action) { return json.write(Map.of("actor", actor, "applicationId", app, "action", action)); }
    private static String event(JdbcTemplate jdbc, String tenant, String app, String type, String aggregate, String action, String payload) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO audit_event (id,tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,application_id,action,payload_json,occurred_at) VALUES (?,?,?,?,?,3,?,?,?,TIMESTAMP '2020-01-01 01:02:03')",
                id, tenant, UUID.randomUUID().toString(), type, aggregate, app, action, payload);
        return id;
    }
}
