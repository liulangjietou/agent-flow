package io.agentflow.finance.callback;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 升级只添加空收件箱，不能把历史资金状态伪造为新签名回调。
 * @author owlzhangfq@gmail.com
 */
class PaymentCallbackMigrationTest {
    @Test void preservesPriorAuditAndMigrationRecordsWithoutInventingIncomingCallbacks() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_CALLBACK_MIGRATION_URL", "jdbc:h2:mem:callback-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_CALLBACK_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_CALLBACK_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("73").load().migrate(); var jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO audit_event(id,tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,payload_json,action,actor_id) VALUES(?,'demo',?,'Application',?,3,'{}','SUBMIT','alice')",
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
        var audit = jdbc.queryForList("SELECT * FROM audit_event");
        var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        assertThat(Flyway.configure().dataSource(source).target("74").load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM audit_event")).isEqualTo(audit);
        assertThat(jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" WHERE \"version\" IS NULL OR \"version\"<>'74' ORDER BY \"installed_rank\"")).isEqualTo(history);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_callback", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_callback_revision", Integer.class)).isZero();
        assertThat(Flyway.configure().dataSource(source).target("74").load().migrate().migrationsExecuted).isZero();
    }
}
