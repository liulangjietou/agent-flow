package io.agentflow.expense;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * V119 只增添原轮次预算依据，不补造历史审批，也不改写旧预算操作。
 * @author owlzhangfq@gmail.com
 */
class ExpenseBudgetReviewMigrationTest {
    @Test
    void upgradeKeepsExistingBudgetHistoryAndCreatesEmptyReviewTables() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).target("118").load().migrate(); var jdbc = new JdbcTemplate(source);
        String report = UUID.randomUUID().toString(), application = UUID.randomUUID().toString(), operation = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,'review-upgrade','BUDGET-REVIEW-UPGRADE','fixture',1,'alice','保留申请','{}','DRAFT',1,1,'EXPENSE',?)", application, report);
        jdbc.update("INSERT INTO expense_report(id,tenant_id,application_id,employee_id,version,state_json) VALUES(?,'review-upgrade',?,'alice',2,'{\"retained\":true}')", report, application);
        jdbc.update("INSERT INTO expense_report_revision(tenant_id,report_id,financial_version,actor_id,operation,state_json) SELECT tenant_id,id,version,employee_id,'CREATE',state_json FROM expense_report");
        jdbc.update("INSERT INTO budget_occupation(tenant_id,report_id,application_id,employee_id,target_digest,version,status,pending_operation_id,state_json) VALUES('review-upgrade',?,?,'alice',?,1,'UNFUNDED',?,'{}')", report, application, "a".repeat(64), operation);
        jdbc.update("INSERT INTO budget_operation(tenant_id,id,report_id,financial_version,input_json,command_digest,state_json,version,status,attempts,active_report_id,created_at,updated_at,next_attempt_at) VALUES('review-upgrade',?,?,2,'{}',?,'{}',1,'QUEUED',0,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", operation, report, "a".repeat(64), report);
        var oldReports = jdbc.queryForList("SELECT * FROM expense_report"); var oldBudgets = jdbc.queryForList("SELECT * FROM budget_operation");
        var flyway = Flyway.configure().dataSource(source).load(); flyway.migrate();
        assertThat(jdbc.queryForList("SELECT * FROM expense_report")).isEqualTo(oldReports);
        assertThat(jdbc.queryForList("SELECT * FROM budget_operation")).isEqualTo(oldBudgets);
        // 原测试只核对预算任务表，没有每轮人工例外的持久来源和状态。
        assertThat(jdbc.queryForList("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_NAME IN ('EXPENSE_BUDGET_REVIEW','EXPENSE_BUDGET_REVIEW_REVISION')", String.class))
                .containsExactlyInAnyOrder("EXPENSE_BUDGET_REVIEW", "EXPENSE_BUDGET_REVIEW_REVISION");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_budget_review", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_budget_review_revision", Integer.class)).isZero();
        assertThat(flyway.migrate().migrationsExecuted).isZero(); assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }
}
