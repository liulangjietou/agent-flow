package io.agentflow.calendar;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日历迁移不补造企业制度、不改写旧审批与协作数据。
 * @author owlzhangfq@gmail.com
 */
class BusinessCalendarMigrationTest {
    @Test
    void migrationPreservesApplicationsCommentsAndPreviousMigrations() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:calendar-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).target("13").load().migrate();
        var jdbc = new JdbcTemplate(source);
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO approval_application (id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version)
                VALUES (?, 'demo', 'OLD-CALENDAR', 'legacy', 1, 'alice', '旧申请', '{"note":"原内容"}', 'IN_APPROVAL', 1, 2)
                """, id);
        jdbc.update("""
                INSERT INTO application_comment (id,tenant_id,application_id,author_id,content,round_no,application_version,application_status,created_at)
                VALUES (?, 'demo', ?, 'alice', '原评论', 1, 2, 'IN_APPROVAL', CURRENT_TIMESTAMP)
                """, UUID.randomUUID().toString(), id);
        var application = jdbc.queryForList("SELECT * FROM approval_application");
        var comments = jdbc.queryForList("SELECT * FROM application_comment");
        var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        assertThat(Flyway.configure().dataSource(source).target("14").load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM approval_application")).isEqualTo(application);
        assertThat(jdbc.queryForList("SELECT * FROM application_comment")).isEqualTo(comments);
        assertThat(jdbc.queryForList("SELECT * FROM business_calendar")).isEmpty();
        assertThat(jdbc.queryForList("SELECT * FROM business_calendar_version")).isEmpty();
        assertThat(jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" WHERE \"version\" IS NULL OR \"version\"<>'14' ORDER BY \"installed_rank\""))
                .isEqualTo(history);
        assertThat(Flyway.configure().dataSource(source).target("14").load().migrate().migrationsExecuted).isZero();
    }
}
