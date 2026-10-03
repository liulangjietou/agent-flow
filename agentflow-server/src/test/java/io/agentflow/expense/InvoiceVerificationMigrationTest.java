package io.agentflow.expense;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * V35 升级不重写原件和财务历史，持久任务必须精确引用本租户原件及存在的版本。
 * @author owlzhangfq@gmail.com
 */
class InvoiceVerificationMigrationTest {
    @Test
    void upgradePreservesOriginalsAndConstrainsIdentityVersionAndOneActiveJob() {
        var source = new DriverManagerDataSource(
                System.getenv().getOrDefault("AGENTFLOW_VERIFICATION_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_VERIFICATION_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_VERIFICATION_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("35").load().migrate();
        var jdbc = new JdbcTemplate(source);
        String invoice = UUID.randomUUID().toString(), original = UUID.randomUUID().toString(), job = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO finance_resource(tenant_id,resource_type,id,owner_id,source_reference,version,context_json,state_json) VALUES('retained','INVOICE',?,'alice',?,1,?,?)", invoice, original, "a".repeat(64), "{\"preserved\":true}");
        jdbc.update("INSERT INTO finance_resource_revision(tenant_id,resource_type,resource_id,version,actor_id,operation,state_json) SELECT tenant_id,resource_type,id,version,owner_id,'CREATE',state_json FROM finance_resource");
        jdbc.update("INSERT INTO invoice_wallet(tenant_id,owner_id,used_bytes,upload_count) VALUES('retained','alice',10,1)");
        jdbc.update("INSERT INTO invoice_original(tenant_id,id,invoice_id,owner_id,filename,byte_size,sha256,format,status,created_at) VALUES('retained',?,?,'alice','retained.pdf',10,?,'PDF','READY',CURRENT_TIMESTAMP)", original, invoice, "a".repeat(64));
        var resources = jdbc.queryForList("SELECT * FROM finance_resource"); var journal = jdbc.queryForList("SELECT * FROM finance_resource_revision");
        var originals = jdbc.queryForList("SELECT * FROM invoice_original"); var inventory = jdbc.queryForList("SELECT * FROM stored_document_inventory");
        var upgrade = Flyway.configure().dataSource(source).target("36").load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM finance_resource")).isEqualTo(resources);
        assertThat(jdbc.queryForList("SELECT * FROM finance_resource_revision")).isEqualTo(journal);
        assertThat(jdbc.queryForList("SELECT * FROM invoice_original")).isEqualTo(originals);
        assertThat(jdbc.queryForList("SELECT * FROM stored_document_inventory")).isEqualTo(inventory);
        assertThat(jdbc.queryForList("SELECT * FROM invoice_verification_job")).isEmpty();
        String insert = "INSERT INTO invoice_verification_job(tenant_id,id,invoice_id,owner_id,original_id,original_digest,invoice_version,input_json,state_json,version,status,active_invoice_id,created_at) VALUES(?,?,?,?,?,?,?,'{}','{}',1,'QUEUED',?,CURRENT_TIMESTAMP)";
        assertThatThrownBy(() -> jdbc.update(insert, "foreign", job, invoice, "alice", original, "a".repeat(64), 1, invoice)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "retained", job, invoice, "bob", original, "a".repeat(64), 1, invoice)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "retained", job, invoice, "alice", UUID.randomUUID().toString(), "a".repeat(64), 1, invoice)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "retained", job, invoice, "alice", original, "b".repeat(64), 1, invoice)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "retained", job, invoice, "alice", original, "a".repeat(64), 2, invoice)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "retained", job, invoice, "alice", original, "a".repeat(64), 1, null)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, "retained", job, invoice, "alice", original, "a".repeat(64), 1, invoice);
        String retry = UUID.randomUUID().toString();
        assertThatThrownBy(() -> jdbc.update(insert, "retained", retry, invoice, "alice", original, "a".repeat(64), 1, invoice)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE invoice_verification_job SET version=3,status='UNAVAILABLE',active_invoice_id=NULL,lease_until=CURRENT_TIMESTAMP,completed_at=CURRENT_TIMESTAMP WHERE id=?", job);
        jdbc.update(insert, "retained", retry, invoice, "alice", original, "a".repeat(64), 1, invoice);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invoice_verification_job", Integer.class)).isEqualTo(2);
        assertThat(upgrade.migrate().migrationsExecuted).isZero(); assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
    }
}
