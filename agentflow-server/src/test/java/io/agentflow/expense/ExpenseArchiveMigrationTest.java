package io.agentflow.expense;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * V49 结算与历史完整保留，归档外键不能跨租户、轮次或不存在的修订。
 * @author owlzhangfq@gmail.com
 */
class ExpenseArchiveMigrationTest {
    @Test void upgradePreservesSettlementsAndArchiveReferencesCannotInventOriginalEvidence() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_ARCHIVE_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_ARCHIVE_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_ARCHIVE_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("49").load().migrate(); var jdbc = new JdbcTemplate(source);
        String report = UUID.randomUUID().toString(), app = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id)
                VALUES(?,'archive-upgrade','OLD-ARCHIVE','fixture',1,'alice','原报销','{}','APPROVED',1,6,'EXPENSE',?)
                """, app, report);
        jdbc.update("INSERT INTO expense_report(tenant_id,id,application_id,employee_id,version,state_json) VALUES('archive-upgrade',?,?,'alice',2,'{\"originalReport\":true}')", report, app);
        jdbc.update("""
                INSERT INTO expense_settlement(tenant_id,report_id,application_id,round_no,application_version,financial_version,input_json,state_json,version,status,resources_consumed,created_at,updated_at)
                VALUES('archive-upgrade',?,?,1,6,2,'{"originalInput":true}','{"queued":true}',1,'QUEUED',FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, report, app);
        jdbc.update("INSERT INTO expense_settlement_revision(tenant_id,report_id,version,state_json) VALUES('archive-upgrade',?,1,'{\"queued\":true}')", report);
        var before = jdbc.queryForList("SELECT * FROM expense_settlement ORDER BY report_id");
        var history = jdbc.queryForList("SELECT * FROM expense_settlement_revision ORDER BY report_id,version");
        var upgraded = Flyway.configure().dataSource(source).target("50").load(); assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM expense_settlement ORDER BY report_id")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM expense_settlement_revision ORDER BY report_id,version")).isEqualTo(history);
        String insert = "INSERT INTO expense_archive(tenant_id,report_id,round_no,settlement_version,issue,checked_at) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP)";
        assertThatThrownBy(() -> jdbc.update(insert, "foreign", report, 1, 1, "ARCHIVE_CHECK_PENDING")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "archive-upgrade", report, 2, 1, "ARCHIVE_CHECK_PENDING")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "archive-upgrade", report, 1, 2, "ARCHIVE_CHECK_PENDING")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "archive-upgrade", report, 1, 1, null)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, "archive-upgrade", report, 1, 1, "ARCHIVE_CHECK_PENDING");
        assertThatThrownBy(() -> jdbc.update(insert, "archive-upgrade", report, 1, 1, "ARCHIVE_CHECK_PENDING")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE expense_archive SET archived_at=checked_at,issue=NULL WHERE tenant_id='archive-upgrade' AND report_id=?", report)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO expense_archive_original(tenant_id,report_id,round_no,original_id) VALUES('archive-upgrade',?,1,?)", report, UUID.randomUUID().toString())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM expense_settlement_revision WHERE tenant_id='archive-upgrade' AND report_id=?", report)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(upgraded.migrate().migrationsExecuted).isZero(); assertThat(upgraded.validateWithResult().validationSuccessful).isTrue();
    }
}
