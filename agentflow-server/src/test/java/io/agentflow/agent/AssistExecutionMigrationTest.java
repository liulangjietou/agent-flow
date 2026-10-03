package io.agentflow.agent;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V31 升级保留原摘要和轨迹，不把未授权的历史运行自动放入模型队列。
 * @author owlzhangfq@gmail.com
 */
class AssistExecutionMigrationTest {
    @Test
    void migrationPreservesHistoricalRunsWithoutInventingSendAuthorization() {
        var source = new DriverManagerDataSource(
                System.getenv().getOrDefault("AGENTFLOW_EXECUTION_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_EXECUTION_MIGRATION_USER", "sa"),
                System.getenv().getOrDefault("AGENTFLOW_EXECUTION_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("31").load().migrate();
        var jdbc = new JdbcTemplate(source);
        String app = UUID.randomUUID().toString(), run = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version) VALUES(?,'retained','BEFORE-32','legacy',1,'alice','原申请','{}','IN_APPROVAL',1,2)", app);
        jdbc.update("INSERT INTO agent_assist_run VALUES(?,'retained',?,2,1,'QUEUED',1,'{}','{}',CURRENT_TIMESTAMP)", run, app);
        jdbc.update("INSERT INTO agent_assist_transition VALUES('retained',?,1,'QUEUED','{}')", run);
        var runs = jdbc.queryForList("SELECT * FROM agent_assist_run");
        var transitions = jdbc.queryForList("SELECT * FROM agent_assist_transition");
        var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        var flyway = Flyway.configure().dataSource(source).target("32").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM agent_assist_run")).isEqualTo(runs);
        assertThat(jdbc.queryForList("SELECT * FROM agent_assist_transition")).isEqualTo(transitions);
        assertThat(jdbc.queryForList("SELECT * FROM agent_assist_job")).isEmpty();
        assertThat(jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"").subList(0, history.size())).isEqualTo(history);
        String insert = "INSERT INTO agent_assist_job(tenant_id,run_id,task_id,requester_json,sources_json,target_digest) VALUES(?,?,'task','{}','[]',?)";
        assertThatThrownBy(() -> jdbc.update(insert, "foreign", run, "a".repeat(64)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update(insert, "retained", run, "a".repeat(64));
        assertThatThrownBy(() -> jdbc.update(insert, "retained", run, "a".repeat(64)))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }
}
