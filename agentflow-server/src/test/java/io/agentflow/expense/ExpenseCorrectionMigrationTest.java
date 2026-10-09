package io.agentflow.expense;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 非空 V128 升级保留原检查、解释和财务历史；新回执不能关联旧版本预检或另一租户。
 * @author owlzhangfq@gmail.com
 */
class ExpenseCorrectionMigrationTest {
    @Test void upgradePreservesHistoryAndBindsCorrectionToTheNewCheckVersions() {
        var source = new DriverManagerDataSource(
                System.getenv().getOrDefault("AGENTFLOW_CORRECTION_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_CORRECTION_MIGRATION_USER", "sa"),
                System.getenv().getOrDefault("AGENTFLOW_CORRECTION_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("128").load().migrate();
        var jdbc = new JdbcTemplate(source);
        String app = UUID.randomUUID().toString(), report = UUID.randomUUID().toString(), original = UUID.randomUUID().toString(), run = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,'retained','EXP-OLD','fixture',1,'alice','保留申请','{}','DRAFT',1,1,'EXPENSE',?)", app, report);
        jdbc.update("INSERT INTO expense_report(id,tenant_id,application_id,employee_id,version,state_json) VALUES(?,'retained',?,'alice',1,'{\"retained\":true}')", report, app);
        jdbc.update("INSERT INTO expense_report_revision(tenant_id,report_id,financial_version,actor_id,operation,state_json) VALUES('retained',?,1,'alice','CREATE','{\"retained\":true}')", report);
        check(jdbc, original, report, app, 1);
        jdbc.update("""
                INSERT INTO agent_precheck_explanation_run(tenant_id,id,report_id,application_id,requested_by,application_version,financial_version,
                    precheck_id,precheck_attempt,status,version,context_json,state_json,created_at)
                VALUES('retained',?,?,?,'alice',1,1,?,1,'COMPLETED',3,'{}','{\"retained\":true}',CURRENT_TIMESTAMP)
                """, run, report, app, original);
        var tables = List.of("approval_application", "expense_report", "expense_report_revision", "expense_precheck_job", "agent_precheck_explanation_run");
        var before = new LinkedHashMap<String, Object>();
        tables.forEach(table -> before.put(table, jdbc.queryForList("SELECT * FROM " + table)));
        var flyway = Flyway.configure().dataSource(source).target("129").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        tables.forEach(table -> assertThat(jdbc.queryForList("SELECT * FROM " + table)).as(table).isEqualTo(before.get(table)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_correction", Integer.class)).isZero();
        jdbc.update("INSERT INTO expense_report_revision(tenant_id,report_id,financial_version,actor_id,operation,state_json) VALUES('retained',?,2,'alice','REVISE','{}')", report);
        String next = UUID.randomUUID().toString(); check(jdbc, next, report, app, 2);
        String insert = "INSERT INTO expense_correction(tenant_id,run_id,report_id,application_id,application_version,financial_version,precheck_id,applied_by,applied_at) VALUES(?,?,?,?,2,2,?,?,CURRENT_TIMESTAMP)";
        assertThatThrownBy(() -> jdbc.update(insert, "retained", run, report, app, original, "alice")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "foreign", run, report, app, next, "alice")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "retained", run, report, app, next, "bob")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update(insert, "retained", run, report, app, next, "alice")).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(insert, "retained", run, report, app, next, "alice")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    private static void check(JdbcTemplate jdbc, String id, String report, String app, long version) {
        jdbc.update("""
                INSERT INTO expense_precheck_job(tenant_id,id,report_id,application_id,employee_id,application_version,financial_version,attempt_no,
                    input_json,state_json,version,status,lease_until,created_at,completed_at)
                VALUES('retained',?,?,?,'alice',?,?,?,'{}','{}',3,'BLOCKED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, id, report, app, version, version, version);
    }
}
