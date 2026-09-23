package io.agentflow.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V17 只新增空投递表，不将已有审计转为外发事件。
 * @author owlzhangfq@gmail.com
 */
class WebhookMigrationTest {
    @Test
    void preservesBusinessAndMigrationHistoryWithoutBackfillingDeliveries() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:webhook-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).target("16").load().migrate(); var jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO audit_event (id,tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,payload_json,action,actor_id) VALUES (?,'demo',?,'Application',?,3,'{}','SUBMIT','alice')", UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
        var audit = jdbc.queryForList("SELECT * FROM audit_event"); var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        Flyway.configure().dataSource(source).load().migrate();
        assertThat(jdbc.queryForList("SELECT * FROM audit_event")).isEqualTo(audit);
        assertThat(jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" WHERE \"version\" IS NULL OR \"version\"<>'17' ORDER BY \"installed_rank\"")).isEqualTo(history);
        for (String table : new String[]{"webhook_delivery", "webhook_attempt", "webhook_retry_request"})
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
        assertThat(Flyway.configure().dataSource(source).load().migrate().migrationsExecuted).isZero();
    }
}
