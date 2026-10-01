package io.agentflow.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.approval.model.SubmissionRisk;
import io.agentflow.common.JsonUtil;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 非空 V93 升级只追加风险列，旧轮次与迁移原文保留，不推断已有申请等级。
 * @author owlzhangfq@gmail.com
 */
class SubmissionRiskMigrationTest {
    @Test
    void upgradeKeepsOriginalRowsUnassessedAndRequiresPairedRiskColumns() {
        var source = new DriverManagerDataSource(
                System.getenv().getOrDefault("AGENTFLOW_RISK_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_RISK_MIGRATION_USER", "sa"),
                System.getenv().getOrDefault("AGENTFLOW_RISK_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("93").load().migrate();
        var jdbc = new JdbcTemplate(source);
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version)
                VALUES(?,'retained','OLD-RISK','legacy',1,'applicant','旧申请','{"amount":"999999"}','IN_APPROVAL',1,2)
                """, id);
        jdbc.update("""
                INSERT INTO approval_submission_round(tenant_id,application_id,round_no,process_instance_id,definition_version,title,payload_json,submitted_by,submitted_at,status)
                VALUES('retained',?,1,'old-instance',1,'原始轮次','{"amount":"999999"}','applicant',CURRENT_TIMESTAMP,'IN_APPROVAL')
                """, id);
        var before = jdbc.queryForList("SELECT * FROM approval_submission_round");
        var applications = jdbc.queryForList("SELECT * FROM approval_application");
        var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        assertThat(Flyway.configure().dataSource(source).target("94").load().migrate().migrationsExecuted).isEqualTo(1);
        var after = jdbc.queryForList("SELECT * FROM approval_submission_round");
        after.forEach(row -> row.entrySet().removeIf(entry -> {
            if (!entry.getKey().equalsIgnoreCase("risk_level") && !entry.getKey().equalsIgnoreCase("risk_json")) return false;
            assertThat(entry.getValue()).isNull();
            return true;
        }));
        assertThat(after).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM approval_application")).isEqualTo(applications);
        var upgradedHistory = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        assertThat(upgradedHistory).hasSize(history.size() + 1);
        assertThat(upgradedHistory.subList(0, history.size())).isEqualTo(history);
        var repository = new JdbcSubmissionRoundRepository(jdbc, new JsonUtil(new ObjectMapper().findAndRegisterModules()));
        assertThat(repository.findByRound("retained", UUID.fromString(id), 1).orElseThrow().risk()).isEqualTo(SubmissionRisk.unassessed());
        assertThatThrownBy(() -> jdbc.update("UPDATE approval_submission_round SET risk_level='LOW'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE approval_submission_round SET risk_json='{}'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(Flyway.configure().dataSource(source).target("94").load().migrate().migrationsExecuted).isZero();
    }
}
