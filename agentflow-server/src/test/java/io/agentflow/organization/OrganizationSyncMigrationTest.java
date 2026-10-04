package io.agentflow.organization;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/**
 * V108 非空组织和审批事实升级后保持原样，同步来源必须受租户目录约束。
 * @author owlzhangfq@gmail.com
 */
class OrganizationSyncMigrationTest {
    @Test void addsOnlyEmptySyncTablesAndRetainsOriginalRowsAndMigrationHistory() {
        var data = new DriverManagerDataSource("jdbc:h2:mem:sync-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(data).target("108").load().migrate(); var jdbc = new JdbcTemplate(data);
        try {
            String person = UUID.randomUUID().toString(), application = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO organization_directory VALUES('existing',5,'original-admin',CURRENT_TIMESTAMP)");
            jdbc.update("INSERT INTO organization_person VALUES('existing',?,'original-subject','原人员',TRUE,TRUE,3)", person);
            jdbc.update("INSERT INTO organization_change VALUES('existing',5,'original-admin','PERSON',?,'{\"kept\":true}',CURRENT_TIMESTAMP)", person);
            jdbc.update("""
                    INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version)
                    VALUES(?,'existing','SYNC-OLD','legacy',1,'original-subject','原审批','{}','IN_APPROVAL',1,2)
                    """, application);
            jdbc.update("""
                    INSERT INTO approval_submission_round(tenant_id,application_id,round_no,process_instance_id,definition_version,title,payload_json,submitted_by,submitted_at,status)
                    VALUES('existing',?,1,'original-instance',1,'原轮次','{}','original-subject',CURRENT_TIMESTAMP,'IN_APPROVAL')
                    """, application);
            var tables = List.of("organization_directory", "organization_person", "organization_change", "approval_application", "approval_submission_round");
            var before = tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table)).toList();
            var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
            assertThat(Flyway.configure().dataSource(data).target("109").load().migrate().migrationsExecuted).isEqualTo(1);
            assertThat(tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table)).toList()).usingRecursiveComparison().isEqualTo(before);
            var after = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
            assertThat(after).hasSize(history.size() + 1); assertThat(after.subList(0, history.size())).isEqualTo(history);
            for (String table : List.of("source", "batch", "transition", "binding", "binding_change")) {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM organization_sync_" + table, Long.class)).isZero();
            }
            assertThat(Flyway.configure().dataSource(data).target("109").load().migrate().migrationsExecuted).isZero();
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO organization_sync_source(tenant_id,source_key,applied_revision,version,registered_by,registered_at)
                    VALUES('absent','hr',0,1,'admin',CURRENT_TIMESTAMP)
                    """)).isInstanceOf(DataIntegrityViolationException.class);
        } finally { jdbc.execute("SHUTDOWN"); }
    }
}
