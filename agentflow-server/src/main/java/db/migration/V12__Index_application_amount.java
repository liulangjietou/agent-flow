package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 为待办金额筛选建立可重建读索引，原申请正文和审批历史保持原样。
 * @author owlzhangfq@gmail.com
 */
public class V12__Index_application_amount extends BaseJavaMigration {
    /** 固定迁移内容版本；发布后不跟随当前运行时投影规则改变。 */
    @Override
    public Integer getChecksum() { return 2026092302; }

    /** 分批读取旧正文，仅识别明确的数值 amount，未知旧值保持 NULL。 */
    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE approval_application ADD COLUMN search_amount DECIMAL(56,18) NULL");
            statement.execute("CREATE INDEX idx_application_pending_amount ON approval_application (tenant_id,status,search_amount,id)");
        }
        var json = new JsonUtil(new ObjectMapper());
        try (var select = connection.prepareStatement("SELECT id,payload_json,form_schema_json FROM approval_application");
             var update = connection.prepareStatement("UPDATE approval_application SET search_amount=? WHERE id=?")) {
            select.setFetchSize(256);
            try (var rows = select.executeQuery()) {
                while (rows.next()) {
                    BigDecimal amount;
                    try { amount = amount(json.map(rows.getString("payload_json")), rows.getString("form_schema_json"), json); }
                    catch (DomainException | IllegalArgumentException malformedLegacyValue) { continue; }
                    if (amount == null) continue;
                    update.setBigDecimal(1, amount); update.setString(2, rows.getString("id")); update.executeUpdate();
                }
            }
        }
    }

    private static BigDecimal amount(Map<String, Object> payload, String schemaText, JsonUtil json) {
        if (schemaText != null) {
            Map<String, Object> schema = json.map(schemaText);
            if (schema == null || !(schema.get("fields") instanceof List<?> fields) || fields.stream().noneMatch(field ->
                    field instanceof Map<?, ?> value && "amount".equals(value.get("key")) && "NUMBER".equals(value.get("type")))) return null;
        }
        if (payload == null) return null;
        Object raw = payload.get("amount");
        if (!(raw instanceof String) && !(raw instanceof Number)) return null;
        String text = raw.toString();
        if (text.length() > 80 || !text.matches("-?[0-9]+(?:\\.[0-9]+)?")) return null;
        BigDecimal amount = new BigDecimal(text);
        return amount.precision() <= 38 && amount.scale() <= 18 ? amount : null;
    }
}
