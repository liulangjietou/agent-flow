package io.agentflow.approval.comment;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V13 只新增评论表，不能把旧审批意见伪装成协作评论或改写旧申请。
 * @author owlzhangfq@gmail.com
 */
class CommentMigrationTest {
    @Test
    void migrationPreservesApplicationsAndExistingMigrationHistory() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:comment-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).target("12").load().migrate();
        var jdbc = new JdbcTemplate(source);
        jdbc.update("""
                INSERT INTO approval_application (id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version)
                VALUES (?, 'demo', 'OLD-COMMENT', 'legacy', 1, 'alice', '旧申请', '{"note":"原内容"}', 'APPROVED', 1, 3)
                """, UUID.randomUUID().toString());
        var application = jdbc.queryForList("SELECT * FROM approval_application");
        var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        assertThat(Flyway.configure().dataSource(source).target("13").load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM approval_application")).isEqualTo(application);
        assertThat(jdbc.queryForList("SELECT * FROM application_comment")).isEmpty();
        assertThat(jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" WHERE \"version\" IS NULL OR \"version\"<>'13' ORDER BY \"installed_rank\""))
                .isEqualTo(history);
        assertThat(Flyway.configure().dataSource(source).target("13").load().migrate().migrationsExecuted).isZero();
    }
}
