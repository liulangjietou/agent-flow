package io.agentflow.event;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V87 只追加空事件白名单，旧申请、评论和迁移记录保持；H2/PostgreSQL 使用独立验收库。
 * @author owlzhangfq@gmail.com
 */
class EventContractMigrationTest {
    @Test
    void migrationPreservesExistingFactsAndDoesNotInventEventContracts() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_EVENT_CONTRACT_MIGRATION_URL", "jdbc:h2:mem:event-contract-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_EVENT_CONTRACT_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_EVENT_CONTRACT_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("86").load().migrate(); var jdbc = new JdbcTemplate(source);
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO approval_application (id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version)
                VALUES (?,'demo','OLD-EVENT-CONTRACT','legacy',1,'alice','旧申请','{"note":"原内容"}','IN_APPROVAL',1,2)
                """, id);
        jdbc.update("""
                INSERT INTO application_comment (id,tenant_id,application_id,author_id,content,round_no,application_version,application_status,created_at)
                VALUES (?,'demo',?,'alice','原评论',1,2,'IN_APPROVAL',CURRENT_TIMESTAMP)
                """, UUID.randomUUID().toString(), id);
        var applications = jdbc.queryForList("SELECT * FROM approval_application ORDER BY id");
        var comments = jdbc.queryForList("SELECT * FROM application_comment ORDER BY id");
        var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        assertThat(Flyway.configure().dataSource(source).target("87").load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM approval_application ORDER BY id")).isEqualTo(applications);
        assertThat(jdbc.queryForList("SELECT * FROM application_comment ORDER BY id")).isEqualTo(comments);
        assertThat(jdbc.queryForList("SELECT * FROM event_contract")).isEmpty();
        assertThat(jdbc.queryForList("SELECT * FROM event_contract_version")).isEmpty();
        assertThat(jdbc.queryForList("SELECT * FROM event_contract_availability_history")).isEmpty();
        assertThat(jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" WHERE \"version\" IS NULL OR \"version\"<>'87' ORDER BY \"installed_rank\""))
                .isEqualTo(history);
        assertThat(Flyway.configure().dataSource(source).target("87").load().migrate().migrationsExecuted).isZero();
    }
}
