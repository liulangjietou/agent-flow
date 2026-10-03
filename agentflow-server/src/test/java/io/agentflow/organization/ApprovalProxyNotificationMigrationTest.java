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
 * 非空 V96 升级保留原通知和代理，不给历史消息建立新来源；新关联受租户与类别约束。
 * @author owlzhangfq@gmail.com
 */
class ApprovalProxyNotificationMigrationTest {
    @Test void migrationPreservesOriginalRowsAndRejectsCrossTenantOrDuplicateSources() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_PROXY_NOTIFICATION_MIGRATION_URL",
                "jdbc:h2:mem:proxy-notification-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_PROXY_TEST_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_PROXY_TEST_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("96").load().migrate(); var jdbc = new JdbcTemplate(source);
        String definition = UUID.randomUUID().toString(), principal = UUID.randomUUID().toString(), substitute = UUID.randomUUID().toString();
        String proxy = UUID.randomUUID().toString(), inbox = UUID.randomUUID().toString(), foreign = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO organization_directory VALUES ('original',1,'admin',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO approval_definition(id,tenant_id,process_key,name,version,revision,status,graph_json,start_enabled) VALUES (?,'original','retained','保留版本',1,2,'PUBLISHED','{}',FALSE)", definition);
        jdbc.update("INSERT INTO organization_person VALUES ('original',?,'principal','原审批人',TRUE,TRUE,1)", principal);
        jdbc.update("INSERT INTO organization_person VALUES ('original',?,'substitute','代理人',TRUE,TRUE,1)", substitute);
        jdbc.update("""
                INSERT INTO organization_approval_proxy(tenant_id,id,definition_id,principal_id,substitute_id,starts_at,ends_at,reason,created_by,created_at,revision)
                VALUES ('original',?,?,?,?,'2030-01-01 00:00:00+00','2030-01-02 00:00:00+00','保留代理','admin',CURRENT_TIMESTAMP,1)
                """, proxy, definition, principal, substitute);
        for (String tenant : List.of("original", "foreign")) jdbc.update("""
                INSERT INTO notification_inbox(id,tenant_id,recipient_id,event_key,application_id,title,business_no,kind,actor_id,task_id,round_no,created_at,content)
                VALUES (?,?,'substitute','old-event',?,'旧通知','OLD','TASK_PENDING','principal','retained-task',1,CURRENT_TIMESTAMP,'原文保留')
                """, tenant.equals("original") ? inbox : foreign, tenant, UUID.randomUUID().toString());
        var tables = List.of("organization_directory", "organization_person", "approval_definition", "organization_approval_proxy", "notification_inbox");
        var before = tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table)).toList();
        var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        var migration = Flyway.configure().dataSource(source).target("97").load(); assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table)).toList()).usingRecursiveComparison().isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"").subList(0, history.size())).isEqualTo(history);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_proxy_notification", Integer.class)).isZero();
        String insert = "INSERT INTO approval_proxy_notification(tenant_id,proxy_id,task_id,kind,inbox_id) VALUES ('original',?,'retained-task',?,?)";
        assertThatThrownBy(() -> jdbc.update(insert, proxy, "TASK_PENDING", foreign)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID().toString(), "TASK_PENDING", inbox)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, proxy, "APPLICATION_APPROVED", inbox)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, proxy, "TASK_PENDING", inbox);
        assertThatThrownBy(() -> jdbc.update(insert, proxy, "TASK_PENDING", inbox)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(migration.migrate().migrationsExecuted).isZero(); assertThat(migration.validateWithResult().validationSuccessful).isTrue();
    }
}
