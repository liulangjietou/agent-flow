package io.agentflow.expense;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 非空 V82、V83 的原财务修订不被迁移改写；新部分调整与完成必须引用实际同租户修订。
 * @author owlzhangfq@gmail.com
 */
class ExpensePartialAdjustmentMigrationTest {
    @Test void upgradePreservesOriginalFinanceAndConstrainsAdjustmentEvidence() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_PARTIAL_ADJUSTMENT_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_PARTIAL_ADJUSTMENT_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_PARTIAL_ADJUSTMENT_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("82").load().migrate(); var jdbc = new JdbcTemplate(source);
        String tenant = "partial-upgrade", app = UUID.randomUUID().toString(), report = UUID.randomUUID().toString(), budget = UUID.randomUUID().toString(), voucher = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id)
                VALUES(?,?,'OLD-PARTIAL','fixture',1,'alice','原报销','{}','APPROVED',1,6,'EXPENSE',?)
                """, app, tenant, report);
        jdbc.update("INSERT INTO expense_report(tenant_id,id,application_id,employee_id,version,state_json) VALUES(?,?,?,'alice',2,'{\"originalReport\":true}')", tenant, report, app);
        jdbc.update("INSERT INTO expense_report_revision(tenant_id,report_id,financial_version,actor_id,operation,state_json) VALUES(?,?,2,'alice','SETTLED','{\"originalReport\":true}')", tenant, report);
        jdbc.update("INSERT INTO budget_occupation(tenant_id,report_id,employee_id,application_id,target_digest,version,status,state_json) VALUES(?,?,'alice',?,?,3,'CONSUMED','{\"originalConsumed\":true}')", tenant, report, app, "a".repeat(64));
        jdbc.update("""
                INSERT INTO budget_operation(tenant_id,id,report_id,financial_version,input_json,command_digest,state_json,version,status,attempts,created_at,updated_at)
                VALUES(?,?,?,2,'{"originalCommand":true}',?,'{"originalBudget":true}',3,'APPLIED',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, tenant, budget, report, "a".repeat(64));
        jdbc.update("INSERT INTO budget_operation_revision(tenant_id,operation_id,version,state_json) VALUES(?,?,3,'{\"originalBudget\":true}')", tenant, budget);
        jdbc.update("""
                INSERT INTO voucher_operation(tenant_id,id,business_type,business_id,application_id,round_no,kind,application_version,business_version,input_json,command_digest,state_json,
                version,status,attempts,highest_revision,created_at,updated_at) VALUES(?,?,'EXPENSE',?,?,1,'EXPENSE_ACCRUAL',6,2,'{"originalCommand":true}',?,'{"originalAccrual":true}',3,'POSTED',1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, tenant, voucher, report, app, "b".repeat(64));
        jdbc.update("INSERT INTO voucher_operation_revision(tenant_id,operation_id,version,state_json) VALUES(?,?,3,'{\"originalAccrual\":true}')", tenant, voucher);
        jdbc.update("""
                INSERT INTO expense_settlement(tenant_id,report_id,application_id,round_no,application_version,financial_version,voucher_operation_id,input_json,state_json,version,status,resources_consumed,budget_operation_id,created_at,updated_at)
                VALUES(?,?,?,1,6,2,?,'{"originalInput":true}','{"originalSettlement":true}',4,'SETTLED',TRUE,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, tenant, report, app, voucher, budget);
        jdbc.update("INSERT INTO expense_settlement_revision(tenant_id,report_id,version,state_json) VALUES(?,?,4,'{\"originalSettlement\":true}')", tenant, report);
        var before = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : List.of("approval_application", "expense_report", "expense_report_revision", "budget_occupation", "budget_operation", "budget_operation_revision",
                "voucher_operation", "voucher_operation_revision", "expense_settlement", "expense_settlement_revision")) before.put(table, jdbc.queryForList("SELECT * FROM " + table));

        var upgraded = Flyway.configure().dataSource(source).target("83").load(); assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
        before.forEach((table, rows) -> assertThat(jdbc.queryForList("SELECT * FROM " + table)).as(table).containsExactlyInAnyOrderElementsOf(rows));
        String id = UUID.randomUUID().toString();
        String insert = """
                INSERT INTO expense_partial_adjustment(tenant_id,id,report_id,round_no,sequence_no,settlement_version,consumption_id,consumed_version,accrual_id,accrual_version,
                input_json,state_json,version,status,active_report_id,created_at,updated_at) VALUES(?,?,?,1,1,4,?,3,?,3,'{}','{}',1,'WAITING_FINANCE',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """;
        assertThatThrownBy(() -> jdbc.update(insert, "foreign", id, report, budget, voucher, report)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, tenant, id, report, UUID.randomUUID().toString(), voucher, report)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, tenant, id, report, budget, voucher, report);
        assertThatThrownBy(() -> jdbc.update(insert, tenant, UUID.randomUUID().toString(), report, budget, voucher, report)).isInstanceOf(DataIntegrityViolationException.class);
        for (String mutation : List.of("active_report_id=NULL", "consumed_version=2", "accrual_version=2", "settlement_version=3", "status='APPLIED'",
                "sequence_no=2,previous_id='missing',previous_version=1", "completed_at=CURRENT_TIMESTAMP,active_report_id=NULL,status='APPLIED'")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE expense_partial_adjustment SET " + mutation + " WHERE tenant_id=? AND id=?", tenant, id)).as(mutation).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.update("DELETE FROM budget_operation_revision WHERE tenant_id=? AND operation_id=?", tenant, budget)).isInstanceOf(DataIntegrityViolationException.class);
        String command = "INSERT INTO expense_partial_adjustment_operation(tenant_id,id,adjustment_id,side,adjustment_version,input_json,authorization_source_json,created_at) VALUES(?,?,?,'BUDGET',1,'{}','{}',CURRENT_TIMESTAMP)";
        String operation = UUID.randomUUID().toString();
        assertThatThrownBy(() -> jdbc.update(command, tenant, operation, id)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO expense_partial_adjustment_revision(tenant_id,adjustment_id,version,state_json) VALUES(?,?,1,'{}')", tenant, id);
        jdbc.update(command, tenant, operation, id);
        assertThatThrownBy(() -> jdbc.update(command, tenant, operation, id)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(upgraded.migrate().migrationsExecuted).isZero(); assertThat(upgraded.validateWithResult().validationSuccessful).isTrue();
        var oldAdjustments = jdbc.queryForList("SELECT * FROM expense_partial_adjustment");
        var oldOperations = jdbc.queryForList("SELECT * FROM expense_partial_adjustment_operation");
        var completed = Flyway.configure().dataSource(source).target("84").load(); assertThat(completed.migrate().migrationsExecuted).isEqualTo(1);
        before.forEach((table, rows) -> assertThat(jdbc.queryForList("SELECT * FROM " + table)).as(table).containsExactlyInAnyOrderElementsOf(rows));
        assertThat(jdbc.queryForList("SELECT * FROM expense_partial_adjustment")).containsExactlyInAnyOrderElementsOf(oldAdjustments);
        assertThat(jdbc.queryForList("SELECT * FROM expense_partial_adjustment_operation")).containsExactlyInAnyOrderElementsOf(oldOperations);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO expense_partial_adjustment_completion(tenant_id,adjustment_id,before_version,after_version,source_json,completed_at) VALUES(?,?,1,2,'{}',CURRENT_TIMESTAMP)", tenant, id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(completed.migrate().migrationsExecuted).isZero(); assertThat(completed.validateWithResult().validationSuccessful).isTrue();
    }
}
