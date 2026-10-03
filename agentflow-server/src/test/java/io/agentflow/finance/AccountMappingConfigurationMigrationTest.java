package io.agentflow.finance;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/**
 * V103 非空数据库升级只添加科目配置，不改写既有财务资源和费用类别历史。
 * @author owlzhangfq@gmail.com
 */
class AccountMappingConfigurationMigrationTest {
    @Test void upgradePreservesExistingFinancialAndCategoryFacts() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_ACCOUNT_MAPPING_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_ACCOUNT_MAPPING_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_ACCOUNT_MAPPING_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("103").load().migrate(); var jdbc = new JdbcTemplate(source);
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO expense_configuration (tenant_id,category_revision) VALUES ('mapping-migration',1)");
        jdbc.update("INSERT INTO expense_category_revision (tenant_id,revision,category_count,state_json,updated_by,updated_at,comment) VALUES ('mapping-migration',1,0,'{}','original',CURRENT_TIMESTAMP,'original catalog')");
        jdbc.update("INSERT INTO finance_resource (tenant_id,resource_type,id,owner_id,source_reference,version,context_json,state_json) VALUES ('mapping-migration','ADVANCE',?,'alice',?,4,'{\"original\":true}','{\"original\":true}')", id, "mapping-original-" + id);
        var rows = jdbc.queryForList("SELECT * FROM finance_resource"); var categories = jdbc.queryForList("SELECT * FROM expense_category_revision");
        var migration = Flyway.configure().dataSource(source).target("104").load(); assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        for (var table : List.of("account_mapping_scope", "account_mapping_draft", "account_mapping_draft_revision", "account_mapping_version", "account_mapping_activation")) assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM finance_resource")).containsExactlyInAnyOrderElementsOf(rows);
        assertThat(jdbc.queryForList("SELECT * FROM expense_category_revision")).containsExactlyInAnyOrderElementsOf(categories);
        assertThat(migration.migrate().migrationsExecuted).isZero(); assertThat(migration.validateWithResult().validationSuccessful).isTrue();
    }
}
