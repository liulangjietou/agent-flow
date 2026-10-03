package io.agentflow.expense;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * V34 升级保留原发票和附件，统一备份视图同时覆盖两类原件，复合外键防止错绑。
 * @author owlzhangfq@gmail.com
 */
class InvoiceWalletMigrationTest {
    @Test
    void migrationRetainsExistingEvidenceAndIncludesInvoiceOriginalsInRecoveryInventory() {
        var source = new DriverManagerDataSource(
                System.getenv().getOrDefault("AGENTFLOW_INVOICE_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_INVOICE_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_INVOICE_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("34").load().migrate();
        var jdbc = new JdbcTemplate(source);
        String invoice = UUID.randomUUID().toString(), original = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO finance_resource(tenant_id,resource_type,id,owner_id,source_reference,version,context_json,state_json) VALUES('retained','INVOICE',?,'alice',?,1,?,?)", invoice, original, "a".repeat(64), "{\"retained\":true}");
        jdbc.update("INSERT INTO finance_resource_revision(tenant_id,resource_type,resource_id,version,actor_id,operation,state_json) SELECT tenant_id,resource_type,id,version,owner_id,'CREATE',state_json FROM finance_resource");
        String application = UUID.randomUUID().toString(), attachment = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version) VALUES(?,'retained','BEFORE-35','ordinary',1,'alice','旧附件','{}','DRAFT',1,1)", application);
        jdbc.update("INSERT INTO approval_attachment(id,tenant_id,application_id,field_path,filename,byte_size,sha256,created_by,created_at,status) VALUES(?,'retained',?,'proof','old.pdf',10,?,'alice',CURRENT_TIMESTAMP,'READY')", attachment, application, "b".repeat(64));
        var before = jdbc.queryForList("SELECT * FROM finance_resource"); var journal = jdbc.queryForList("SELECT * FROM finance_resource_revision");
        var attachments = jdbc.queryForList("SELECT * FROM approval_attachment");
        var upgrade = Flyway.configure().dataSource(source).target("35").load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM finance_resource")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM finance_resource_revision")).isEqualTo(journal);
        assertThat(jdbc.queryForList("SELECT * FROM approval_attachment")).isEqualTo(attachments);
        assertThat(jdbc.queryForList("SELECT * FROM invoice_original")).isEmpty();
        jdbc.update("INSERT INTO invoice_wallet(tenant_id,owner_id) VALUES('retained','alice'),('retained','bob'),('foreign','alice')");
        String insert = "INSERT INTO invoice_original(tenant_id,id,invoice_id,owner_id,filename,byte_size,sha256,format,status,created_at) VALUES(?,?,?,?,'invoice.pdf',10,?,'PDF','READY',CURRENT_TIMESTAMP)";
        assertThatThrownBy(() -> jdbc.update(insert, "foreign", original, invoice, "alice", "a".repeat(64))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "retained", original, invoice, "bob", "a".repeat(64))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "retained", UUID.randomUUID().toString(), invoice, "alice", "a".repeat(64))).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, "retained", original, invoice, "alice", "a".repeat(64));
        assertThat(jdbc.queryForList("SELECT id FROM stored_document_inventory ORDER BY id", String.class)).containsExactlyInAnyOrder(attachment, original);
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
    }
}
