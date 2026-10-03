package io.agentflow.finance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * V37 到 V38 不改动既有财务事实，预算操作必须有真实同租户单据和历史财务版本。
 * @author owlzhangfq@gmail.com
 */
class BudgetOperationMigrationTest {
    @Test
    void migrationRetainsExistingHistoryAndConstrainsOwnerVersionSingleActiveAndRecoverySchedule() {
        var source = new DriverManagerDataSource(
                System.getenv().getOrDefault("AGENTFLOW_BUDGET_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_BUDGET_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_BUDGET_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("37").load().migrate(); var jdbc = new JdbcTemplate(source);
        String report = UUID.randomUUID().toString(), application = UUID.randomUUID().toString(), operation = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,'retained','BUDGET-UPGRADE','fixture',1,'alice','保留申请','{}','DRAFT',1,1,'EXPENSE',?)", application, report);
        jdbc.update("INSERT INTO expense_report(id,tenant_id,application_id,employee_id,version,state_json) VALUES(?,'retained',?,'alice',1,'{\"retained\":true}')", report, application);
        jdbc.update("INSERT INTO expense_report_revision(tenant_id,report_id,financial_version,actor_id,operation,state_json) SELECT tenant_id,id,version,employee_id,'CREATE',state_json FROM expense_report");
        var oldReports = jdbc.queryForList("SELECT * FROM expense_report"); var oldVersions = jdbc.queryForList("SELECT * FROM expense_report_revision");
        var oldApplications = jdbc.queryForList("SELECT * FROM approval_application");
        var flyway = Flyway.configure().dataSource(source).target("38").load(); assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM expense_report")).isEqualTo(oldReports);
        assertThat(jdbc.queryForList("SELECT * FROM expense_report_revision")).isEqualTo(oldVersions);
        assertThat(jdbc.queryForList("SELECT * FROM approval_application")).isEqualTo(oldApplications);
        String ledger = "INSERT INTO budget_occupation(tenant_id,report_id,application_id,employee_id,target_digest,version,status,pending_operation_id,state_json) VALUES(?,?,?,?,?,1,'UNFUNDED',?,'{}')";
        assertThatThrownBy(() -> jdbc.update(ledger, "foreign", report, application, "alice", "a".repeat(64), operation)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(ledger, "retained", report, application, "bob", "a".repeat(64), operation)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(ledger, "retained", report, UUID.randomUUID().toString(), "alice", "a".repeat(64), operation)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(ledger, "retained", report, application, "alice", "a".repeat(64), operation);
        String insert = "INSERT INTO budget_operation(tenant_id,id,report_id,financial_version,input_json,command_digest,state_json,version,status,attempts,active_report_id,created_at,updated_at,next_attempt_at) VALUES(?,?,?,?,'{}',?,'{}',1,'QUEUED',0,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)";
        assertThatThrownBy(() -> jdbc.update(insert, "foreign", operation, report, 1, "a".repeat(64), report)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "retained", operation, report, 2, "a".repeat(64), report)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "retained", operation, report, 1, "a".repeat(64), null)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, "retained", operation, report, 1, "a".repeat(64), report);
        String next = UUID.randomUUID().toString();
        assertThatThrownBy(() -> jdbc.update(insert, "retained", next, report, 1, "a".repeat(64), report)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE budget_operation SET status='EXECUTING',attempts=1 WHERE id=?", operation)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE budget_occupation SET status='RELEASED' WHERE report_id=?", report)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE budget_operation SET status='REJECTED',active_report_id=NULL,next_attempt_at=NULL WHERE id=?", operation);
        jdbc.update(insert, "retained", next, report, 1, "a".repeat(64), report);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_operation", Integer.class)).isEqualTo(2);
        assertThat(flyway.migrate().migrationsExecuted).isZero(); assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }
}
