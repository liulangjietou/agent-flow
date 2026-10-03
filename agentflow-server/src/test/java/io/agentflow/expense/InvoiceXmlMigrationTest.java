package io.agentflow.expense;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * V56 原件及库存逐行保留，V57 只扩充 XML 格式，不放松原有归属和尺寸边界。
 * @author owlzhangfq@gmail.com
 */
class InvoiceXmlMigrationTest {
    @Test
    void upgradePreservesEveryOriginalAndConstraintOtherThanTheFormatAllowlist() {
        var source = new DriverManagerDataSource(
                System.getenv().getOrDefault("AGENTFLOW_XML_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_XML_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_XML_MIGRATION_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("56").load().migrate();
        var jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO invoice_wallet(tenant_id,owner_id) VALUES('retained','alice'),('retained','bob')");
        for (String format : List.of("PDF", "OFD", "PNG", "JPEG")) insertOriginal(jdbc, format);
        var originals = jdbc.queryForList("SELECT * FROM invoice_original ORDER BY id");
        var inventory = jdbc.queryForList("SELECT * FROM stored_document_inventory ORDER BY id");
        var resources = jdbc.queryForList("SELECT * FROM finance_resource ORDER BY id");
        var revisions = jdbc.queryForList("SELECT * FROM finance_resource_revision ORDER BY resource_id");
        var wallets = jdbc.queryForList("SELECT * FROM invoice_wallet ORDER BY owner_id");
        assertThatThrownBy(() -> jdbc.update("UPDATE invoice_original SET format='XML'")).isInstanceOf(DataIntegrityViolationException.class);
        var upgrade = Flyway.configure().dataSource(source).target("57").load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM invoice_original ORDER BY id")).isEqualTo(originals);
        assertThat(jdbc.queryForList("SELECT * FROM stored_document_inventory ORDER BY id")).isEqualTo(inventory);
        assertThat(jdbc.queryForList("SELECT * FROM finance_resource ORDER BY id")).isEqualTo(resources);
        assertThat(jdbc.queryForList("SELECT * FROM finance_resource_revision ORDER BY resource_id")).isEqualTo(revisions);
        assertThat(jdbc.queryForList("SELECT * FROM invoice_wallet ORDER BY owner_id")).isEqualTo(wallets);
        String xml = insertOriginal(jdbc, "XML");
        assertThat(jdbc.queryForList("SELECT id FROM stored_document_inventory", String.class)).contains(xml).hasSize(5);
        for (String invalid : List.of("format='SVG'", "format='HTML'", "format=NULL", "byte_size=0", "byte_size=20971521", "status='VERIFIED'", "owner_id='bob'", "tenant_id='foreign'", "resource_type='EXPENSE'")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE invoice_original SET " + invalid + " WHERE id=?", xml)).as(invalid).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
    }

    private String insertOriginal(JdbcTemplate jdbc, String format) {
        String id = UUID.randomUUID().toString(), invoice = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO finance_resource(tenant_id,resource_type,id,owner_id,source_reference,version,context_json,state_json) VALUES('retained','INVOICE',?,'alice',?,1,?,?)", invoice, id, "a".repeat(64), "{\"retained\":true}");
        jdbc.update("INSERT INTO finance_resource_revision(tenant_id,resource_type,resource_id,version,actor_id,operation,state_json) SELECT tenant_id,resource_type,id,version,owner_id,'CREATE',state_json FROM finance_resource WHERE id=?", invoice);
        jdbc.update("INSERT INTO invoice_original(tenant_id,id,invoice_id,owner_id,filename,byte_size,sha256,format,status,created_at) VALUES('retained',?,?,'alice',?,10,?,?,'READY',CURRENT_TIMESTAMP)", id, invoice, "original." + format.toLowerCase(java.util.Locale.ROOT), "a".repeat(64), format);
        return id;
    }
}
