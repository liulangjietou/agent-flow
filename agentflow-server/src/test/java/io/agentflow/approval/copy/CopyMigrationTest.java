package io.agentflow.approval.copy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V30 升级只新增抄送事实，不补造历史授权，并验证租户约束与节点收件唯一性。
 * @author owlzhangfq@gmail.com
 */
class CopyMigrationTest {
    @Test
    void migrationPreservesApplicationsAndRoundsWithoutGrantingHistoricalCopies() {
        var source = new DriverManagerDataSource(
                System.getenv().getOrDefault("AGENTFLOW_COPY_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_COPY_MIGRATION_USER", "sa"),
                System.getenv().getOrDefault("AGENTFLOW_COPY_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("30").load().migrate();
        var jdbc = new JdbcTemplate(source);
        String app = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version) VALUES(?,'retained','BEFORE-31','legacy',1,'alice','原标题','{}','IN_APPROVAL',1,2)", app);
        jdbc.update("INSERT INTO approval_submission_round(tenant_id,application_id,round_no,process_instance_id,definition_version,title,payload_json,submitted_by,submitted_at,status) VALUES('retained',?,1,'instance',1,'原轮次','{}','alice',CURRENT_TIMESTAMP,'IN_APPROVAL')", app);
        var before = jdbc.queryForList("SELECT * FROM approval_application");
        var rounds = jdbc.queryForList("SELECT * FROM approval_submission_round");
        var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        var flyway = Flyway.configure().dataSource(source).target("31").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM approval_application")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM approval_submission_round")).isEqualTo(rounds);
        assertThat(jdbc.queryForList("SELECT * FROM approval_copy_recipient")).isEmpty();
        assertThat(jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"").subList(0, history.size())).isEqualTo(history);
        String insert = "INSERT INTO approval_copy_recipient VALUES(?,?,1,'instance','copy','抄送','bob','user:bob',0,CURRENT_TIMESTAMP)";
        assertThatThrownBy(() -> jdbc.update(insert, "foreign", app)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update(insert, "retained", app);
        assertThatThrownBy(() -> jdbc.update(insert, "retained", app)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }
}
