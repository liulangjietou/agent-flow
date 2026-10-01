package io.agentflow.approval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 非空 V89 升级保留已有结论；新增取消仍必须携带明确执行身份和完成时间。
 * @author owlzhangfq@gmail.com
 */
class SubprocessCancellationMigrationTest {
    @Test
    void preservesEveryExistingRoundAndRequiresCompleteCancellationEvidence() {
        var source = new DriverManagerDataSource(
                System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_CANCELLATION_MIGRATION_URL",
                        "jdbc:h2:mem:subprocess-cancel-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_START_USER", "sa"),
                System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_START_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("89").load().migrate();
        var jdbc = new JdbcTemplate(source);
        String tenant = "cancel-upgrade-" + UUID.randomUUID(), active = UUID.randomUUID().toString();
        for (String status : List.of("IN_APPROVAL", "APPROVED", "RETURNED", "REJECTED", "WITHDRAWN")) {
            String id = status.equals("IN_APPROVAL") ? active : UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO approval_application (id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version)
                    VALUES (?,?,?,'legacy',1,'alice','旧申请','{"note":"保留原文"}',?,1,2)
                    """, id, tenant, "old-" + id, status);
            jdbc.update("""
                    INSERT INTO approval_submission_round (tenant_id,application_id,round_no,process_instance_id,definition_version,title,payload_json,submitted_by,submitted_at,status)
                    VALUES (?,?,1,?,1,'旧轮次','{"note":"固定快照"}','alice',CURRENT_TIMESTAMP,'IN_APPROVAL')
                    """, tenant, id, "instance-" + id);
            if (!status.equals("IN_APPROVAL")) jdbc.update("""
                    UPDATE approval_submission_round SET status=?,reason='原理由',completed_by='manager',completed_at=CURRENT_TIMESTAMP
                    WHERE tenant_id=? AND application_id=?
                    """, status, tenant, id);
        }
        var before = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : List.of("approval_application", "approval_submission_round", "approval_subprocess_call", "approval_subprocess_attachment")) {
            before.put(table, jdbc.queryForList("SELECT * FROM " + table));
        }
        var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        var upgraded = Flyway.configure().dataSource(source).target("90").load();
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
        before.forEach((table, rows) -> assertThat(jdbc.queryForList("SELECT * FROM " + table)).containsExactlyInAnyOrderElementsOf(rows));
        assertThat(jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" WHERE \"version\" IS NULL OR \"version\"<>'90' ORDER BY \"installed_rank\""))
                .isEqualTo(history);
        assertThatThrownBy(() -> jdbc.update("UPDATE approval_submission_round SET status='CANCELLED' WHERE tenant_id=? AND application_id=?", tenant, active))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM approval_submission_round WHERE tenant_id=? AND application_id=?", String.class, tenant, active))
                .isEqualTo("IN_APPROVAL");
        assertThat(jdbc.update("""
                UPDATE approval_submission_round SET status='CANCELLED',reason='Parent stopped',completed_by='system:subprocess',completed_at=CURRENT_TIMESTAMP
                WHERE tenant_id=? AND application_id=?
                """, tenant, active)).isEqualTo(1);
        assertThat(upgraded.migrate().migrationsExecuted).isZero();
        assertThat(upgraded.validateWithResult().validationSuccessful).isTrue();
    }
}
