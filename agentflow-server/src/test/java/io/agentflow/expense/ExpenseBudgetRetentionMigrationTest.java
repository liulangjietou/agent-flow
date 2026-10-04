package io.agentflow.expense;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 非空升级保留旧单、预算及凭据，历史停止轮次不补造到期记录。
 * @author owlzhangfq@gmail.com
 */
class ExpenseBudgetRetentionMigrationTest {
    @Test void upgradeKeepsOldRowsAndOnlyReleasedLedgersCanHaveANewPendingOperation() {
        var source = new DriverManagerDataSource(
                System.getenv().getOrDefault("AGENTFLOW_RETENTION_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_RETENTION_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_RETENTION_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("104").load().migrate(); var jdbc = new JdbcTemplate(source);
        String report = UUID.randomUUID().toString(), application = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id)
                VALUES(?,'retained','RETENTION-UPGRADE','fixture',1,'alice','保留申请','{}','WITHDRAWN',1,3,'EXPENSE',?)
                """, application, report);
        jdbc.update("INSERT INTO expense_report(id,tenant_id,application_id,employee_id,version,state_json) VALUES(?,'retained',?,'alice',1,'{\"original\":true}')", report, application);
        jdbc.update("INSERT INTO expense_report_revision(tenant_id,report_id,financial_version,actor_id,operation,state_json) SELECT tenant_id,id,version,employee_id,'CREATE',state_json FROM expense_report");
        jdbc.update("""
                INSERT INTO approval_submission_round(tenant_id,application_id,round_no,process_instance_id,definition_version,title,payload_json,submitted_by,submitted_at,status,completed_by,completed_at)
                VALUES('retained',?,1,'retained-instance',1,'原轮次','{}','alice',CURRENT_TIMESTAMP,'WITHDRAWN','alice',CURRENT_TIMESTAMP)
                """, application);
        jdbc.update("""
                INSERT INTO budget_occupation(tenant_id,report_id,application_id,employee_id,target_digest,version,status,state_json)
                VALUES('retained',?,?,'alice',?,4,'RELEASED','{"originalRelease":true}')
                """, report, application, "a".repeat(64));
        var oldReports = jdbc.queryForList("SELECT * FROM expense_report"); var oldRounds = jdbc.queryForList("SELECT * FROM approval_submission_round");
        var oldLedger = jdbc.queryForList("SELECT * FROM budget_occupation");
        var migration = Flyway.configure().dataSource(source).target("105").load(); assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM expense_report")).isEqualTo(oldReports);
        assertThat(jdbc.queryForList("SELECT * FROM approval_submission_round")).isEqualTo(oldRounds);
        assertThat(jdbc.queryForList("SELECT * FROM budget_occupation")).isEqualTo(oldLedger);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_budget_retention", Integer.class)).isZero();
        jdbc.update("UPDATE budget_occupation SET pending_operation_id=? WHERE report_id=?", UUID.randomUUID().toString(), report);
        assertThatThrownBy(() -> jdbc.update("UPDATE budget_occupation SET status='CONSUMED' WHERE report_id=?", report)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO expense_budget_retention(tenant_id,report_id,application_id,round_no,stopped_status,retained_at,retention_days,expires_at,status,version,state_json,updated_at)
                VALUES('retained',?,?,2,'WITHDRAWN',CURRENT_TIMESTAMP,3,CURRENT_TIMESTAMP + INTERVAL '3' DAY,'RETAINED',1,'{}',CURRENT_TIMESTAMP)
                """, report, application)).isInstanceOf(DataIntegrityViolationException.class);
        String otherApplication = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version)
                VALUES(?,'retained','OTHER-RETENTION','fixture',1,'alice','另一申请','{}','WITHDRAWN',1,3)
                """, otherApplication);
        jdbc.update("""
                INSERT INTO approval_submission_round(tenant_id,application_id,round_no,process_instance_id,definition_version,title,payload_json,submitted_by,submitted_at,status,completed_by,completed_at)
                VALUES('retained',?,1,'other-instance',1,'另一轮次','{}','alice',CURRENT_TIMESTAMP,'WITHDRAWN','alice',CURRENT_TIMESTAMP)
                """, otherApplication);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO expense_budget_retention(tenant_id,report_id,application_id,round_no,stopped_status,retained_at,retention_days,expires_at,status,version,state_json,updated_at)
                VALUES('retained',?,?,1,'WITHDRAWN',CURRENT_TIMESTAMP,3,CURRENT_TIMESTAMP + INTERVAL '3' DAY,'RETAINED',1,'{}',CURRENT_TIMESTAMP)
                """, report, otherApplication)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(migration.migrate().migrationsExecuted).isZero(); assertThat(migration.validateWithResult().validationSuccessful).isTrue();
    }
}
