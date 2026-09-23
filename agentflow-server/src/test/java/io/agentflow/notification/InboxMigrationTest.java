package io.agentflow.notification;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 新消息表不回填旧提醒，不改写历史业务或已发布迁移。
 * @author owlzhangfq@gmail.com
 */
class InboxMigrationTest {
    @Test
    void upgradeFromTenAddsEmptyInboxAndPreservesExistingRowsAndMigrationHistory() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:inbox-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).target("10").load().migrate();
        var jdbc = new JdbcTemplate(source);
        jdbc.update("""
                INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version)
                VALUES(?,'demo','OLD-INBOX','legacy',1,'alice','旧申请','{}','APPROVED',1,3)
                """, UUID.randomUUID().toString());
        var before = jdbc.queryForList("SELECT * FROM approval_application");
        var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        assertThat(Flyway.configure().dataSource(source).load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM approval_application")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" WHERE \"version\"<>'11' OR \"version\" IS NULL ORDER BY \"installed_rank\""))
                .isEqualTo(history);
        assertThat(Flyway.configure().dataSource(source).load().migrate().migrationsExecuted).isZero();
    }
}
