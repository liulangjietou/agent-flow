package io.agentflow.expense;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * V36 到 V37 保留财务历史，任务必须指向本租户、本人、原申请及存在的财务版本。
 * @author owlzhangfq@gmail.com
 */
class ExpensePrecheckMigrationTest {
    @Test
    void upgradePreservesFinancialRowsAndConstrainsOwnerVersionAttemptAndActiveJob() {
        var source = new DriverManagerDataSource(
                System.getenv().getOrDefault("AGENTFLOW_PRECHECK_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_PRECHECK_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_PRECHECK_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("36").load().migrate(); var jdbc = new JdbcTemplate(source);
        String report = UUID.randomUUID().toString(), application = UUID.randomUUID().toString(), job = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,'retained','PRECHECK-UPGRADE','fixture',1,'alice','保留申请','{}','DRAFT',1,1,'EXPENSE',?)", application, report);
        jdbc.update("INSERT INTO expense_report(id,tenant_id,application_id,employee_id,version,state_json) VALUES(?,'retained',?,'alice',1,'{\"retained\":true}')", report, application);
        jdbc.update("INSERT INTO expense_report_revision(tenant_id,report_id,financial_version,actor_id,operation,state_json) SELECT tenant_id,id,version,employee_id,'CREATE',state_json FROM expense_report");
        var reportBefore = jdbc.queryForList("SELECT * FROM expense_report"); var revisionsBefore = jdbc.queryForList("SELECT * FROM expense_report_revision");
        var applicationsBefore = jdbc.queryForList("SELECT * FROM approval_application");
        var flyway = Flyway.configure().dataSource(source).target("37").load(); assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM expense_report")).isEqualTo(reportBefore);
        assertThat(jdbc.queryForList("SELECT * FROM expense_report_revision")).isEqualTo(revisionsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM approval_application")).isEqualTo(applicationsBefore);
        String insert = "INSERT INTO expense_precheck_job(tenant_id,id,report_id,application_id,employee_id,application_version,financial_version,attempt_no,input_json,state_json,version,status,active_report_id,created_at) VALUES(?,?,?,?,?,1,?,?,'{}','{}',1,'QUEUED',?,CURRENT_TIMESTAMP)";
        assertThatThrownBy(() -> jdbc.update(insert, "foreign", job, report, application, "alice", 1, 1, report)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "retained", job, report, application, "bob", 1, 1, report)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "retained", job, report, UUID.randomUUID().toString(), "alice", 1, 1, report)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "retained", job, report, application, "alice", 2, 1, report)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "retained", job, report, application, "alice", 1, 1, null)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, "retained", job, report, application, "alice", 1, 1, report);
        String retry = UUID.randomUUID().toString();
        assertThatThrownBy(() -> jdbc.update(insert, "retained", retry, report, application, "alice", 1, 2, report)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE expense_precheck_job SET version=3,status='UNAVAILABLE',active_report_id=NULL,lease_until=CURRENT_TIMESTAMP,completed_at=CURRENT_TIMESTAMP WHERE id=?", job);
        assertThatThrownBy(() -> jdbc.update(insert, "retained", retry, report, application, "alice", 1, 1, report)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, "retained", retry, report, application, "alice", 1, 2, report);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_precheck_job", Integer.class)).isEqualTo(2);
        assertThat(flyway.migrate().migrationsExecuted).isZero(); assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }
}
