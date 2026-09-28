package io.agentflow.expense;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V32 升级保留旧申请语义，并用数据库外键保证同租户的一对一财务绑定。
 * @author owlzhangfq@gmail.com
 */
class ExpenseMigrationTest {
    @Test
    void migrationPreservesLegacyApplicationsAndEnforcesExactFinancialBinding() {
        var source = new DriverManagerDataSource(
                System.getenv().getOrDefault("AGENTFLOW_EXPENSE_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_EXPENSE_MIGRATION_USER", "sa"),
                System.getenv().getOrDefault("AGENTFLOW_EXPENSE_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("32").load().migrate();
        var jdbc = new JdbcTemplate(source);
        String legacy = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version) VALUES(?,'retained','BEFORE-33','expense-reimbursement',1,'alice','原表单','{\"amount\":12.34}','DRAFT',1,1)", legacy);
        var original = jdbc.queryForMap("SELECT * FROM approval_application WHERE id=?", legacy);
        var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        var upgraded = Flyway.configure().dataSource(source).target("33").load();
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
        var migrated = jdbc.queryForMap("SELECT * FROM approval_application WHERE id=?", legacy);
        for (Map.Entry<String, Object> entry : original.entrySet()) assertThat(migrated.get(entry.getKey())).isEqualTo(entry.getValue());
        assertThat(jdbc.queryForObject("SELECT business_type FROM approval_application WHERE id=?", String.class, legacy)).isNull();
        assertThat(jdbc.queryForList("SELECT * FROM expense_report")).isEmpty();
        assertThat(jdbc.queryForList("SELECT * FROM expense_report_revision")).isEmpty();
        assertThat(jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"").subList(0, history.size())).isEqualTo(history);
        assertThatThrownBy(() -> jdbc.update("UPDATE approval_application SET business_type='EXPENSE' WHERE id=?", legacy)).isInstanceOf(DataIntegrityViolationException.class);
        String report = UUID.randomUUID().toString();
        jdbc.update("UPDATE approval_application SET business_type='EXPENSE',business_id=? WHERE id=?", report, legacy);
        String insert = "INSERT INTO expense_report(id,tenant_id,application_id,employee_id,version,state_json) VALUES(?,?,?,'alice',1,'{}')";
        assertThatThrownBy(() -> jdbc.update(insert, report, "foreign", legacy)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID().toString(), "retained", legacy)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, report, "retained", legacy);
        assertThatThrownBy(() -> jdbc.update(insert, report, "retained", legacy)).isInstanceOf(DataIntegrityViolationException.class);
        String append = "INSERT INTO expense_report_revision(tenant_id,report_id,financial_version,actor_id,operation,state_json) VALUES(?,?,1,'alice','CREATE','{}')";
        assertThatThrownBy(() -> jdbc.update(append, "foreign", report)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(append, "retained", report);
        assertThatThrownBy(() -> jdbc.update(append, "retained", report)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(upgraded.migrate().migrationsExecuted).isZero();
        assertThat(upgraded.validateWithResult().validationSuccessful).isTrue();
    }
}
