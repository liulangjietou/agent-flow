package io.agentflow.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * V88 从非空 V87 升级只追加收件箱，不改写旧申请及契约事实。
 *
 * @author owlzhangfq@gmail.com
 */
class EventInboxMigrationTest {
    @Test void upgradePreservesExistingApplicationsContractsAndMigrationHistory() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_EVENT_INBOX_MIGRATION_URL", "jdbc:h2:mem:event-inbox-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_EVENT_INBOX_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_EVENT_INBOX_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("87").load().migrate(); var jdbc = new JdbcTemplate(source);
        jdbc.update(
                """
INSERT INTO approval_application (id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version)
VALUES (?,'demo','OLD-EVENT-INBOX','legacy',1,'alice','旧申请','{"note":"原内容"}','IN_APPROVAL',1,2)
""", UUID.randomUUID().toString());
        var contract = EventContract.publish("demo", "old-event", 1, "原契约", "erp", "GoodsAccepted", "admin", "原发布", Instant.now());
        new JdbcEventContractRepository(
                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                (jdbc).getDataSource(),
                                io.agentflow.event.mapper.EventContractRepositoryMapper.class)).publish(contract, 0);
        var before = new LinkedHashMap<String, List<java.util.Map<String, Object>>>();
        for (String table : List.of("approval_application", "event_contract", "event_contract_version", "event_contract_availability_history")) {
            before.put(table, jdbc.queryForList("SELECT * FROM " + table));
        }
        var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        assertThat(Flyway.configure().dataSource(source).target("88").load().migrate().migrationsExecuted).isEqualTo(1);
        before.forEach((table, rows) -> assertThat(jdbc.queryForList("SELECT * FROM " + table)).isEqualTo(rows));
        assertThat(jdbc.queryForList("SELECT * FROM event_inbox")).isEmpty();
        assertThat(jdbc.queryForList("SELECT * FROM event_inbox_revision")).isEmpty();
        assertThat(jdbc.queryForList(
                                "SELECT * FROM \"flyway_schema_history\" WHERE \"version\" IS NULL"
                                        + " OR \"version\"<>'88' ORDER BY \"installed_rank\""))
                .isEqualTo(history);
        assertThat(Flyway.configure().dataSource(source).target("88").load().migrate().migrationsExecuted).isZero();
    }
}
