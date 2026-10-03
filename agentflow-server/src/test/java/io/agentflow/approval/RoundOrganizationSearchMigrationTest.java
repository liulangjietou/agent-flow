package io.agentflow.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.JsonUtil;
import io.agentflow.organization.InitiatorContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 组织查询列仅从原轮次快照回填，保持原文、结论和迁移历史，不推测缺失组织。
 * @author owlzhangfq@gmail.com
 */
class RoundOrganizationSearchMigrationTest {
    @Test
    void upgradesV28PreservingOriginalRoundsAndLeavingUnknownNamesUnrecorded() {
        var source = new DriverManagerDataSource(
                System.getProperty("agentflow.round-organization-migration.jdbc-url", System.getenv().getOrDefault("AGENTFLOW_ROUND_ORGANIZATION_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")),
                System.getProperty("agentflow.round-organization-migration.jdbc-user", System.getenv().getOrDefault("AGENTFLOW_ROUND_ORGANIZATION_MIGRATION_USER", "sa")),
                System.getProperty("agentflow.round-organization-migration.jdbc-password", System.getenv().getOrDefault("AGENTFLOW_ROUND_ORGANIZATION_MIGRATION_PASSWORD", "")));
        Flyway.configure().dataSource(source).target("28").load().migrate();
        var jdbc = new JdbcTemplate(source);
        var json = new JsonUtil(new ObjectMapper());
        var context = new InitiatorContext(UUID.randomUUID(), UUID.randomUUID(), "applicant", 17,
                UUID.randomUUID(), "原法人", UUID.randomUUID(), "原部门", UUID.randomUUID(), "原岗位");
        String[] contexts = {json.write(context), null, "broken-json", "null", "{\"departmentName\":\"片段\"}"};
        for (int index = 0; index < contexts.length; index++) {
            String id = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version)
                    VALUES(?,'retained',?,'legacy',1,'applicant','旧申请','{}','IN_APPROVAL',1,2)
                    """, id, "OLD-" + index);
            jdbc.update("""
                    INSERT INTO approval_submission_round(tenant_id,application_id,round_no,process_instance_id,definition_version,title,payload_json,submitted_by,submitted_at,status,initiator_context_json)
                    VALUES('retained',?,1,?,1,?,'{}','applicant',CURRENT_TIMESTAMP,'IN_APPROVAL',?)
                    """, id, "instance-" + index, "旧轮次-" + index, contexts[index]);
        }
        var before = jdbc.queryForList("SELECT * FROM approval_submission_round ORDER BY title");
        var applications = jdbc.queryForList("SELECT * FROM approval_application ORDER BY business_no");
        var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        assertThat(Flyway.configure().dataSource(source).target("29").load().migrate().migrationsExecuted).isEqualTo(1);
        var after = jdbc.queryForList("SELECT * FROM approval_submission_round ORDER BY title");
        assertThat(after.get(0).get("INITIATOR_LEGAL_ENTITY_NAME")).isEqualTo("原法人");
        assertThat(after.get(0).get("INITIATOR_DEPARTMENT_NAME")).isEqualTo("原部门");
        assertThat(after.get(0).get("INITIATOR_POSITION_NAME")).isEqualTo("原岗位");
        for (String column : List.of("INITIATOR_LEGAL_ENTITY_NAME", "INITIATOR_DEPARTMENT_NAME", "INITIATOR_POSITION_NAME")) {
            assertThat(after.subList(1, after.size())).allSatisfy(row -> assertThat(row.get(column)).isNull());
            after.forEach(row -> row.remove(column));
        }
        assertThat(after).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM approval_application ORDER BY business_no")).isEqualTo(applications);
        var migratedHistory = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        assertThat(migratedHistory).hasSize(history.size() + 1);
        assertThat(migratedHistory.subList(0, history.size())).isEqualTo(history);
        assertThat(Flyway.configure().dataSource(source).target("29").load().migrate().migrationsExecuted).isZero();
    }
}
