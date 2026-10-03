package io.agentflow.agent;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 非空 V98 升级只增加草稿建议存储，原申请、摘要和发送授权保持不变。
 * @author owlzhangfq@gmail.com
 */
class DraftAssistMigrationTest {
    @Test void preservesExistingDataAndConstrainsTenantAndState() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_DRAFT_MIGRATION_URL",
                "jdbc:h2:mem:draft-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_DRAFT_TEST_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_DRAFT_TEST_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("98").load().migrate(); var jdbc = new JdbcTemplate(source);
        String app = UUID.randomUUID().toString(), previousRun = UUID.randomUUID().toString(), run = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version) VALUES(?,'retained','BEFORE-99','legacy',1,'alice','原申请','{}','IN_APPROVAL',1,2)", app);
        jdbc.update("INSERT INTO agent_assist_run VALUES(?,'retained',?,2,1,'QUEUED',1,'{}','{}',CURRENT_TIMESTAMP)", previousRun, app);
        jdbc.update("INSERT INTO agent_assist_transition VALUES('retained',?,1,'QUEUED','{}')", previousRun);
        jdbc.update("INSERT INTO agent_assist_job(tenant_id,run_id,task_id,requester_json,sources_json,target_digest) VALUES('retained',?,'old-task','{}','[]',?)", previousRun, "a".repeat(64));
        var tables = List.of("approval_application", "agent_assist_run", "agent_assist_transition", "agent_assist_job");
        var previous = tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table)).toList();
        var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        var migration = Flyway.configure().dataSource(source).target("99").load();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table)).toList()).usingRecursiveComparison().isEqualTo(previous);
        assertThat(jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"").subList(0, history.size())).isEqualTo(history);
        assertThat(jdbc.queryForList("SELECT * FROM agent_draft_assist_run")).isEmpty();
        String insert = "INSERT INTO agent_draft_assist_run(id,tenant_id,application_id,application_version,status,version,context_json,state_json,created_at) VALUES(?,?,?,1,?,?,'{}','{}',CURRENT_TIMESTAMP)";
        assertThatThrownBy(() -> jdbc.update(insert, run, "foreign", app, "QUEUED", 1)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, run, "retained", app, "QUEUED", 2)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, run, "retained", app, "QUEUED", 1);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO agent_draft_assist_transition VALUES('foreign',?,1,'QUEUED','{}')", run)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO agent_draft_assist_transition VALUES('retained',?,1,'QUEUED','{}')", run);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO agent_draft_assist_transition VALUES('retained',?,1,'QUEUED','{}')", run)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(migration.migrate().migrationsExecuted).isZero();
        assertThat(migration.validateWithResult().validationSuccessful).isTrue();
    }
}
