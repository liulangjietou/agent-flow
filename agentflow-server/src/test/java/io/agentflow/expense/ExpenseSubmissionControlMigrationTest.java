package io.agentflow.expense;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 控制记录不能跨单据引用预检，且必须已经有对应审批轮次和正式冻结版本。
 * @author owlzhangfq@gmail.com
 */
class ExpenseSubmissionControlMigrationTest {
    @Test
    void upgradePreservesOldDataAndRequiresActualRoundVersionAndSameReportPrecheck() {
        var source = new DriverManagerDataSource(
                System.getenv().getOrDefault("AGENTFLOW_EXPENSE_CONTROL_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_EXPENSE_CONTROL_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_EXPENSE_CONTROL_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("38").load().migrate(); var jdbc = new JdbcTemplate(source);
        String report = UUID.randomUUID().toString(), app = UUID.randomUUID().toString(), precheck = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,'control-upgrade','CONTROL-UPGRADE','fixture',1,'alice','保留申请','{}','DRAFT',1,1,'EXPENSE',?)", app, report);
        jdbc.update("INSERT INTO expense_report(id,tenant_id,application_id,employee_id,version,state_json) VALUES(?,'control-upgrade',?,'alice',2,'{\"retained\":true}')", report, app);
        for (int version : new int[]{1, 2}) jdbc.update("INSERT INTO expense_report_revision(tenant_id,report_id,financial_version,actor_id,operation,state_json) VALUES('control-upgrade',?,?,'alice','SYNTHETIC','{}')", report, version);
        jdbc.update("""
                INSERT INTO expense_precheck_job(tenant_id,id,report_id,application_id,employee_id,application_version,financial_version,
                attempt_no,input_json,state_json,version,status,active_report_id,created_at,lease_until,completed_at)
                VALUES('control-upgrade',?,?,?,'alice',1,1,1,'{}','{}',3,'READY',NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, precheck, report, app);
        var previous = jdbc.queryForList("SELECT * FROM expense_precheck_job"); var reports = jdbc.queryForList("SELECT * FROM expense_report");
        var flyway = Flyway.configure().dataSource(source).target("39").load(); assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM expense_precheck_job")).isEqualTo(previous);
        assertThat(jdbc.queryForList("SELECT * FROM expense_report")).isEqualTo(reports);
        String insert = "INSERT INTO expense_submission_control(tenant_id,report_id,application_id,employee_id,round_no,submitted_financial_version,precheck_id,paper_required,receipt_received,version,input_json,state_json,submitted_at) VALUES('control-upgrade',?,?,?,1,?,?,TRUE,FALSE,1,'{}','{}',CURRENT_TIMESTAMP)";
        assertThatThrownBy(() -> jdbc.update(insert, report, app, "alice", 2, precheck)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO approval_submission_round(tenant_id,application_id,round_no,process_instance_id,definition_version,title,payload_json,submitted_by,submitted_at,status) VALUES('control-upgrade',?,1,?,1,'合成轮次','{}','alice',CURRENT_TIMESTAMP,'IN_APPROVAL')", app, UUID.randomUUID().toString());
        assertThatThrownBy(() -> jdbc.update(insert, report, app, "bob", 2, precheck)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, report, app, "alice", 3, precheck)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, report, app, "alice", 2, UUID.randomUUID().toString())).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, report, app, "alice", 2, precheck);
        assertThatThrownBy(() -> jdbc.update("UPDATE expense_submission_control SET receipt_received=TRUE WHERE report_id=?", report)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE expense_submission_control SET receipt_received=TRUE,version=2 WHERE report_id=?", report);
        assertThatThrownBy(() -> jdbc.update("UPDATE expense_submission_control SET paper_required=FALSE WHERE report_id=?", report)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(flyway.migrate().migrationsExecuted).isZero(); assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }
}
