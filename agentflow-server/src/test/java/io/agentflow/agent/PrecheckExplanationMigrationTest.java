package io.agentflow.agent;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/**
 * V106 到 V107 保留非空财务与检查历史，新增队列必须绑定本人和原单据版本。
 * @author owlzhangfq@gmail.com
 */
class PrecheckExplanationMigrationTest {
    @Test void upgradePreservesOldRowsAndConstrainsOwnershipSourceVersionLeaseAndActiveRun() {
        var source = new DriverManagerDataSource(
                System.getenv().getOrDefault("AGENTFLOW_EXPLANATION_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_EXPLANATION_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_EXPLANATION_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("106").load().migrate(); var jdbc = new JdbcTemplate(source);
        String report = UUID.randomUUID().toString(), app = UUID.randomUUID().toString(), job = UUID.randomUUID().toString();
        String otherReport = UUID.randomUUID().toString(), otherApp = UUID.randomUUID().toString(), otherJob = UUID.randomUUID().toString();
        fixture(jdbc, report, app, job); fixture(jdbc, otherReport, otherApp, otherJob);
        var oldReports = jdbc.queryForList("SELECT * FROM expense_report ORDER BY id");
        var oldRevisions = jdbc.queryForList("SELECT * FROM expense_report_revision ORDER BY report_id");
        var oldJobs = jdbc.queryForList("SELECT * FROM expense_precheck_job ORDER BY id");
        var oldJobRevisions = jdbc.queryForList("SELECT * FROM expense_precheck_revision ORDER BY job_id,version");
        var oldApps = jdbc.queryForList("SELECT * FROM approval_application ORDER BY id");
        var flyway = Flyway.configure().dataSource(source).target("107").load(); assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM expense_report ORDER BY id")).isEqualTo(oldReports);
        assertThat(jdbc.queryForList("SELECT * FROM expense_report_revision ORDER BY report_id")).isEqualTo(oldRevisions);
        assertThat(jdbc.queryForList("SELECT * FROM expense_precheck_job ORDER BY id")).isEqualTo(oldJobs);
        assertThat(jdbc.queryForList("SELECT * FROM expense_precheck_revision ORDER BY job_id,version")).isEqualTo(oldJobRevisions);
        assertThat(jdbc.queryForList("SELECT * FROM approval_application ORDER BY id")).isEqualTo(oldApps);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_precheck_explanation_run", Integer.class)).isZero();
        String insert = """
                INSERT INTO agent_precheck_explanation_run(tenant_id,id,report_id,application_id,requested_by,application_version,financial_version,
                    precheck_id,precheck_attempt,status,version,active_report_id,context_json,state_json,created_at)
                VALUES(?,?,?,?,?,1,?,?,1,'QUEUED',1,?,'{}','{}',CURRENT_TIMESTAMP)
                """;
        String run = UUID.randomUUID().toString();
        assertThatThrownBy(() -> jdbc.update(insert, "foreign", run, report, app, "alice", 1, job, report)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "retained", run, report, app, "admin", 1, job, report)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "retained", run, report, otherApp, "alice", 1, job, report)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "retained", run, report, app, "alice", 1, otherJob, report)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "retained", run, report, app, "alice", 2, job, report)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "retained", run, report, app, "alice", 1, job, null)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, "retained", run, report, app, "alice", 1, job, report);
        assertThatThrownBy(() -> jdbc.update(insert, "retained", UUID.randomUUID().toString(), report, app, "alice", 1, job, report)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE agent_precheck_explanation_run SET status='RUNNING',version=2 WHERE id=?", run)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE agent_precheck_explanation_run SET status='ADOPTED',active_report_id=NULL WHERE id=?", run)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE agent_precheck_explanation_run SET status='RUNNING',version=2,lease_until=CURRENT_TIMESTAMP WHERE id=?", run);
        jdbc.update("UPDATE agent_precheck_explanation_run SET status='FAILED',version=3,lease_until=NULL,active_report_id=NULL WHERE id=?", run);
        jdbc.update(insert, "retained", UUID.randomUUID().toString(), report, app, "alice", 1, job, report);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_precheck_explanation_run", Integer.class)).isEqualTo(2);
        assertThat(flyway.migrate().migrationsExecuted).isZero(); assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }
    private static void fixture(JdbcTemplate jdbc, String report, String application, String job) {
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,'retained',?,'fixture',1,'alice','保留申请','{}','DRAFT',1,1,'EXPENSE',?)", application, "EXPLAIN-" + application, report);
        jdbc.update("INSERT INTO expense_report(id,tenant_id,application_id,employee_id,version,state_json) VALUES(?,'retained',?,'alice',1,'{\"retained\":true}')", report, application);
        jdbc.update("INSERT INTO expense_report_revision(tenant_id,report_id,financial_version,actor_id,operation,state_json) SELECT tenant_id,id,version,employee_id,'CREATE',state_json FROM expense_report WHERE id=?", report);
        jdbc.update("""
                INSERT INTO expense_precheck_job(tenant_id,id,report_id,application_id,employee_id,application_version,financial_version,attempt_no,
                    input_json,state_json,version,status,created_at,lease_until,completed_at)
                VALUES('retained',?,?,?,'alice',1,1,1,'{}','{}',3,'BLOCKED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, job, report, application);
        jdbc.update("INSERT INTO expense_precheck_revision(tenant_id,job_id,version,state_json) VALUES('retained',?,3,'{}')", job);
    }
}
