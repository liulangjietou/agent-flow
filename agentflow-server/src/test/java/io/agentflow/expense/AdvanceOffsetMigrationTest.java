package io.agentflow.expense;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.JsonUtil;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 旧放款只增加可查询日期索引；损坏日期不能静默跳过，更不能改写原始财务账本。
 * @author owlzhangfq@gmail.com
 */
class AdvanceOffsetMigrationTest {
    @Test
    void indexesOriginalContextsWithoutChangingFinancialFactsAndSurvivesRepeatedMigration() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_FIFO_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_FIFO_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_FIFO_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("100").load().migrate(); var jdbc = new JdbcTemplate(source);
        String tenant = "fifo-migration", id = UUID.randomUUID().toString(), entity = UUID.randomUUID().toString();
        var json = new JsonUtil(new ObjectMapper());
        String context = json.write(Map.of("legalEntityId", entity, "paidAmount", Map.of("value", "123.45", "currency", "CNY"), "paidOn", "2026-09-01", "dueOn", "2026-10-01"));
        jdbc.update("INSERT INTO finance_resource(tenant_id,resource_type,id,owner_id,source_reference,version,context_json,state_json) VALUES(?,'ADVANCE',?,'alice',?,4,?,'{\"original\":true}')", tenant, id, "actual-payment-" + id, context);
        jdbc.update("INSERT INTO finance_resource_revision(tenant_id,resource_type,resource_id,version,actor_id,operation,state_json) VALUES(?,'ADVANCE',?,4,'alice','RESERVE','{\"original\":true}')", tenant, id);
        var before = jdbc.queryForList("SELECT * FROM finance_resource"); var revisions = jdbc.queryForList("SELECT * FROM finance_resource_revision");
        var migration = Flyway.configure().dataSource(source).target("101").load();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT legal_entity_id FROM employee_advance_order WHERE tenant_id=? AND advance_id=?", String.class, tenant, id)).isEqualTo(entity);
        assertThat(jdbc.queryForObject("SELECT paid_on FROM employee_advance_order WHERE tenant_id=? AND advance_id=?", java.sql.Date.class, tenant, id).toLocalDate().toString()).isEqualTo("2026-09-01");
        assertThat(jdbc.queryForList("SELECT * FROM finance_resource")).containsExactlyInAnyOrderElementsOf(before);
        assertThat(jdbc.queryForList("SELECT * FROM finance_resource_revision")).containsExactlyInAnyOrderElementsOf(revisions);
        assertThat(migration.migrate().migrationsExecuted).isZero(); assertThat(migration.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void invalidLegacyContextStopsMigrationWithoutRewritingTheBadFact() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).target("100").load().migrate(); var jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO finance_resource(tenant_id,resource_type,id,owner_id,source_reference,version,context_json,state_json) VALUES('bad-fifo','ADVANCE',?,'alice','original-payment',1,'{}','{}')", UUID.randomUUID().toString());
        var before = jdbc.queryForList("SELECT * FROM finance_resource");
        assertThatThrownBy(() -> Flyway.configure().dataSource(source).target("101").load().migrate()).hasStackTraceContaining("Cannot index advance with invalid immutable context");
        assertThat(jdbc.queryForList("SELECT * FROM finance_resource")).containsExactlyInAnyOrderElementsOf(before);
    }
}
