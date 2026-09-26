package io.agentflow.organization;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * V26 只新增组织存储，保留旧申请、治理状态、登录会话和迁移历史。
 * @author owlzhangfq@gmail.com
 */
class OrganizationMigrationTest {
    @Test
    void upgradesV25WithoutRewritingExistingDataAndRejectsCrossTenantReferences() {
        var source = new DriverManagerDataSource(
                System.getProperty("agentflow.organization-migration.jdbc-url", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getProperty("agentflow.organization-migration.jdbc-user", "sa"),
                System.getProperty("agentflow.organization-migration.jdbc-password", ""));
        Flyway.configure().dataSource(source).target("25").load().migrate();
        var jdbc = new JdbcTemplate(source);
        String definition = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO approval_definition(id,tenant_id,process_key,name,version,revision,status,graph_json,start_enabled)
                VALUES(?,'legacy','retained','保留版本',1,2,'PUBLISHED','{}',FALSE)
                """, definition);
        jdbc.update("""
                INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version)
                VALUES(?,'legacy','RETAINED','retained',1,'person','旧申请','{}','RETURNED',1,3)
                """, UUID.randomUUID().toString());
        String session = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO AF_HTTP_SESSION VALUES (?,?,?,?,?,?,?)", session, UUID.randomUUID().toString(), 100L, 200L, 1800, 1800200L, "person");
        jdbc.update("INSERT INTO AF_HTTP_SESSION_ATTRIBUTES VALUES (?,?,?)", session, "identity", new byte[]{1, 2, 3});
        var tables = List.of("approval_definition", "approval_application", "AF_HTTP_SESSION", "AF_HTTP_SESSION_ATTRIBUTES");
        var before = tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table)).toList();
        var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");

        assertThat(Flyway.configure().dataSource(source).target("26").load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table)).toList()).usingRecursiveComparison().isEqualTo(before);
        var after = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        assertThat(after).hasSize(history.size() + 1);
        assertThat(after.subList(0, history.size())).isEqualTo(history);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM organization_directory", Integer.class)).isZero();
        assertThat(Flyway.configure().dataSource(source).target("26").load().migrate().migrationsExecuted).isZero();

        for (String tenant : List.of("one", "two")) jdbc.update("INSERT INTO organization_directory VALUES (?,1,'admin',CURRENT_TIMESTAMP)", tenant);
        String legal = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO organization_unit VALUES ('one',?,'LEGAL_ENTITY','法人',NULL,NULL,TRUE,1)", legal);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO organization_unit VALUES ('two',?,'DEPARTMENT','跨租户',?,NULL,TRUE,1)", UUID.randomUUID().toString(), legal))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM organization_unit WHERE tenant_id='two'", Integer.class)).isZero();
    }
}
