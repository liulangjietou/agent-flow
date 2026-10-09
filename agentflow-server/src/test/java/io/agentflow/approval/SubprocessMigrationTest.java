package io.agentflow.approval;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentflow.attachment.JdbcAttachmentRepository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 非空 V88 升级保留旧申请、轮次和原件；共享引用不增加物理备份文件，也不改变旧清单。
 *
 * @author owlzhangfq@gmail.com
 */
class SubprocessMigrationTest {
    @Test
    void preservesOriginalEvidenceAndListsPhysicalFilesOnceAfterUpgrade() {
        var source = new DriverManagerDataSource(
                System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_MIGRATION_URL", "jdbc:h2:mem:subprocess-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_PERSISTENCE_USER", "sa"),
                System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_PERSISTENCE_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("88").load().migrate(); var jdbc = new JdbcTemplate(source);
        String parent = UUID.randomUUID().toString(), child = UUID.randomUUID().toString(), original = UUID.randomUUID().toString();
        String invoice = UUID.randomUUID().toString(), invoiceOriginal = UUID.randomUUID().toString();
        for (String app : List.of(parent, child)) jdbc.update(
                    """
INSERT INTO approval_application (id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version)
VALUES (?,'retained',?,'legacy',1,'alice','旧申请','{"note":"原内容"}','IN_APPROVAL',1,2)
""", app, "old-" + app);
        jdbc.update(
                """
INSERT INTO approval_submission_round (tenant_id,application_id,round_no,process_instance_id,definition_version,title,payload_json,submitted_by,submitted_at,status)
VALUES ('retained',?,1,'original-instance',1,'旧轮次','{"note":"不可变原文"}','alice',CURRENT_TIMESTAMP,'IN_APPROVAL')
""", parent);
        jdbc.update(
                """
INSERT INTO approval_attachment (id,tenant_id,application_id,field_path,filename,byte_size,sha256,created_by,created_at,status)
VALUES (?,'retained',?,'proof','原件.pdf',10,?,'alice',CURRENT_TIMESTAMP,'READY')
""", original, parent, "a".repeat(64));
        jdbc.update(
                "INSERT INTO"
                    + " finance_resource(tenant_id,resource_type,id,owner_id,source_reference,version,context_json,state_json)"
                    + " VALUES('retained','INVOICE',?,'alice',?,1,?,?)",
                invoice, invoiceOriginal, "b".repeat(64), "{\"retained\":true}");
        jdbc.update("INSERT INTO invoice_wallet(tenant_id,owner_id) VALUES('retained','alice')");
        jdbc.update(
                "INSERT INTO"
                    + " invoice_original(tenant_id,id,invoice_id,owner_id,filename,byte_size,sha256,format,status,created_at)"
                    + " VALUES('retained',?,?,'alice','旧发票.pdf',10,?,'PDF','READY',CURRENT_TIMESTAMP)",
                invoiceOriginal, invoice, "b".repeat(64));
        var before = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : List.of("approval_application", "approval_submission_round", "finance_resource", "invoice_wallet", "invoice_original", "stored_document_inventory")) {
            before.put(table, jdbc.queryForList("SELECT * FROM " + table));
        }
        String attachmentColumns = "id,tenant_id,application_id,field_path,filename,byte_size,sha256,created_by,created_at,status";
        var oldAttachments = jdbc.queryForList("SELECT " + attachmentColumns + " FROM approval_attachment");
        var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        var upgraded = Flyway.configure().dataSource(source).target("89").load();
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
        before.forEach((table, rows) -> assertThat(jdbc.queryForList("SELECT * FROM " + table)).isEqualTo(rows));
        assertThat(jdbc.queryForList("SELECT " + attachmentColumns + " FROM approval_attachment")).isEqualTo(oldAttachments);
        assertThat(jdbc.queryForList("SELECT * FROM approval_subprocess_call")).isEmpty();
        assertThat(jdbc.queryForList("SELECT * FROM approval_subprocess_attachment")).isEmpty();
        assertThat(jdbc.queryForList(
                                "SELECT * FROM \"flyway_schema_history\" WHERE \"version\" IS NULL"
                                        + " OR \"version\"<>'89' ORDER BY \"installed_rank\"")).isEqualTo(history);

        var files = new JdbcAttachmentRepository(
                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                (jdbc).getDataSource(),
                                io.agentflow.attachment.mapper.AttachmentRepositoryMapper.class));
        var file = files.get("retained", UUID.fromString(parent), UUID.fromString(original));
        assertThat(file.contentId()).isEqualTo(file.id());
        var reference = file.rebind(UUID.randomUUID(), UUID.fromString(child), "document", "system:subprocess", Instant.now());
        files.insert(reference);
        assertThat(files.get("retained", reference.applicationId(), reference.id()).contentId()).isEqualTo(file.id());
        assertThat(jdbc.queryForList("SELECT id FROM stored_document_inventory", String.class)).containsExactlyInAnyOrder(original, invoiceOriginal);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_attachment", Integer.class)).isEqualTo(2);
        assertThat(upgraded.migrate().migrationsExecuted).isZero();
        assertThat(upgraded.validateWithResult().validationSuccessful).isTrue();
    }
}
