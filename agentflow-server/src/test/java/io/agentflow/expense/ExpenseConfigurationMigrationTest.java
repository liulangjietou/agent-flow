package io.agentflow.expense;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 非空数据库升级只增加独立费用配置历史，不改写存量财务事实或预检结论。
 * @author owlzhangfq@gmail.com
 */
class ExpenseConfigurationMigrationTest {
    @Test void addsConfigurationTablesWithoutChangingLegacyFinancialFacts() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_EXPENSE_CONFIG_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_EXPENSE_CONFIG_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_EXPENSE_CONFIG_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("102").load().migrate(); var jdbc = new JdbcTemplate(source);
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO finance_resource(tenant_id,resource_type,id,owner_id,source_reference,version,context_json,state_json) VALUES('configuration-migration','ADVANCE',?,'alice',?,4,'{\"originalContext\":true}','{\"originalState\":true}')", id, "original-payment-" + id);
        jdbc.update("INSERT INTO finance_resource_revision(tenant_id,resource_type,resource_id,version,actor_id,operation,state_json) VALUES('configuration-migration','ADVANCE',?,4,'alice','RESERVE','{\"originalState\":true}')", id);
        var before = jdbc.queryForList("SELECT * FROM finance_resource"); var revisions = jdbc.queryForList("SELECT * FROM finance_resource_revision");
        var migration = Flyway.configure().dataSource(source).target("103").load();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        for (var table : java.util.List.of("expense_configuration", "expense_category_revision", "expense_policy_draft", "expense_policy_draft_revision", "expense_policy_version", "expense_policy_activation")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
        }
        assertThat(jdbc.queryForList("SELECT * FROM finance_resource")).containsExactlyInAnyOrderElementsOf(before);
        assertThat(jdbc.queryForList("SELECT * FROM finance_resource_revision")).containsExactlyInAnyOrderElementsOf(revisions);
        assertThat(migration.migrate().migrationsExecuted).isZero();
        assertThat(migration.validateWithResult().validationSuccessful).isTrue();
    }
}
