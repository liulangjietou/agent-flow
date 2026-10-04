package io.agentflow.servicetask;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 非空 V105 升级只追加服务任务存储，旧申请、轮次、原件和财务事实逐表保持。
 * @author owlzhangfq@gmail.com
 */
class ServiceTaskMigrationTest {
    @Test
    void nonEmptyUpgradePreservesAllExistingTablesAndDoesNotInventHistoricalCommands() throws Exception {
        var source = new DriverManagerDataSource(
                System.getenv().getOrDefault("AGENTFLOW_SERVICE_TASK_MIGRATION_URL", "jdbc:h2:mem:service-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_SERVICE_TASK_MIGRATION_USER", "sa"),
                System.getenv().getOrDefault("AGENTFLOW_SERVICE_TASK_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("105").load().migrate();
        var jdbc = new JdbcTemplate(source);
        String application = UUID.randomUUID().toString(), attachment = UUID.randomUUID().toString();
        String invoice = UUID.randomUUID().toString(), original = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version)
                VALUES(?,'retained','SERVICE-UPGRADE','legacy',1,'alice','原在审申请','{"reason":"原内容"}','IN_APPROVAL',1,2)
                """, application);
        jdbc.update("""
                INSERT INTO approval_submission_round(tenant_id,application_id,round_no,process_instance_id,definition_version,title,payload_json,submitted_by,submitted_at,status)
                VALUES('retained',?,1,'original-instance',1,'原轮次','{"reason":"不可变原文"}','alice',CURRENT_TIMESTAMP,'IN_APPROVAL')
                """, application);
        jdbc.update("""
                INSERT INTO approval_attachment(id,tenant_id,application_id,field_path,filename,byte_size,sha256,created_by,created_at,status)
                VALUES(?,'retained',?,'proof','原件.pdf',10,?,'alice',CURRENT_TIMESTAMP,'READY')
                """, attachment, application, "a".repeat(64));
        jdbc.update("INSERT INTO finance_resource(tenant_id,resource_type,id,owner_id,source_reference,version,context_json,state_json) VALUES('retained','INVOICE',?,'alice',?,1,?,?)",
                invoice, original, "b".repeat(64), "{\"retained\":true}");
        jdbc.update("INSERT INTO invoice_wallet(tenant_id,owner_id) VALUES('retained','alice')");
        jdbc.update("INSERT INTO invoice_original(tenant_id,id,invoice_id,owner_id,filename,byte_size,sha256,format,status,created_at) VALUES('retained',?,?,'alice','旧发票.pdf',10,?,'PDF','READY',CURRENT_TIMESTAMP)",
                original, invoice, "b".repeat(64));
        var before = new LinkedHashMap<String, List<Map<String, Object>>>();
        try (var connection = source.getConnection();
             var tables = connection.getMetaData().getTables(null, connection.getSchema(), "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String table = tables.getString("TABLE_NAME");
                if (!table.equalsIgnoreCase("flyway_schema_history")) before.put(table, jdbc.queryForList("SELECT * FROM " + quote(table)));
            }
        }
        assertThat(before).hasSizeGreaterThan(100);
        assertThat(before.values().stream().mapToLong(List::size).sum()).isGreaterThanOrEqualTo(6);
        var oldFiles = jdbc.queryForList("SELECT * FROM stored_document_inventory");
        var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        var upgraded = Flyway.configure().dataSource(source).target("106").load();
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
        before.forEach((table, rows) -> assertThat(jdbc.queryForList("SELECT * FROM " + quote(table))).as(table).containsExactlyInAnyOrderElementsOf(rows));
        assertThat(jdbc.queryForList("SELECT * FROM stored_document_inventory")).containsExactlyInAnyOrderElementsOf(oldFiles);
        assertThat(jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" WHERE \"version\" IS NULL OR \"version\"<>'106' ORDER BY \"installed_rank\"")).isEqualTo(history);
        assertThat(jdbc.queryForList("SELECT id FROM service_task_catalog_lock", Integer.class)).containsExactly(1);
        for (String table : List.of("service_task_contract", "service_task_operation", "service_task_operation_revision")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).as(table).isZero();
        }
        assertThat(upgraded.migrate().migrationsExecuted).isZero();
        assertThat(upgraded.validateWithResult().validationSuccessful).isTrue();
    }

    private static String quote(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }
}
