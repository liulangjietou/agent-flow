package io.agentflow.attachment;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * V29 升级仅增加附件存储关系；验证旧申请原文和跨租户复合外键。
 * @author owlzhangfq@gmail.com
 */
class AttachmentMigrationTest {
    @Test
    void upgradesWithoutInventingOldAttachmentsAndEnforcesTenantFieldAndRoundReferences() {
        var source = new DriverManagerDataSource(
                System.getenv().getOrDefault("AGENTFLOW_ATTACHMENT_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_ATTACHMENT_MIGRATION_USER", "sa"),
                System.getenv().getOrDefault("AGENTFLOW_ATTACHMENT_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("29").load().migrate();
        var jdbc = new JdbcTemplate(source);
        String app = UUID.randomUUID().toString(), attachment = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version)
                VALUES(?,'retained','OLD-1','legacy',1,'applicant','原申请','{"proof":"旧文本"}','IN_APPROVAL',1,2)
                """, app);
        jdbc.update("""
                INSERT INTO approval_submission_round(tenant_id,application_id,round_no,process_instance_id,definition_version,title,payload_json,submitted_by,submitted_at,status)
                VALUES('retained',?,1,'instance',1,'原轮次','{"proof":"旧文本"}','applicant',CURRENT_TIMESTAMP,'IN_APPROVAL')
                """, app);
        var before = jdbc.queryForList("SELECT * FROM approval_application");
        var rounds = jdbc.queryForList("SELECT * FROM approval_submission_round");
        var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        var flyway = Flyway.configure().dataSource(source).target("30").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM approval_application")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM approval_submission_round")).isEqualTo(rounds);
        assertThat(jdbc.queryForList("SELECT * FROM approval_attachment")).isEmpty();
        assertThat(jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"").subList(0, history.size())).isEqualTo(history);
        String insert = "INSERT INTO approval_attachment(id,tenant_id,application_id,field_path,filename,byte_size,sha256,created_by,created_at,status) VALUES(?,?,?,'proof','file.bin',0,?,'applicant',CURRENT_TIMESTAMP,'READY')";
        assertThatThrownBy(() -> jdbc.update(insert, attachment, "foreign", app, "0".repeat(64)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update(insert, attachment, "retained", app, "0".repeat(64));
        String freeze = "INSERT INTO approval_attachment_round(tenant_id,application_id,round_no,field_path,attachment_id) VALUES('retained',?,?,?,?)";
        assertThatThrownBy(() -> jdbc.update(freeze, app, 1, "another", attachment)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(freeze, app, 2, "proof", attachment)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update(freeze, app, 1, "proof", attachment);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }
}
