package io.agentflow.agent;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 非空 V99 升级保留财务原件，新增抽取运行的租户、活动唯一键及阶段约束。
 * @author owlzhangfq@gmail.com
 */
class InvoiceExtractionMigrationTest {
    @Test void preservesOriginalsAndConstrainsTenantOwnerStageAndActiveRun() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_EXTRACTION_MIGRATION_URL",
                "jdbc:h2:mem:extraction-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_EXTRACTION_TEST_USER", "sa"),
                System.getenv().getOrDefault("AGENTFLOW_EXTRACTION_TEST_PASSWORD", ""));
        Flyway.configure().dataSource(source).target("99").load().migrate();
        var jdbc = new JdbcTemplate(source);
        String invoice = UUID.randomUUID().toString(), original = UUID.randomUUID().toString(), run = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO invoice_wallet VALUES('retained','alice',20,1)");
        jdbc.update("INSERT INTO finance_resource(tenant_id,resource_type,id,owner_id,source_reference,version,context_json,state_json) VALUES('retained','INVOICE',?,'alice',?,1,'{}','{}')", invoice, original);
        jdbc.update("INSERT INTO invoice_original(tenant_id,id,invoice_id,owner_id,filename,byte_size,sha256,format,status,created_at) VALUES('retained',?,?,'alice','original.xml',20,?,'XML','READY',CURRENT_TIMESTAMP)", original, invoice, "a".repeat(64));
        var tables = List.of("finance_resource", "invoice_original", "invoice_wallet", "agent_draft_assist_run");
        var before = tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table)).toList();
        var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        var migration = Flyway.configure().dataSource(source).target("100").load();
        assertThat(migration.migrate().migrationsExecuted).isOne();
        assertThat(tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table)).toList()).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"").subList(0, history.size())).isEqualTo(history);
        assertThat(jdbc.queryForList("SELECT * FROM agent_invoice_extraction_run")).isEmpty();
        String insert = """
                INSERT INTO agent_invoice_extraction_run(id,tenant_id,invoice_id,owner_id,original_id,original_digest,
                    method,original_format,original_bytes,page_count,status,version,context_json,state_json,created_at,active_invoice_id)
                VALUES(?,?,?,?,?,?,'STRUCTURED_XML','XML',20,1,'QUEUED',1,'{}','{}',CURRENT_TIMESTAMP,?)
                """;
        assertThatThrownBy(() -> jdbc.update(insert, run, "foreign", invoice, "alice", original, "a".repeat(64), invoice)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, run, "retained", invoice, "bob", original, "a".repeat(64), invoice)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, run, "retained", invoice, "alice", original, "b".repeat(64), invoice)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, run, "retained", invoice, "alice", original, "a".repeat(64), invoice);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID().toString(), "retained", invoice, "alice", original, "a".repeat(64), invoice)).isInstanceOf(DataIntegrityViolationException.class);
        for (String invalid : List.of("version=2", "active_invoice_id=NULL", "method='MODEL'", "page_count=11", "original_format='PDF'", "lease_until=CURRENT_TIMESTAMP")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE agent_invoice_extraction_run SET " + invalid + " WHERE id=?", run)).isInstanceOf(DataIntegrityViolationException.class);
        }
        jdbc.update("INSERT INTO agent_invoice_extraction_transition VALUES('retained',?,1,'QUEUED','{}')", run);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO agent_invoice_extraction_transition VALUES('foreign',?,1,'QUEUED','{}')", run)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO agent_invoice_extraction_transition VALUES('retained',?,1,'QUEUED','{}')", run)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE agent_invoice_extraction_run SET status='FAILED',version=3,active_invoice_id=NULL WHERE id=?", run);
        jdbc.update(insert, UUID.randomUUID().toString(), "retained", invoice, "alice", original, "a".repeat(64), invoice);
        assertThat(migration.migrate().migrationsExecuted).isZero();
        assertThat(migration.validateWithResult().validationSuccessful).isTrue();
    }
}
