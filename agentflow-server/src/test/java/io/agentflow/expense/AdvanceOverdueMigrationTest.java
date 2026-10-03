package io.agentflow.expense;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.JsonUtil;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.sql.Date;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 已有资金记录只增加归还日索引，迁移失败不能改写原始借款历史。
 * @author owlzhangfq@gmail.com
 */
class AdvanceOverdueMigrationTest {
    @Test void indexesOriginalDueDatesWithoutChangingFinancialHistoryAndRepeatsSafely() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_OVERDUE_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_OVERDUE_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_OVERDUE_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("100").load().migrate(); var jdbc = new JdbcTemplate(source);
        insert(jdbc, "2026-10-10"); Flyway.configure().dataSource(source).target("101").load().migrate();
        var facts = jdbc.queryForList("SELECT * FROM finance_resource"); var history = jdbc.queryForList("SELECT * FROM finance_resource_revision");
        var originalIndex = jdbc.queryForList("SELECT tenant_id,advance_id,paid_on FROM employee_advance_order");
        var flyway = Flyway.configure().dataSource(source).target("102").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT due_on FROM employee_advance_order", Date.class).toLocalDate().toString()).isEqualTo("2026-10-10");
        assertThat(jdbc.queryForList("SELECT * FROM finance_resource")).containsExactlyInAnyOrderElementsOf(facts);
        assertThat(jdbc.queryForList("SELECT * FROM finance_resource_revision")).containsExactlyInAnyOrderElementsOf(history);
        assertThat(jdbc.queryForList("SELECT tenant_id,advance_id,paid_on FROM employee_advance_order")).containsExactlyInAnyOrderElementsOf(originalIndex);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM advance_overdue_reminder", Integer.class)).isZero();
        assertThat(flyway.migrate().migrationsExecuted).isZero(); assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test void corruptLegacyDueDateFailsWithoutInventingARepaymentDate() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).target("100").load().migrate(); var jdbc = new JdbcTemplate(source);
        insert(jdbc, "invalid"); Flyway.configure().dataSource(source).target("101").load().migrate();
        var facts = jdbc.queryForList("SELECT * FROM finance_resource");
        assertThatThrownBy(() -> Flyway.configure().dataSource(source).target("102").load().migrate()).hasStackTraceContaining("Cannot index advance with invalid due date");
        assertThat(jdbc.queryForList("SELECT * FROM finance_resource")).containsExactlyInAnyOrderElementsOf(facts);
    }

    private void insert(JdbcTemplate jdbc, String dueOn) {
        String id = UUID.randomUUID().toString(); var json = new JsonUtil(new ObjectMapper());
        String context = json.write(Map.of("legalEntityId", UUID.randomUUID().toString(), "paidAmount", Map.of("value", "123.45", "currency", "CNY"), "paidOn", "2026-09-01", "dueOn", dueOn));
        jdbc.update("INSERT INTO finance_resource(tenant_id,resource_type,id,owner_id,source_reference,version,context_json,state_json) VALUES('overdue-migration','ADVANCE',?,'alice',?,4,?,'{\"original\":true}')", id, "actual-payment-" + id, context);
        jdbc.update("INSERT INTO finance_resource_revision(tenant_id,resource_type,resource_id,version,actor_id,operation,state_json) VALUES('overdue-migration','ADVANCE',?,4,'alice','RESERVE','{\"original\":true}')", id);
    }
}
