package io.agentflow.expense;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本人分页升级保留已有审批、费用和资金事实，重复运行不会改写业务数据。
 * @author owlzhangfq@gmail.com
 */
class ExpenseWorkspaceMigrationTest {
    @Test
    void upgradeRetainsExistingOwnedRowsAndPaginationOrder() {
        var source = new DriverManagerDataSource(
                System.getenv().getOrDefault("AGENTFLOW_EXPENSE_WORKSPACE_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_EXPENSE_WORKSPACE_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_EXPENSE_WORKSPACE_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("39").load().migrate(); var jdbc = new JdbcTemplate(source);
        for (String owner : List.of("alice", "bob")) {
            String report = UUID.randomUUID().toString(), application = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,'workspace-upgrade',?,'fixture',1,?,'保留申请','{}','DRAFT',1,1,'EXPENSE',?)", application, owner, owner, report);
            jdbc.update("INSERT INTO expense_report(id,tenant_id,application_id,employee_id,version,state_json) VALUES(?,'workspace-upgrade',?,?,1,'{\"retained\":true}')", report, application, owner);
            jdbc.update("INSERT INTO finance_resource(tenant_id,resource_type,id,owner_id,source_reference,version,context_json,state_json) VALUES('workspace-upgrade','ADVANCE',?,?,?,1,'{}','{\"retained\":true}')", UUID.randomUUID().toString(), owner, "synthetic-" + owner);
        }
        var reports = jdbc.queryForList("SELECT * FROM expense_report ORDER BY created_at,id");
        var resources = jdbc.queryForList("SELECT * FROM finance_resource ORDER BY id");
        var applications = jdbc.queryForList("SELECT * FROM approval_application ORDER BY id");
        var flyway = Flyway.configure().dataSource(source).target("40").load(); assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM expense_report ORDER BY created_at,id")).isEqualTo(reports);
        assertThat(jdbc.queryForList("SELECT * FROM finance_resource ORDER BY id")).isEqualTo(resources);
        assertThat(jdbc.queryForList("SELECT * FROM approval_application ORDER BY id")).isEqualTo(applications);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_report WHERE tenant_id='workspace-upgrade' AND employee_id='alice'", Integer.class)).isEqualTo(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero(); assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }
}
