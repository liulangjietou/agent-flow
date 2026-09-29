package io.agentflow.procurement;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.InvoiceKey;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 在独立旧库验证票号来源回填、原文保留、跨业务冲突阻断及数据库闭合约束。
 * @author owlzhangfq@gmail.com
 */
class InvoiceClaimMigrationTest {
    private static final String TENANT = "invoice-upgrade";
    private static final String LEGACY_COLUMNS = "tenant_id,invoice_key,invoice_id,resource_type,report_id,round_no,line_no,status";
    private final JsonUtil json = new JsonUtil(new ObjectMapper().setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL));
    private final String entity = UUID.randomUUID().toString();
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private String schema;

    @BeforeEach void oldDatabase() {
        source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_INVOICE_CLAIM_MIGRATION_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_INVOICE_CLAIM_MIGRATION_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_INVOICE_CLAIM_MIGRATION_PASSWORD", ""));
        schema = "claim_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(source).execute("CREATE SCHEMA \"" + schema + "\""); source.setSchema(schema); jdbc = new JdbcTemplate(source);
        migration("66").migrate();
    }

    @Test void upgradeKeepsExpenseColumnsAndEveryOriginalRecordWhileRecognizingReleasedAndHeldPayables() {
        expense(digital("01"), "OCCUPIED"); expense(digital("02"), "CONSUMED");
        String released = procurement("AP-1", true, List.of(digital("03"), digital("03")));
        String explicitNull = procurement("AP-1", false, List.of(digital("03")));
        var state = json.read(jdbc.queryForObject("SELECT state_json FROM procurement_payable_reservation WHERE id=?", String.class, explicitNull), com.fasterxml.jackson.databind.JsonNode.class);
        ((com.fasterxml.jackson.databind.node.ObjectNode) state.at("/source/round/payable/lines/0/invoice")).putNull("code");
        jdbc.update("UPDATE procurement_payable_reservation SET state_json=? WHERE id=?", json.write(state), explicitNull);
        var traditional = new InvoiceKey(InvoiceKey.Type.TRADITIONAL, "00001", "00002"); procurement("AP-2", false, List.of(traditional));
        var claims = jdbc.queryForList("SELECT " + LEGACY_COLUMNS + " FROM invoice_active_claim ORDER BY invoice_key");
        var originals = snapshots(); var migration = migration("67");
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(snapshots()).isEqualTo(originals);
        assertThat(jdbc.queryForList("SELECT " + LEGACY_COLUMNS + " FROM invoice_active_claim WHERE source_type='EXPENSE' ORDER BY invoice_key")).isEqualTo(claims);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invoice_active_claim WHERE source_type='PROCUREMENT' AND status='CONSUMED'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT procurement_reservation_id FROM invoice_active_claim WHERE invoice_key=?", String.class, digital("03").canonical())).isEqualTo(released);
        assertThat(jdbc.queryForObject("SELECT invoice_key FROM invoice_active_claim WHERE invoice_key=?", String.class, traditional.canonical())).isEqualTo("T:00001:00002");
        assertThat(migration.migrate().migrationsExecuted).isZero(); assertThat(migration.validateWithResult().validationSuccessful).isTrue();
        String insert = "INSERT INTO invoice_active_claim(tenant_id,invoice_key,source_type,procurement_reservation_id,round_no,status) VALUES(?,?,'PROCUREMENT',?,?,?)";
        for (Object[] input : List.of(new Object[]{"foreign", "D:00000000000000000009", released, 1, "CONSUMED"},
                new Object[]{TENANT, "D:00000000000000000009", released, 2, "CONSUMED"},
                new Object[]{TENANT, "D:00000000000000000009", released, 1, "OCCUPIED"})) {
            assertThatThrownBy(() -> jdbc.update(insert, input)).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.update("UPDATE invoice_active_claim SET source_type='EXPENSE' WHERE source_type='PROCUREMENT'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE invoice_active_claim SET procurement_reservation_id=NULL WHERE source_type='PROCUREMENT'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void historicalExpenseAndProcurementConflictStopsUpgradeWithoutReplacingEitherOriginal() {
        var key = digital("01"); expense(key, "CONSUMED"); procurement("AP-1", true, List.of(key));
        var originals = snapshots(); var claim = jdbc.queryForList("SELECT " + LEGACY_COLUMNS + " FROM invoice_active_claim");
        assertThatThrownBy(() -> migration("67").migrate()).hasStackTraceContaining("Historical invoice has conflicting expense or procurement sources");
        assertThat(snapshots()).isEqualTo(originals);
        assertThat(jdbc.queryForList("SELECT " + LEGACY_COLUMNS + " FROM invoice_active_claim")).isEqualTo(claim);
    }

    @Test void twoDifferentHistoricalPayablesUsingTheSameInvoiceCannotSilentlyShareAnOrigin() {
        procurement("AP-1", true, List.of(digital("01"))); procurement("AP-2", false, List.of(digital("01"))); var originals = snapshots();
        assertThatThrownBy(() -> migration("67").migrate()).hasStackTraceContaining("Historical invoice has conflicting expense or procurement sources");
        assertThat(snapshots()).isEqualTo(originals);
    }

    private Flyway migration(String version) { return Flyway.configure().dataSource(source).defaultSchema(schema).target(version).load(); }
    private Map<String, List<Map<String, Object>>> snapshots() {
        var result = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : List.of("approval_application", "expense_report", "finance_resource", "procurement_payment", "procurement_payment_revision",
                "procurement_payable_reservation", "procurement_payable_reservation_revision")) result.put(table, jdbc.queryForList("SELECT * FROM " + table));
        return result;
    }
    private void expense(InvoiceKey key, String status) {
        String report = UUID.randomUUID().toString(), app = application("EXPENSE", report), invoice = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO expense_report(id,tenant_id,application_id,employee_id,version,state_json) VALUES(?,?,?,'alice',1,'{}')", report, TENANT, app);
        jdbc.update("INSERT INTO finance_resource(tenant_id,resource_type,id,owner_id,source_reference,version,context_json,state_json) VALUES(?,'INVOICE',?,'alice',?,1,'{}','{}')", TENANT, invoice, UUID.randomUUID().toString());
        jdbc.update("INSERT INTO invoice_active_claim(tenant_id,invoice_key,invoice_id,report_id,round_no,line_no,status) VALUES(?,?,?,?,1,1,?)", TENANT, key.canonical(), invoice, report, status);
    }
    private String application(String type, String business) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,?,?,'fixture',1,'alice','旧单据','{}','DRAFT',1,1,?,?)", id, TENANT, id, type, business);
        return id;
    }
    private String procurement(String payable, boolean released, List<InvoiceKey> invoices) {
        String request = UUID.randomUUID().toString(), app = application("PROCUREMENT_PAYMENT", request), id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO procurement_payment(id,tenant_id,application_id,employee_id,version,state_json) VALUES(?,?,?,'alice',1,'{}')", request, TENANT, app);
        jdbc.update("INSERT INTO procurement_payment_revision(tenant_id,request_id,request_version,actor_id,operation,state_json) VALUES(?,?,1,'alice','SUBMIT','{}')", TENANT, request);
        var identity = Map.of("legalEntityId", entity, "supplierReference", "supplier", "payableReference", payable);
        // 迁移只解释当时已持久化的身份和票号字段；原文中的其他内容作为不透明历史保留。
        var state = Map.of("id", id, "untouched", "原始历史", "source", Map.of("tenantId", TENANT, "requestId", request, "applicationId", app,
                "employeeId", "alice", "requestVersion", 1, "round", Map.of("roundNo", 1, "content", identity,
                        "payable", Map.of("request", identity, "lines", invoices.stream().map(key -> Map.of("invoice", key)).toList()))));
        var at = Timestamp.from(Instant.parse("2026-09-29T12:00:00Z").plusSeconds(jdbc.queryForObject("SELECT COUNT(*) FROM procurement_payable_reservation", Integer.class)));
        jdbc.update("""
                INSERT INTO procurement_payable_reservation(tenant_id,id,request_id,application_id,employee_id,request_version,round_no,legal_entity_id,supplier_reference,payable_reference,
                active_request_id,active_payable_reference,version,state_json,held_at,released_at) VALUES(?,?,?,?,'alice',1,1,?,'supplier',?,?,?,?,?,?,?)
                """, TENANT, id, request, app, entity, payable, released ? null : request, released ? null : payable, released ? 2 : 1, json.write(state), at, released ? at : null);
        jdbc.update("INSERT INTO procurement_payable_reservation_revision(tenant_id,reservation_id,version,state_json) VALUES(?,?,1,?)", TENANT, id, json.write(state));
        return id;
    }
    private static InvoiceKey digital(String suffix) { return new InvoiceKey(InvoiceKey.Type.DIGITAL, null, "0".repeat(20 - suffix.length()) + suffix); }
}
