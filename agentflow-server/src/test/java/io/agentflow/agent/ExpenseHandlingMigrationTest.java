package io.agentflow.agent;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 非空 V129 升级只增加办理与用量表；原申请不改写，活动记录必须绑定同租户本人。
 * @author owlzhangfq@gmail.com
 */
class ExpenseHandlingMigrationTest {
    @Test void upgradeRetainsOriginalFactsAndEnforcesOwnerAndActiveUniqueness() {
        var source = new DriverManagerDataSource(
                System.getenv().getOrDefault("AGENTFLOW_HANDLING_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_HANDLING_MIGRATION_USER", "sa"),
                System.getenv().getOrDefault("AGENTFLOW_HANDLING_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("129").load().migrate();
        var jdbc = new JdbcTemplate(source);
        String app = UUID.randomUUID().toString(), report = UUID.randomUUID().toString(), task = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,'retained','EXP-RETAIN','fixture',1,'alice','保留申请','{}','DRAFT',0,1,'EXPENSE',?)", app, report);
        jdbc.update("INSERT INTO expense_report(id,tenant_id,application_id,employee_id,version,state_json) VALUES(?,'retained',?,'alice',1,'{\"retained\":true}')", report, app);
        var before = jdbc.queryForList("SELECT * FROM expense_report");
        var flyway = Flyway.configure().dataSource(source).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT * FROM expense_report")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_execution_usage", Integer.class)).isZero();
        String insert = "INSERT INTO agent_expense_handling(tenant_id,id,report_id,application_id,owner_id,active_report_id,version,context_json,state_json) VALUES(?,?,?,?,?,?,1,'{}','{}')";
        assertThatThrownBy(() -> jdbc.update(insert, "foreign", task, report, app, "alice", report)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "retained", task, report, app, "bob", report)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update(insert, "retained", task, report, app, "alice", report)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(insert, "retained", UUID.randomUUID().toString(), report, app, "alice", report)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE agent_expense_handling SET active_report_id=NULL WHERE tenant_id='retained' AND id=?", task);
        assertThat(jdbc.update(insert, "retained", UUID.randomUUID().toString(), report, app, "alice", report)).isEqualTo(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }
}
