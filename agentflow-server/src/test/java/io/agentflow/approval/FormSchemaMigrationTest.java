package io.agentflow.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.JdbcDefinitionDraftRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 V6 升级不猜测旧 schema、不重写旧表单和轮次内容。
 * @author owlzhangfq@gmail.com
 */
class FormSchemaMigrationTest {
    @Test
    void upgradesExistingV6RowsWithoutInventingSchemasOrChangingPayloads() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:forms-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).target("6").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String definitionId = UUID.randomUUID().toString();
        String applicationId = UUID.randomUUID().toString();
        String graph = "{\"nodes\":[],\"edges\":[]}";
        String payload = "{\"old\":{\"note\":null},\"lines\":[{\"amount\":6000}],\"optional\":null}";
        jdbc.update("""
                INSERT INTO approval_definition (id,tenant_id,process_key,name,version,revision,status,graph_json)
                VALUES (?, 'demo', 'legacy', '旧定义', 1, 1, 'PUBLISHED', ?)
                """, definitionId, graph);
        jdbc.update("""
                INSERT INTO approval_application (id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version)
                VALUES (?, 'demo', 'OLD-BIZ', 'legacy', 1, 'alice', '旧申请', ?, 'IN_APPROVAL', 1, 2)
                """, applicationId, payload);
        jdbc.update("""
                INSERT INTO approval_submission_round (tenant_id,application_id,round_no,process_instance_id,definition_version,title,payload_json,submitted_by,submitted_at,status)
                VALUES ('demo', ?, 1, 'old-instance', 1, '旧申请', ?, 'alice', CURRENT_TIMESTAMP, 'IN_APPROVAL')
                """, applicationId, payload);
        var definitionBefore = jdbc.queryForMap("SELECT * FROM approval_definition WHERE id=?", definitionId);
        var applicationBefore = jdbc.queryForMap("SELECT * FROM approval_application WHERE id=?", applicationId);
        var roundBefore = jdbc.queryForMap("SELECT * FROM approval_submission_round WHERE application_id=?", applicationId);

        Flyway.configure().dataSource(dataSource).load().migrate();

        for (String table : java.util.List.of("approval_definition", "approval_application", "approval_submission_round")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE form_schema_json IS NULL", Integer.class)).isEqualTo(1);
        }
        var definitionAfter = jdbc.queryForMap("SELECT * FROM approval_definition WHERE id=?", definitionId);
        var applicationAfter = jdbc.queryForMap("SELECT * FROM approval_application WHERE id=?", applicationId);
        var roundAfter = jdbc.queryForMap("SELECT * FROM approval_submission_round WHERE application_id=?", applicationId);
        definitionAfter.remove("FORM_SCHEMA_JSON"); applicationAfter.remove("FORM_SCHEMA_JSON"); applicationAfter.remove("RUNTIME_DEFINITION_ID"); roundAfter.remove("FORM_SCHEMA_JSON");
        assertThat(definitionAfter).isEqualTo(definitionBefore);
        assertThat(applicationAfter).isEqualTo(applicationBefore);
        assertThat(roundAfter).isEqualTo(roundBefore);
        JsonUtil json = new JsonUtil(new ObjectMapper());
        assertThat(new JdbcDefinitionDraftRepository(jdbc, json).findPublished("demo", "legacy", 1).orElseThrow().formSchema()).isNull();
        var restoredApplication = new JdbcApplicationRepository(jdbc, json).findById("demo", UUID.fromString(applicationId)).orElseThrow();
        assertThat(restoredApplication.formSchema()).isNull();
        assertThat(restoredApplication.runtimeDefinitionId()).isNull();
        assertThat(new JdbcSubmissionRoundRepository(jdbc, json).findAll("demo", UUID.fromString(applicationId)).get(0).formSchema()).isNull();
    }
}
