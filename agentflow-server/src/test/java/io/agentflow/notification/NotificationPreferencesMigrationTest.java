package io.agentflow.notification;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/** V92 不从历史消息推断同意，也不批量补造外发意向。
 * @author owlzhangfq@gmail.com
 */
class NotificationPreferencesMigrationTest {
    @Test void oldInboxAndCommentsStayUnchangedWithoutAnyExternalConsent() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:notification-pref-migration-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        Flyway.configure().dataSource(source).target("91").load().migrate();var jdbc=new JdbcTemplate(source);
        jdbc.update("""
                INSERT INTO notification_inbox(id,tenant_id,recipient_id,event_key,application_id,title,business_no,kind,actor_id,round_no,created_at)
                VALUES (?,'demo','alice','old',?,'旧提醒','OLD','COMMENT_MENTIONED','manager',1,CURRENT_TIMESTAMP)
                """,UUID.randomUUID().toString(),UUID.randomUUID().toString());
        var before=jdbc.queryForList("SELECT * FROM notification_inbox");var comments=jdbc.queryForList("SELECT * FROM application_comment");
        assertThat(Flyway.configure().dataSource(source).target("92").load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM notification_inbox")).isEqualTo(before);assertThat(jdbc.queryForList("SELECT * FROM application_comment")).isEqualTo(comments);
        for(String table:java.util.List.of("notification_preferences","notification_preference_change","notification_dispatch")) assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class)).isZero();
        assertThat(Flyway.configure().dataSource(source).target("92").load().migrate().migrationsExecuted).isZero();
    }
}
