package io.agentflow.organization;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 非空 V97 来源升级后保持原消息和待办去重，生命周期按真实版本独立防重且保留租户外键。
 * @author owlzhangfq@gmail.com
 */
class ApprovalProxyLifecycleMigrationTest {
    @Test void migrationKeepsOriginalSourcesAndSeparatesRepeatedLifecycleVersions() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_PROXY_NOTIFICATION_MIGRATION_URL",
                "jdbc:h2:mem:proxy-lifecycle-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_PROXY_TEST_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_PROXY_TEST_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("97").load().migrate(); var jdbc = new JdbcTemplate(source);
        String definition = id(), principal = id(), substitute = id(), proxy = id(), pending = id(), foreign = id();
        jdbc.update("INSERT INTO organization_directory VALUES ('original',1,'admin',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO approval_definition(id,tenant_id,process_key,name,version,revision,status,graph_json,start_enabled) VALUES (?,'original','retained','原版本',1,2,'PUBLISHED','{}',FALSE)", definition);
        jdbc.update("INSERT INTO organization_person VALUES ('original',?,'principal','原审批人',TRUE,TRUE,1)", principal);
        jdbc.update("INSERT INTO organization_person VALUES ('original',?,'substitute','代理人',TRUE,TRUE,1)", substitute);
        jdbc.update("""
                INSERT INTO organization_approval_proxy(tenant_id,id,definition_id,principal_id,substitute_id,starts_at,ends_at,reason,created_by,created_at,revision)
                VALUES ('original',?,?,?,?,'2030-01-01 00:00:00+00','2030-01-02 00:00:00+00','原授权','admin',CURRENT_TIMESTAMP,1)
                """, proxy, definition, principal, substitute);
        message(jdbc, pending, "original", "TASK_PENDING"); message(jdbc, foreign, "foreign", "TASK_PENDING");
        jdbc.update("INSERT INTO approval_proxy_notification(tenant_id,proxy_id,task_id,kind,inbox_id) VALUES ('original',?,'retained-task','TASK_PENDING',?)", proxy, pending);
        var tables = List.of("organization_directory", "organization_person", "approval_definition", "organization_approval_proxy", "notification_inbox");
        var before = tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table)).toList();
        String oldColumns = "SELECT tenant_id,proxy_id,task_id,kind,inbox_id FROM approval_proxy_notification";
        var previousSource = jdbc.queryForList(oldColumns);
        var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        var migration = Flyway.configure().dataSource(source).target("98").load();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table)).toList()).usingRecursiveComparison().isEqualTo(before);
        assertThat(jdbc.queryForList(oldColumns)).isEqualTo(previousSource);
        assertThat(jdbc.queryForObject("SELECT event_version FROM approval_proxy_notification", Long.class)).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"").subList(0, history.size())).isEqualTo(history);
        String insert = "INSERT INTO approval_proxy_notification(tenant_id,proxy_id,task_id,kind,inbox_id,event_version) VALUES ('original',?,'retained-task',?,?,?)";
        String first = id(), second = id(), duplicate = id();
        for (String value : List.of(first, second, duplicate)) message(jdbc, value, "original", "APPLICATION_PAUSED");
        assertThatThrownBy(() -> jdbc.update(insert, proxy, "APPLICATION_PAUSED", first, 0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, proxy, "TASK_PENDING", first, 3)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, proxy, "APPLICATION_APPROVED", first, 3)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, proxy, "APPLICATION_PAUSED", foreign, 3)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, id(), "APPLICATION_PAUSED", first, 3)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, proxy, "APPLICATION_PAUSED", first, 3);
        assertThatThrownBy(() -> jdbc.update(insert, proxy, "APPLICATION_PAUSED", duplicate, 3)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, proxy, "APPLICATION_PAUSED", second, 5);
        assertThatThrownBy(() -> jdbc.update(insert, proxy, "APPLICATION_RESUMED", first, 6)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, proxy, "TASK_PENDING", duplicate, 0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_proxy_notification", Integer.class)).isEqualTo(3);
        assertThat(migration.migrate().migrationsExecuted).isZero(); assertThat(migration.validateWithResult().validationSuccessful).isTrue();
    }

    private static void message(JdbcTemplate jdbc, String id, String tenant, String kind) {
        jdbc.update("""
                INSERT INTO notification_inbox(id,tenant_id,recipient_id,event_key,application_id,title,business_no,kind,actor_id,task_id,round_no,created_at,content)
                VALUES (?,?,'substitute',?,?,'原通知','',?,'system:approval-proxy','retained-task',1,CURRENT_TIMESTAMP,'原文保留')
                """, id, tenant, id, id(), kind);
    }
    private static String id() { return UUID.randomUUID().toString(); }
}
