package io.agentflow.approval.comment;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;

/** 旧评论及旧消息保留原事实，升级只给旧评论补空提醒名单。
 * @author owlzhangfq@gmail.com
 */
class CommentMentionMigrationTest {
    @Test void existingCommentsAndNotificationsKeepTheirOriginalData() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:comment-mention-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).target("90").load().migrate();
        var jdbc = new JdbcTemplate(source);
        String app = UUID.randomUUID().toString(), comment = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO approval_application (id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version)
                VALUES (?, 'demo', 'OLD-MENTION', 'legacy', 1, 'alice', '旧申请', '{}', 'IN_APPROVAL', 1, 2)
                """, app);
        jdbc.update("""
                INSERT INTO application_comment(id,tenant_id,application_id,author_id,content,round_no,application_version,application_status,created_at)
                VALUES (?,'demo',?,'alice','原评论 @finance',1,2,'IN_APPROVAL',CURRENT_TIMESTAMP)
                """, comment, app);
        var oldComment = jdbc.queryForMap("SELECT * FROM application_comment WHERE id=?", comment);
        var oldApplication = jdbc.queryForMap("SELECT * FROM approval_application WHERE id=?", app);
        var oldMessages = jdbc.queryForList("SELECT * FROM notification_inbox");
        assertThat(Flyway.configure().dataSource(source).target("91").load().migrate().migrationsExecuted).isEqualTo(1);
        var upgraded = jdbc.queryForMap("SELECT * FROM application_comment WHERE id=?", comment);
        assertThat(upgraded.remove("MENTIONS_JSON")).isEqualTo("[]");
        assertThat(upgraded).isEqualTo(oldComment);
        assertThat(jdbc.queryForMap("SELECT * FROM approval_application WHERE id=?", app)).isEqualTo(oldApplication);
        assertThat(jdbc.queryForList("SELECT * FROM notification_inbox")).isEqualTo(oldMessages);
        assertThat(Flyway.configure().dataSource(source).target("91").load().migrate().migrationsExecuted).isZero();
    }
}
