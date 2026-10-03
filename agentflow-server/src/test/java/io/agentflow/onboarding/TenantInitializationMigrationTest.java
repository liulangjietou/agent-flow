package io.agentflow.onboarding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V95 只新增初始化事实表；已有组织、日历、通知及业务数据不补造完成记录。
 * @author owlzhangfq@gmail.com
 */
class TenantInitializationMigrationTest {
    @Test void upgradePreservesAllOldTablesAndDoesNotInitializeExistingTenants() {
        String url = System.getenv().getOrDefault("AGENTFLOW_INITIALIZATION_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var source = new DriverManagerDataSource(url,
                System.getenv().getOrDefault("AGENTFLOW_INITIALIZATION_MIGRATION_USER", "sa"),
                System.getenv().getOrDefault("AGENTFLOW_INITIALIZATION_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("94").load().migrate();
        var jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO organization_directory(tenant_id,revision,initialized_by,initialized_at) VALUES ('retained',2,'old-admin',CURRENT_TIMESTAMP)");
        String person = UUID.randomUUID().toString(), calendar = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO organization_person(tenant_id,id,subject,display_name,active,approval_eligible,revision) VALUES ('retained',?,'old-admin','原管理员',TRUE,FALSE,1)", person);
        jdbc.update("INSERT INTO business_calendar(id,tenant_id,calendar_key,name,zone_id,revision,updated_by,updated_at) VALUES (?,'retained','old-work','原工作日历','Asia/Shanghai',1,'old-admin',CURRENT_TIMESTAMP)", calendar);
        jdbc.update("""
                INSERT INTO business_calendar_version(tenant_id,calendar_id,calendar_key,name,zone_id,revision,rules_json,updated_by,updated_at)
                VALUES ('retained',?,'old-work','原工作日历','Asia/Shanghai',1,'{"zoneId":"Asia/Shanghai","weeklyHours":{"MONDAY":[{"start":"09:00","end":"18:00"}]},"overrides":[]}','old-admin',CURRENT_TIMESTAMP)
                """, calendar);
        jdbc.update("""
                INSERT INTO notification_preferences(tenant_id,recipient_id,email_enabled,enterprise_im_enabled,version,email_generation,enterprise_im_generation,updated_at)
                VALUES ('retained','old-admin',TRUE,FALSE,1,1,0,CURRENT_TIMESTAMP)
                """);
        String app = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version)
                VALUES (?,'retained','OLD-INIT','legacy',1,'old-admin','原在审申请','{}','IN_APPROVAL',1,2)
                """, app);
        var tables = jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=? AND table_type='BASE TABLE' ORDER BY table_name",
                String.class, url.startsWith("jdbc:postgresql:") ? "public" : "PUBLIC");
        tables = tables.stream().filter(name -> !name.equalsIgnoreCase("flyway_schema_history")).toList();
        var before = snapshots(jdbc, tables);
        var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        assertThat(Flyway.configure().dataSource(source).target("95").load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(snapshots(jdbc, tables)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenant_initialization", Long.class)).isZero();
        var upgradedHistory = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        assertThat(upgradedHistory).hasSize(history.size() + 1);
        assertThat(upgradedHistory.subList(0, history.size())).isEqualTo(history);
        assertThat(Flyway.configure().dataSource(source).target("95").load().migrate().migrationsExecuted).isZero();
    }

    private Map<String, List<List<String>>> snapshots(JdbcTemplate jdbc, List<String> tables) {
        var result = new LinkedHashMap<String, List<List<String>>>();
        for (String table : tables) {
            var rows = jdbc.query("SELECT * FROM \"" + table + "\"", (row, index) -> {
                List<String> values = new ArrayList<>();
                for (int column = 1; column <= row.getMetaData().getColumnCount(); column++) values.add(row.getString(column));
                return values;
            });
            result.put(table, rows.stream().sorted(java.util.Comparator.comparing(Object::toString)).toList());
        }
        return result;
    }
}
