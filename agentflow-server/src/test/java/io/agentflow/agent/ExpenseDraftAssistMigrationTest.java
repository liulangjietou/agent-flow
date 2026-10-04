package io.agentflow.agent;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 非空 V107 升级保留费用与预检解释，新增填报建议绑定租户、本人、原财务版本和状态。
 * @author owlzhangfq@gmail.com
 */
class ExpenseDraftAssistMigrationTest {
    @Test void upgradePreservesExistingFinancialAndAgentRowsAndEnforcesRunAndTraceBindings() {
        var source = new DriverManagerDataSource(
                System.getenv().getOrDefault("AGENTFLOW_EXPENSE_DRAFT_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_EXPENSE_DRAFT_MIGRATION_USER", "sa"),
                System.getenv().getOrDefault("AGENTFLOW_EXPENSE_DRAFT_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("107").load().migrate(); var jdbc = new JdbcTemplate(source);
        String report = UUID.randomUUID().toString(), app = UUID.randomUUID().toString();
        String otherReport = UUID.randomUUID().toString(), otherApp = UUID.randomUUID().toString();
        fixture(jdbc, report, app); fixture(jdbc, otherReport, otherApp);
        var tables = List.of("approval_application", "expense_report", "expense_report_revision", "expense_precheck_job",
                "expense_precheck_revision", "agent_precheck_explanation_run", "agent_precheck_explanation_transition");
        var before = tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1,2")).toList();
        var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        var migration = Flyway.configure().dataSource(source).target("108").load(); assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1,2")).toList()).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"").subList(0, history.size())).isEqualTo(history);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_expense_draft_run", Long.class)).isZero();
        String insert = """
                INSERT INTO agent_expense_draft_run(tenant_id,id,report_id,application_id,requested_by,application_version,financial_version,
                    status,version,active_report_id,context_json,state_json,created_at)
                VALUES(?,?,?,?,?,1,?,'QUEUED',1,?,'{}','{}',CURRENT_TIMESTAMP)
                """;
        String run = UUID.randomUUID().toString();
        assertThatThrownBy(() -> jdbc.update(insert, "foreign", run, report, app, "alice", 1, report)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "retained", run, report, app, "admin", 1, report)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "retained", run, report, otherApp, "alice", 1, report)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "retained", run, report, app, "alice", 2, report)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "retained", run, report, app, "alice", 1, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "retained", run, report, app, "alice", 1, otherReport)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, "retained", run, report, app, "alice", 1, report);
        assertThatThrownBy(() -> jdbc.update(insert, "retained", UUID.randomUUID().toString(), report, app, "alice", 1, report)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE agent_expense_draft_run SET status='RUNNING',version=2 WHERE id=?", run)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE agent_expense_draft_run SET status='CONFIRMED',active_report_id=NULL WHERE id=?", run)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE agent_expense_draft_run SET lease_until=CURRENT_TIMESTAMP WHERE id=?", run)).isInstanceOf(DataIntegrityViolationException.class);
        String trace = "INSERT INTO agent_expense_draft_transition(tenant_id,run_id,run_version,status,state_json) VALUES(?,?,?,'QUEUED','{}')";
        assertThatThrownBy(() -> jdbc.update(trace, "foreign", run, 1)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(trace, "retained", run, 2)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(trace, "retained", run, 1);
        assertThatThrownBy(() -> jdbc.update(trace, "retained", run, 1)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE agent_expense_draft_run SET status='RUNNING',version=2,lease_until=CURRENT_TIMESTAMP WHERE id=?", run);
        jdbc.update("UPDATE agent_expense_draft_run SET status='FAILED',version=3,lease_until=NULL,active_report_id=NULL WHERE id=?", run);
        jdbc.update(insert, "retained", UUID.randomUUID().toString(), report, app, "alice", 1, report);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_expense_draft_run", Long.class)).isEqualTo(2);
        assertThat(migration.migrate().migrationsExecuted).isZero(); assertThat(migration.validateWithResult().validationSuccessful).isTrue();
    }

    private static void fixture(JdbcTemplate jdbc, String report, String app) {
        String job = UUID.randomUUID().toString(), explanation = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,'retained',?,'fixture',1,'alice','保留申请','{}','DRAFT',1,1,'EXPENSE',?)", app, "BEFORE-108-" + app, report);
        jdbc.update("INSERT INTO expense_report(id,tenant_id,application_id,employee_id,version,state_json) VALUES(?,'retained',?,'alice',1,'{\"retained\":true}')", report, app);
        jdbc.update("INSERT INTO expense_report_revision(tenant_id,report_id,financial_version,actor_id,operation,state_json) SELECT tenant_id,id,version,employee_id,'CREATE',state_json FROM expense_report WHERE id=?", report);
        jdbc.update("""
                INSERT INTO expense_precheck_job(tenant_id,id,report_id,application_id,employee_id,application_version,financial_version,attempt_no,
                    input_json,state_json,version,status,created_at,lease_until,completed_at)
                VALUES('retained',?,?,?,'alice',1,1,1,'{}','{}',3,'BLOCKED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, job, report, app);
        jdbc.update("INSERT INTO expense_precheck_revision(tenant_id,job_id,version,state_json) VALUES('retained',?,3,'{}')", job);
        jdbc.update("""
                INSERT INTO agent_precheck_explanation_run(tenant_id,id,report_id,application_id,requested_by,application_version,financial_version,
                    precheck_id,precheck_attempt,status,version,active_report_id,context_json,state_json,created_at)
                VALUES('retained',?,?,?,'alice',1,1,?,1,'QUEUED',1,?,'{}','{}',CURRENT_TIMESTAMP)
                """, explanation, report, app, job, report);
        jdbc.update("INSERT INTO agent_precheck_explanation_transition(tenant_id,run_id,run_version,status,state_json) VALUES('retained',?,1,'QUEUED','{}')", explanation);
    }
}
