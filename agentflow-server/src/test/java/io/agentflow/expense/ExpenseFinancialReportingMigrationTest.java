package io.agentflow.expense;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/**
 * V121 保留原业务列，只添加新的拒绝记录与真实记录启用时点。
 * @author owlzhangfq@gmail.com
 */
class ExpenseFinancialReportingMigrationTest {
    @Test void nonemptyV120UpgradeRetainsAllOriginalColumnsAndStartsEmptyFailureCoverage() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:report-upgrade-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        try {
            Flyway.configure().dataSource(source).target("120").load().migrate();
            String report = UUID.randomUUID().toString(), application = UUID.randomUUID().toString();
            String operation = UUID.randomUUID().toString(), precheck = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,'report-upgrade','PROJECT-UPGRADE','fixture',1,'alice','保留申请','{}','IN_APPROVAL',1,3,'EXPENSE',?)", application, report);
            jdbc.update("INSERT INTO approval_submission_round(tenant_id,application_id,round_no,process_instance_id,definition_version,title,payload_json,submitted_by,submitted_at,status) VALUES('report-upgrade',?,1,'old-project-instance',1,'保留原轮次','{}','alice',CURRENT_TIMESTAMP,'IN_APPROVAL')", application);
            jdbc.update("INSERT INTO expense_report(id,tenant_id,application_id,employee_id,version,state_json) VALUES(?,'report-upgrade',?,'alice',2,'{\"retained\":true}')", report, application);
            jdbc.update("INSERT INTO expense_report_revision(tenant_id,report_id,financial_version,actor_id,operation,state_json) SELECT tenant_id,id,version,employee_id,'CREATE',state_json FROM expense_report");
            jdbc.update("INSERT INTO budget_occupation(tenant_id,report_id,application_id,employee_id,target_digest,version,status,pending_operation_id,state_json) VALUES('report-upgrade',?,?,'alice',?,1,'UNFUNDED',?,'{}')", report, application, "a".repeat(64), operation);
            jdbc.update("INSERT INTO budget_operation(tenant_id,id,report_id,financial_version,input_json,command_digest,state_json,version,status,attempts,active_report_id,created_at,updated_at,next_attempt_at) VALUES('report-upgrade',?,?,2,'{}',?,'{}',1,'QUEUED',0,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", operation, report, "a".repeat(64), report);
            jdbc.update("INSERT INTO expense_precheck_job(tenant_id,id,report_id,application_id,employee_id,application_version,financial_version,attempt_no,input_json,state_json,version,status,created_at,lease_until,completed_at) VALUES('report-upgrade',?,?,?,'alice',1,2,1,'{}','{}',3,'READY',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", precheck, report, application);
            jdbc.update("INSERT INTO expense_precheck_revision(tenant_id,job_id,version,state_json) VALUES('report-upgrade',?,3,'{}')", precheck);
            var tables = List.of("approval_application", "approval_submission_round", "expense_report", "expense_report_revision",
                    "budget_occupation", "budget_operation", "expense_precheck_job", "expense_precheck_revision");
            // 固定升级前的全部列；后续迁移可增加列，但不能修改任何原值或丢失原记录。
            var originalQueries = tables.stream().map(table -> "SELECT "
                    + String.join(",", jdbc.queryForMap("SELECT * FROM " + table).keySet()) + " FROM " + table).toList();
            var before = originalQueries.stream().map(jdbc::queryForList).toList();
            var flyway = Flyway.configure().dataSource(source).load(); flyway.migrate();
            assertThat(originalQueries.stream().map(jdbc::queryForList).toList()).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT trace_id FROM budget_operation", String.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT trace_id FROM expense_precheck_job", String.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_submission_rejection", Integer.class)).isZero();
            var started = jdbc.queryForObject("SELECT started_at FROM expense_reporting_coverage WHERE fact_type='DUPLICATE_SUBMISSION'", java.time.OffsetDateTime.class);
            assertThat(started).isNotNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_reporting_coverage", Integer.class)).isOne();
            assertThatThrownBy(() -> jdbc.update("INSERT INTO expense_reporting_coverage(fact_type) VALUES('DUPLICATE_SUBMISSION')"))
                    .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
            assertThat(flyway.migrate().migrationsExecuted).isZero(); assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        } finally { jdbc.execute("SHUTDOWN"); }
    }
}
