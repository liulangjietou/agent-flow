package io.agentflow.notification;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文案迁移只增加可空列，不将新配置伪装为历史消息事实。
 * @author owlzhangfq@gmail.com
 */
class NotificationTextsMigrationTest {
    @Test
    void migrationPreservesOldRowsAndDoesNotBackfillOrRewriteHistory() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:notification-text-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).target("23").load().migrate();
        var jdbc = new JdbcTemplate(source);
        String definitionId = UUID.randomUUID().toString(), applicationId = UUID.randomUUID().toString(), messageId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO approval_definition(id,tenant_id,process_key,name,version,revision,status,graph_json)
                VALUES(?,'demo','old-notice','旧流程',1,1,'PUBLISHED','{"nodes":[],"edges":[]}')
                """, definitionId);
        jdbc.update("""
                INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version)
                VALUES(?,'demo','OLD-NOTICE','old-notice',1,'alice','旧申请','{}','APPROVED',1,3)
                """, applicationId);
        jdbc.update("""
                INSERT INTO notification_inbox(id,tenant_id,recipient_id,event_key,application_id,title,business_no,kind,actor_id,round_no,created_at)
                VALUES(?,'demo','alice','old-event',?,'旧申请','OLD-NOTICE','APPLICATION_APPROVED','manager',1,CURRENT_TIMESTAMP)
                """, messageId, applicationId);
        var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        var originalMessage = jdbc.queryForMap("SELECT id,title,event_key,created_at,read_at FROM notification_inbox WHERE id=?", messageId);
        assertThat(Flyway.configure().dataSource(source).target("24").load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT id,title,event_key,created_at,read_at FROM notification_inbox WHERE id=?", messageId)).isEqualTo(originalMessage);
        assertThat(jdbc.queryForObject("SELECT content FROM notification_inbox WHERE id=?", String.class, messageId)).isNull();
        assertThat(jdbc.queryForObject("SELECT notification_texts_json FROM approval_definition WHERE id=?", String.class, definitionId)).isNull();
        assertThat(jdbc.queryForObject("SELECT notification_texts_json FROM approval_application WHERE id=?", String.class, applicationId)).isNull();
        assertThat(jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" WHERE \"version\"<>'24' OR \"version\" IS NULL ORDER BY \"installed_rank\""))
                .isEqualTo(history);
        assertThat(Flyway.configure().dataSource(source).target("24").load().migrate().migrationsExecuted).isZero();
    }
}
