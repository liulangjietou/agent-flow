package db.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.JsonUtil;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.TreeSet;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * 保留报销票号互斥键并追加原采购应付归属；所有历史付款轮次均回填，释放付款不释放已入账发票。
 * @author owlzhangfq@gmail.com
 */
public class V67__Share_invoice_claims extends BaseJavaMigration {
    private static final int FETCH_SIZE = 256;
    private final JsonUtil json = new JsonUtil(new ObjectMapper());

    /** 固定迁移口径，历史快照不依赖当前领域对象的构造规则。 */
    @Override public Integer getChecksum() { return 2026092967; }

    /** 既有报销归属保持；若历史业务已重复使用同票号则阻断升级，不能自行挑选保留方。 */
    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE invoice_active_claim ADD COLUMN source_type VARCHAR(16) NOT NULL DEFAULT 'EXPENSE'");
            statement.execute("ALTER TABLE invoice_active_claim ADD COLUMN procurement_reservation_id VARCHAR(36)");
            statement.execute("ALTER TABLE invoice_active_claim ALTER COLUMN invoice_id DROP NOT NULL");
            statement.execute("ALTER TABLE invoice_active_claim ALTER COLUMN report_id DROP NOT NULL");
            statement.execute("ALTER TABLE invoice_active_claim ALTER COLUMN line_no DROP NOT NULL");
            statement.execute("ALTER TABLE procurement_payable_reservation ADD CONSTRAINT uq_procurement_invoice_round UNIQUE (tenant_id,id,round_no)");
            statement.execute("""
                    ALTER TABLE invoice_active_claim ADD CONSTRAINT fk_invoice_claim_procurement
                    FOREIGN KEY (tenant_id,procurement_reservation_id,round_no) REFERENCES procurement_payable_reservation(tenant_id,id,round_no)
                    """);
            statement.execute("""
                    ALTER TABLE invoice_active_claim ADD CONSTRAINT ck_invoice_claim_source CHECK (
                    (source_type='EXPENSE' AND invoice_id IS NOT NULL AND report_id IS NOT NULL AND line_no IS NOT NULL AND procurement_reservation_id IS NULL)
                    OR (source_type='PROCUREMENT' AND invoice_id IS NULL AND report_id IS NULL AND line_no IS NULL AND procurement_reservation_id IS NOT NULL AND status='CONSUMED'))
                    """);
            statement.execute("CREATE INDEX idx_invoice_claim_procurement ON invoice_active_claim(tenant_id,procurement_reservation_id)");
        }
        try (var select = connection.prepareStatement("SELECT * FROM procurement_payable_reservation ORDER BY held_at,id")) {
            select.setFetchSize(FETCH_SIZE);
            try (var rows = select.executeQuery()) { while (rows.next()) backfill(connection, rows); }
        }
    }

    private void backfill(Connection connection, ResultSet row) throws Exception {
        var value = json.read(row.getString("state_json"), JsonNode.class); var source = value.path("source"); var round = source.path("round");
        var content = round.path("content"); var payable = round.path("payable"); var lines = payable.path("lines");
        if (!row.getString("id").equals(value.path("id").asText()) || !row.getString("tenant_id").equals(source.path("tenantId").asText())
                || !row.getString("request_id").equals(source.path("requestId").asText()) || !row.getString("application_id").equals(source.path("applicationId").asText())
                || !row.getString("employee_id").equals(source.path("employeeId").asText()) || row.getLong("request_version") != source.path("requestVersion").asLong()
                || row.getInt("round_no") != round.path("roundNo").asInt() || !lines.isArray() || lines.isEmpty()) throw inconsistent();
        for (var field : new String[]{"legalEntityId", "supplierReference", "payableReference"}) {
            String column = switch (field) { case "legalEntityId" -> "legal_entity_id"; case "supplierReference" -> "supplier_reference"; default -> "payable_reference"; };
            if (!row.getString(column).equals(content.path(field).asText()) || !content.path(field).equals(payable.path("request").path(field))) throw inconsistent();
        }
        var keys = new TreeSet<String>();
        for (var line : lines) keys.add(canonical(line.path("invoice")));
        for (var key : keys) {
            if (alreadyRecognized(connection, row, key)) continue;
            try (var insert = connection.prepareStatement("""
                    INSERT INTO invoice_active_claim(tenant_id,invoice_key,source_type,procurement_reservation_id,round_no,status)
                    VALUES(?,?,'PROCUREMENT',?,?,'CONSUMED')
                    """)) {
                insert.setString(1, row.getString("tenant_id")); insert.setString(2, key); insert.setString(3, row.getString("id"));
                insert.setInt(4, row.getInt("round_no")); insert.executeUpdate();
            }
        }
    }

    private static boolean alreadyRecognized(Connection connection, ResultSet source, String key) throws Exception {
        try (var select = connection.prepareStatement("""
                SELECT c.source_type,r.legal_entity_id,r.supplier_reference,r.payable_reference FROM invoice_active_claim c
                LEFT JOIN procurement_payable_reservation r ON r.tenant_id=c.tenant_id AND r.id=c.procurement_reservation_id
                WHERE c.tenant_id=? AND c.invoice_key=?
                """)) {
            select.setString(1, source.getString("tenant_id")); select.setString(2, key);
            try (var rows = select.executeQuery()) {
                if (!rows.next()) return false;
                if (!"PROCUREMENT".equals(rows.getString("source_type"))
                        || !source.getString("legal_entity_id").equals(rows.getString("legal_entity_id"))
                        || !source.getString("supplier_reference").equals(rows.getString("supplier_reference"))
                        || !source.getString("payable_reference").equals(rows.getString("payable_reference"))) {
                    throw new IllegalStateException("Historical invoice has conflicting expense or procurement sources; reconcile before upgrading");
                }
                return true;
            }
        }
    }
    private static String canonical(JsonNode invoice) {
        String type = invoice.path("type").asText(), number = invoice.path("number").asText(), code = invoice.path("code").asText();
        if (!number.matches("[0-9]{1,32}")) throw inconsistent();
        if ("DIGITAL".equals(type) && number.length() == 20 && (invoice.path("code").isNull() || invoice.path("code").isMissingNode())) return "D:" + number;
        if ("TRADITIONAL".equals(type) && code.matches("[0-9]{1,32}")) return "T:" + code + ":" + number;
        throw inconsistent();
    }
    private static IllegalStateException inconsistent() { return new IllegalStateException("Historical procurement invoice source is inconsistent"); }
}
