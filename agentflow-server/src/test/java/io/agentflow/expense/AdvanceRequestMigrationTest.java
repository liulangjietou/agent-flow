package io.agentflow.expense;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * V41 升级保留旧申请和报销，新增借款申请与预检仍由精确业务绑定和版本外键约束。
 * @author owlzhangfq@gmail.com
 */
class AdvanceRequestMigrationTest {
    @Test void upgradePreservesExistingRowsAndRejectsCrossBusinessOrForeignCheckBindings() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_ADVANCE_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_ADVANCE_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_ADVANCE_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("41").load().migrate(); var jdbc = new JdbcTemplate(source);
        String legacy = UUID.randomUUID().toString(), expenseApp = UUID.randomUUID().toString(), expense = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version) VALUES(?,'advance-upgrade','LEGACY','fixture',1,'alice','旧表单','{}','DRAFT',1,1)", legacy);
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,'advance-upgrade','EXPENSE','fixture',1,'alice','旧报销','{}','DRAFT',1,1,'EXPENSE',?)", expenseApp, expense);
        jdbc.update("INSERT INTO expense_report(id,tenant_id,application_id,employee_id,version,state_json) VALUES(?,'advance-upgrade',?,'alice',1,'{\"retained\":true}')", expense, expenseApp);
        var applications = jdbc.queryForList("SELECT * FROM approval_application ORDER BY id"); var reports = jdbc.queryForList("SELECT * FROM expense_report ORDER BY id");
        var upgraded = Flyway.configure().dataSource(source).target("42").load(); assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM approval_application ORDER BY id")).isEqualTo(applications);
        assertThat(jdbc.queryForList("SELECT * FROM expense_report ORDER BY id")).isEqualTo(reports);
        String plan = UUID.randomUUID().toString();
        jdbc.update("UPDATE approval_application SET business_type='ADVANCE_REQUEST',business_id=? WHERE id=?", plan, legacy);
        String insert = "INSERT INTO advance_request(id,tenant_id,application_id,employee_id,version,state_json) VALUES(?,?,?,'alice',1,'{}')";
        assertThatThrownBy(() -> jdbc.update(insert, expense, "advance-upgrade", expenseApp)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, plan, "foreign", legacy)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, plan, "advance-upgrade", legacy);
        assertThatThrownBy(() -> jdbc.update("UPDATE approval_application SET business_type='UNKNOWN' WHERE id=?", legacy)).isInstanceOf(DataIntegrityViolationException.class);
        String queued = "INSERT INTO advance_request_check_job(tenant_id,id,request_id,application_id,employee_id,application_version,request_version,attempt_no,input_json,state_json,version,status,active_request_id,created_at) VALUES('advance-upgrade',?,?,?, ?,1,1,1,'{}','{}',1,'QUEUED',?,CURRENT_TIMESTAMP)";
        assertThatThrownBy(() -> jdbc.update(queued, UUID.randomUUID().toString(), plan, legacy, "alice", plan)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO advance_request_revision(tenant_id,request_id,request_version,actor_id,operation,state_json) VALUES('advance-upgrade',?,1,'alice','CREATE','{}')", plan);
        assertThatThrownBy(() -> jdbc.update(queued, UUID.randomUUID().toString(), plan, legacy, "bob", plan)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(queued, UUID.randomUUID().toString(), plan, legacy, "alice", plan);
        assertThatThrownBy(() -> jdbc.update(queued, UUID.randomUUID().toString(), plan, legacy, "alice", plan)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(upgraded.migrate().migrationsExecuted).isZero(); assertThat(upgraded.validateWithResult().validationSuccessful).isTrue();
    }
}
