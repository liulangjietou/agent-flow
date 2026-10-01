package io.agentflow.notification;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.*;

/** V92 旧意向不具备原收件绑定；升级只抑制这些意向，保持站内消息和偏好事实。 @author owlzhangfq@gmail.com */
class NotificationDeliveryMigrationTest {
    @Test void legacyUnboundIntentsNeverBecomeSendableAndUpgradeIsRepeatable() throws Exception {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_NOTIFICATION_DELIVERY_URL", "jdbc:h2:mem:notification-delivery-migration;DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_NOTIFICATION_DELIVERY_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_NOTIFICATION_DELIVERY_PASSWORD", ""));
        String schema = "notification_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = new SingleConnectionDataSource(source.getConnection(), true)) {
            var jdbc = new JdbcTemplate(connection); jdbc.execute("CREATE SCHEMA \"" + schema + "\"");
            connection.getConnection().setSchema(schema);
            Flyway.configure().dataSource(connection).defaultSchema(schema).schemas(schema).target("92").load().migrate();
            jdbc.update("""
                    INSERT INTO notification_preferences(tenant_id,recipient_id,email_enabled,enterprise_im_enabled,version,email_generation,enterprise_im_generation,updated_at)
                    VALUES ('demo','alice',true,false,1,1,0,CURRENT_TIMESTAMP)
                    """);
            for (String state : java.util.List.of("PENDING", "SUPPRESSED")) {
                String inbox = UUID.randomUUID().toString();
                jdbc.update("""
                        INSERT INTO notification_inbox(id,tenant_id,recipient_id,event_key,application_id,title,business_no,kind,actor_id,round_no,created_at,content)
                        VALUES (?,'demo','alice',?,?,'原消息','OLD','COMMENT_MENTIONED','manager',1,CURRENT_TIMESTAMP,'原私有正文')
                        """, inbox, state, UUID.randomUUID().toString());
                jdbc.update("""
                        INSERT INTO notification_dispatch(id,tenant_id,recipient_id,inbox_id,channel,consent_generation,status,created_at,updated_at)
                        VALUES (?,'demo','alice',?,'EMAIL',1,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                        """, UUID.randomUUID().toString(), inbox, state);
            }
            var inboxBefore = jdbc.queryForList("SELECT * FROM notification_inbox ORDER BY id");
            var preferencesBefore = jdbc.queryForList("SELECT * FROM notification_preferences");
            var originalIds = jdbc.queryForList("SELECT id FROM notification_dispatch ORDER BY id", String.class);
            var migration = Flyway.configure().dataSource(connection).defaultSchema(schema).schemas(schema).target("93").load();
            assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(jdbc.queryForList("SELECT * FROM notification_inbox ORDER BY id")).isEqualTo(inboxBefore);
            assertThat(jdbc.queryForList("SELECT * FROM notification_preferences")).isEqualTo(preferencesBefore);
            assertThat(jdbc.queryForList("SELECT id FROM notification_dispatch ORDER BY id", String.class)).isEqualTo(originalIds);
            assertThat(jdbc.queryForList("SELECT status FROM notification_dispatch", String.class)).containsOnly("SUPPRESSED");
            assertThat(jdbc.queryForList("SELECT error_code FROM notification_dispatch", String.class)).containsExactlyInAnyOrder("BINDING_NOT_CAPTURED", "CONSENT_REVOKED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_dispatch WHERE binding_id IS NOT NULL OR destination_digest IS NOT NULL OR attempts<>0 OR next_attempt_at IS NOT NULL", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_delivery_event", Long.class)).isEqualTo(2);
            var history = jdbc.queryForList("SELECT * FROM notification_delivery_event ORDER BY delivery_id,version");
            assertThat(migration.migrate().migrationsExecuted).isZero();
            assertThat(migration.validateWithResult().validationSuccessful).isTrue();
            assertThat(jdbc.queryForList("SELECT * FROM notification_delivery_event ORDER BY delivery_id,version")).isEqualTo(history);
        }
    }
}
