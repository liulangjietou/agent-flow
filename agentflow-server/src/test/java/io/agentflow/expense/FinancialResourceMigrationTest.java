package io.agentflow.expense;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V33 升级不改原报销或追加证据；金额归属和发票占用都有租户数据库约束。
 * @author owlzhangfq@gmail.com
 */
class FinancialResourceMigrationTest {
    @Test
    void upgradePreservesExpenseFactsAndConstrainsFinancialResourceOwnership() {
        var source = new DriverManagerDataSource(
                System.getenv().getOrDefault("AGENTFLOW_RESOURCE_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_RESOURCE_MIGRATION_USER", "sa"),
                System.getenv().getOrDefault("AGENTFLOW_RESOURCE_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("33").load().migrate();
        var jdbc = new JdbcTemplate(source); String app = UUID.randomUUID().toString(), report = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,'retained','BEFORE-34','structured-expense',1,'alice','费用草稿','{}','DRAFT',1,1,'EXPENSE',?)", app, report);
        jdbc.update("INSERT INTO expense_report(id,tenant_id,application_id,employee_id,version,state_json) VALUES(?,'retained',?,'alice',1,'{\"evidence\":\"original\"}')", report, app);
        jdbc.update("INSERT INTO expense_report_revision(tenant_id,report_id,financial_version,actor_id,operation,state_json) SELECT tenant_id,id,version,employee_id,'CREATE',state_json FROM expense_report");
        var before = jdbc.queryForList("SELECT * FROM expense_report"); var journal = jdbc.queryForList("SELECT * FROM expense_report_revision");
        var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        var flyway = Flyway.configure().dataSource(source).target("34").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM expense_report")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM expense_report_revision")).isEqualTo(journal);
        assertThat(jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"").subList(0, history.size())).isEqualTo(history);
        for (String table : new String[]{"finance_resource", "finance_resource_revision", "invoice_active_claim", "finance_amount_use"}) {
            assertThat(jdbc.queryForList("SELECT * FROM " + table)).isEmpty();
        }
        String invoice = UUID.randomUUID().toString(), advance = UUID.randomUUID().toString();
        String resource = "INSERT INTO finance_resource(tenant_id,resource_type,id,owner_id,source_reference,version,context_json,state_json) VALUES(?, ?,?,'alice',?,1,'{}','{}')";
        jdbc.update(resource, "retained", "INVOICE", invoice, "file-1");
        jdbc.update(resource, "retained", "ADVANCE", advance, "payment-1");
        assertThatThrownBy(() -> jdbc.update(resource, "retained", "ADVANCE", UUID.randomUUID().toString(), "payment-1")).isInstanceOf(DataIntegrityViolationException.class);
        String claim = "INSERT INTO invoice_active_claim(tenant_id,invoice_key,invoice_id,report_id,round_no,line_no,status) VALUES(?,'D:00000000000000000001',?,?,1,1,?)";
        assertThatThrownBy(() -> jdbc.update(claim, "foreign", invoice, report, "OCCUPIED")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(claim, "retained", advance, report, "OCCUPIED")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(claim, "retained", invoice, report, "RELEASED")).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(claim, "retained", invoice, report, "CONSUMED");
        assertThatThrownBy(() -> jdbc.update(claim, "retained", invoice, report, "OCCUPIED")).isInstanceOf(DataIntegrityViolationException.class);
        String amount = "INSERT INTO finance_amount_use(tenant_id,resource_type,resource_id,source_line,report_id,round_no,report_line,amount,currency,status) VALUES(?,'ADVANCE',?,0,?,1,?,?,'CNY','RESERVED')";
        assertThatThrownBy(() -> jdbc.update(amount, "foreign", advance, report, 0, 1)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(amount, "retained", advance, report, 1, 1)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(amount, "retained", advance, report, 0, -1)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(amount, "retained", advance, report, 0, 1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }
}
