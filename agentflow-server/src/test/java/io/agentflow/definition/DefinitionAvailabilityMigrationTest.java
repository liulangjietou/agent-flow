package io.agentflow.definition;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 旧版本迁移保持原发布内容与修订，不伪造治理历史。
 * @author owlzhangfq@gmail.com
 */
class DefinitionAvailabilityMigrationTest {
    @Test
    void upgradesCurrentV24WithoutChangingNotificationsSharedSessionsOrLogoutOrdering() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).target("24").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        String definitionId = UUID.randomUUID().toString(), applicationId = UUID.randomUUID().toString();
        String notificationTexts = "{\"submitted\":\"已收到申请\",\"returned\":\"请补充说明\",\"approved\":\"已完成审核\"}";
        jdbc.update("""
                INSERT INTO approval_definition(id,tenant_id,process_key,name,version,revision,status,graph_json,notification_texts_json)
                VALUES(?,'demo','old-notice','通知流程',1,1,'PUBLISHED','{"nodes":[],"edges":[]}',?)
                """, definitionId, notificationTexts);
        jdbc.update("""
                INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,notification_texts_json)
                VALUES(?,'demo','OLD-NOTICE','old-notice',1,'alice','旧申请','{}','APPROVED',1,3,?)
                """, applicationId, notificationTexts);
        jdbc.update("""
                INSERT INTO notification_inbox(id,tenant_id,recipient_id,event_key,application_id,title,business_no,kind,actor_id,round_no,created_at,content)
                VALUES(?,'demo','alice','old-event',?,'旧申请','OLD-NOTICE','APPLICATION_APPROVED','manager',1,CURRENT_TIMESTAMP,'已完成审核')
                """, UUID.randomUUID().toString(), applicationId);
        var originalDefinition = jdbc.queryForMap("SELECT * FROM approval_definition WHERE id=?", definitionId);
        String sessionId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO AF_HTTP_SESSION VALUES (?,?,?,?,?,?,?)",
                sessionId, UUID.randomUUID().toString(), 100L, 200L, 1800, 1800200L, "employee");
        jdbc.update("INSERT INTO AF_HTTP_SESSION_ATTRIBUTES VALUES (?,?,?)", sessionId, "identity", new byte[]{1, 2, 3});
        jdbc.update("UPDATE AF_OIDC_LOGOUT_ORDER SET LAST_ORDER=7 WHERE ID=1");
        jdbc.update("INSERT INTO AF_OIDC_LOGOUT_SCOPE VALUES (?,?,?)", "a".repeat(64), 200L, 7L);
        jdbc.update("INSERT INTO AF_OIDC_LOGOUT_EVENT VALUES (?)", "b".repeat(64));
        var tables = List.of("AF_HTTP_SESSION", "AF_HTTP_SESSION_ATTRIBUTES", "AF_OIDC_LOGOUT_ORDER",
                "AF_OIDC_LOGOUT_SCOPE", "AF_OIDC_LOGOUT_EVENT", "approval_application", "notification_inbox");
        var before = tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table)).toList();
        var migrations = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");

        assertThat(Flyway.configure().dataSource(dataSource).target("25").load().migrate().migrationsExecuted).isEqualTo(1);

        var migratedDefinition = jdbc.queryForMap("SELECT * FROM approval_definition WHERE id=?", definitionId);
        assertThat(migratedDefinition.remove("START_ENABLED")).isEqualTo(true);
        assertThat(migratedDefinition).isEqualTo(originalDefinition);
        assertThat(tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table)).toList())
                .usingRecursiveComparison().isEqualTo(before);
        var migrated = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        assertThat(migrated).hasSize(migrations.size() + 1);
        assertThat(migrated.subList(0, migrations.size())).isEqualTo(migrations);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM definition_availability_change", Integer.class)).isZero();
        assertThat(Flyway.configure().dataSource(dataSource).target("25").load().migrate().migrationsExecuted).isZero();
    }

    @Test
    void upgradesV20WithEnabledDefaultsAndNoInventedOperations() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).target("20").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_definition(id,tenant_id,process_key,name,version,revision,status,graph_json) VALUES(?,?,?,?,?,?,?,?)",
                id, "legacy", "leave", "历史版本", 2, 5, "PUBLISHED", "{\"nodes\":[],\"edges\":[]}");
        String snapshot = "SELECT id,tenant_id,process_key,name,version,revision,status,graph_json,created_at,updated_at FROM approval_definition WHERE id=?";
        var before = jdbc.queryForMap(snapshot, id);
        Flyway.configure().dataSource(dataSource).target("25").load().migrate();
        assertThat(jdbc.queryForMap(snapshot, id)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT start_enabled FROM approval_definition WHERE id=?", Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM definition_availability_change", Integer.class)).isZero();
    }
}
