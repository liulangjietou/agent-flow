package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.JsonUtil;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 旧借款补录原凭证尚未解除的冻结，只追加余额修订，不改写任何旧资金和凭证原文。
 * @author owlzhangfq@gmail.com
 */
public class V60__Hold_original_advance_vouchers extends BaseJavaMigration {
    private static final int FETCH_SIZE = 256;
    private final JsonUtil json = new JsonUtil(new ObjectMapper());
    /** 固定旧 JSON 属性和修订语义，迁移不依赖当前领域构造器。 */
    @Override public Integer getChecksum() { return 2026092901; }

    /** 服务启动前按原付款授权找回挂账及付款凭证，未实际放款的单据不生成资金。 */
    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        try (var select = connection.prepareStatement("""
                SELECT f.tenant_id,f.id,f.version,f.state_json,p.id AS payment_id,p.application_id,p.round_no,p.voucher_operation_id
                FROM finance_resource f JOIN payment_authorization p ON p.tenant_id=f.tenant_id
                    AND p.business_type='ADVANCE_REQUEST' AND p.active_business_id=f.id
                WHERE f.resource_type='ADVANCE' ORDER BY f.tenant_id,f.id
                """)) {
            select.setFetchSize(FETCH_SIZE);
            try (var rows = select.executeQuery()) {
                while (rows.next()) {
                    var tenant = rows.getString("tenant_id"); var id = rows.getString("id");
                    var reviews = reviews(connection, tenant, id, rows.getString("payment_id"), rows.getString("application_id"),
                            rows.getInt("round_no"), rows.getString("voucher_operation_id"));
                    if (!reviews.isEmpty()) freeze(connection, tenant, id, rows.getLong("version"), rows.getString("state_json"), reviews);
                }
            }
        }
    }

    private List<String> reviews(Connection connection, String tenant, String id, String payment, String application, int round, String accrual) throws Exception {
        var result = new ArrayList<String>();
        try (var select = connection.prepareStatement("""
                SELECT id,kind,input_json FROM voucher_operation WHERE tenant_id=? AND business_type='ADVANCE_REQUEST'
                AND business_id=? AND application_id=? AND round_no=? AND ((kind='EMPLOYEE_ADVANCE' AND id=?) OR kind='PAYMENT') ORDER BY id
                """)) {
            select.setString(1, tenant); select.setString(2, id); select.setString(3, application); select.setInt(4, round); select.setString(5, accrual);
            try (var rows = select.executeQuery()) {
                while (rows.next()) {
                    var voucher = rows.getString("id");
                    if (!requiresReview(connection, tenant, voucher)) continue;
                    if ("PAYMENT".equals(rows.getString("kind"))) {
                        var input = json.map(rows.getString("input_json"));
                        if (!(input.get("command") instanceof Map<?, ?> command) || !(command.get("payment") instanceof Map<?, ?> proof)
                                || !(proof.get("command") instanceof Map<?, ?> original) || !payment.equals(original.get("id"))) throw inconsistent();
                    }
                    result.add(voucher);
                }
            }
        }
        return result;
    }

    private boolean requiresReview(Connection connection, String tenant, String voucher) throws Exception {
        try (var select = connection.prepareStatement("""
                SELECT r.state_json FROM voucher_operation_revision r WHERE r.tenant_id=? AND r.operation_id=?
                AND r.version>COALESCE((SELECT MAX(d.resolved_version) FROM voucher_dispute_resolution d
                    WHERE d.tenant_id=r.tenant_id AND d.operation_id=r.operation_id AND d.outcome='POSTED'),0)
                """)) {
            select.setString(1, tenant); select.setString(2, voucher); select.setFetchSize(FETCH_SIZE);
            try (var rows = select.executeQuery()) {
                while (rows.next()) {
                    var status = json.map(rows.getString("state_json")).get("status");
                    if ("REVERSED".equals(status) || "RECONCILING".equals(status)) return true;
                }
            }
        }
        return false;
    }

    private void freeze(Connection connection, String tenant, String id, long version, String original, List<String> reviews) throws Exception {
        var state = new LinkedHashMap<>(json.map(original));
        if (!tenant.equals(state.get("tenantId")) || !id.equals(state.get("id")) || !(state.get("version") instanceof Number storedVersion)
                || storedVersion.longValue() != version) throw inconsistent();
        var current = state.get("voucherReviews"); var next = new ArrayList<String>();
        if (current != null) {
            if (!(current instanceof List<?> values)) throw inconsistent();
            for (var value : values) {
                if (!(value instanceof String reference) || !UUID.fromString(reference).toString().equals(reference) || next.contains(reference)) throw inconsistent();
                next.add(reference);
            }
        }
        for (var review : reviews) if (!next.contains(review)) next.add(review);
        if (next.equals(current)) return;
        state.put("voucherReviews", next); state.put("version", Math.addExact(version, 1)); var updated = json.write(state);
        try (var update = connection.prepareStatement("""
                UPDATE finance_resource SET state_json=?,version=?,updated_at=CURRENT_TIMESTAMP
                WHERE tenant_id=? AND resource_type='ADVANCE' AND id=? AND version=?
                """); var append = connection.prepareStatement("""
                INSERT INTO finance_resource_revision(tenant_id,resource_type,resource_id,version,actor_id,operation,state_json)
                VALUES(?,'ADVANCE',?,?,'finance-schema-upgrade','VOUCHER_REVIEW_UPGRADE',?)
                """)) {
            update.setString(1, updated); update.setLong(2, version + 1); update.setString(3, tenant); update.setString(4, id); update.setLong(5, version);
            if (update.executeUpdate() != 1) throw inconsistent();
            append.setString(1, tenant); append.setString(2, id); append.setLong(3, version + 1); append.setString(4, updated); append.executeUpdate();
        }
    }
    private static IllegalStateException inconsistent() { return new IllegalStateException("Original advance voucher review evidence is inconsistent"); }
}
