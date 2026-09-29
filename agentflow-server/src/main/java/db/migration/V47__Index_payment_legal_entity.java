package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.util.Map;
import java.util.UUID;

/**
 * 付款目录按原授权法人过滤，迁移只读取不可变条款，不根据当前组织猜测历史付款归属。
 * @author owlzhangfq@gmail.com
 */
public class V47__Index_payment_legal_entity extends BaseJavaMigration {
    private static final int FETCH_SIZE = 256;
    /** 固定历史 JSON 契约，不依赖后续领域模型构造器变化。 */
    @Override public Integer getChecksum() { return 2026092801; }
    /** 缺失或损坏的历史条款保留原文且不授予出纳范围；正常条款建立可查询的法人关联。 */
    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        try (var statement = connection.createStatement()) { statement.execute("ALTER TABLE payment_authorization ADD COLUMN legal_entity_id VARCHAR(36)"); }
        var json = new JsonUtil(new ObjectMapper());
        try (var select = connection.prepareStatement("SELECT tenant_id,id,terms_json FROM payment_authorization");
             var update = connection.prepareStatement("UPDATE payment_authorization SET legal_entity_id=? WHERE tenant_id=? AND id=?")) {
            select.setFetchSize(FETCH_SIZE);
            try (var rows = select.executeQuery()) {
                while (rows.next()) {
                    String entity;
                    try { entity = entity(json.map(rows.getString("terms_json")), rows.getString("tenant_id"), rows.getString("id")); }
                    catch (DomainException malformedLegacyTerms) { continue; }
                    if (entity == null) continue;
                    update.setString(1, entity); update.setString(2, rows.getString("tenant_id")); update.setString(3, rows.getString("id")); update.executeUpdate();
                }
            }
        }
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE INDEX idx_payment_authorization_cashier ON payment_authorization(tenant_id,legal_entity_id,authorized_at DESC,id DESC)");
        }
    }
    private static String entity(Map<String, Object> terms, String tenant, String id) {
        if (terms == null || !tenant.equals(terms.get("tenantId")) || !id.equals(terms.get("id")) || !(terms.get("payee") instanceof Map<?, ?> payee)
                || !(payee.get("legalEntityId") instanceof String value)) return null;
        try { return UUID.fromString(value).toString().equals(value) ? value : null; }
        catch (IllegalArgumentException invalid) { return null; }
    }
}
