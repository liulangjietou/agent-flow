package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 从旧付款命令建立实际出款账户索引；不调用资金目录、不改写历史快照或待复查选择。
 * @author owlzhangfq@gmail.com
 */
public class V114__Index_cashier_payment_account extends BaseJavaMigration {
    private static final int FETCH_SIZE = 256;
    /** 迁移固定旧 JSON 及摘要格式，独立于后续模型和查询帮助类。 */
    @Override public Integer getChecksum() { return 2026100401; }

    /** 损坏或相互矛盾的历史信息不补造账户归属，后续严格读取仍会报错。 */
    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE payment_authorization ADD COLUMN debit_account_key VARCHAR(64)");
        }
        var json = new JsonUtil(new ObjectMapper());
        try (var select = connection.prepareStatement("SELECT tenant_id,id,legal_entity_id,status,terms_json,state_json FROM payment_authorization");
             var update = connection.prepareStatement("UPDATE payment_authorization SET debit_account_key=? WHERE tenant_id=? AND id=?")) {
            select.setFetchSize(FETCH_SIZE);
            try (var rows = select.executeQuery()) {
                while (rows.next()) {
                    String key;
                    try {
                        key = key(rows.getString("tenant_id"), rows.getString("id"), rows.getString("legal_entity_id"), rows.getString("status"),
                                json.map(rows.getString("terms_json")), json.map(rows.getString("state_json")));
                    } catch (DomainException malformed) { continue; }
                    if (key == null) continue;
                    update.setString(1, key); update.setString(2, rows.getString("tenant_id")); update.setString(3, rows.getString("id")); update.executeUpdate();
                }
            }
        }
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE payment_authorization ADD CONSTRAINT ck_payment_debit_key CHECK (debit_account_key IS NULL OR LENGTH(debit_account_key)=64)");
            statement.execute("CREATE INDEX idx_payment_cashier_account ON payment_authorization(tenant_id,legal_entity_id,debit_account_key,authorized_at DESC,id DESC)");
        }
    }

    private static String key(String tenant, String id, String legal, String status, Map<String, Object> terms, Map<String, Object> state) throws Exception {
        if (terms == null || state == null || !Set.of("EXECUTION_REGISTERED", "RETIRED").contains(status)
                || !tenant.equals(terms.get("tenantId")) || !id.equals(terms.get("id")) || !status.equals(state.get("status"))
                || !terms.equals(state.get("terms")) || !(terms.get("payee") instanceof Map<?, ?> payee)
                || !canonical(legal) || !legal.equals(payee.get("legalEntityId"))
                || !(terms.get("amount") instanceof Map<?, ?> amount) || !(amount.get("currency") instanceof String currency) || !currency.matches("[A-Z]{3}")
                || !(terms.get("targetDigest") instanceof String target) || !target.matches("[a-f0-9]{64}")
                || !(state.get("execution") instanceof Map<?, ?> execution) || !(execution.get("debitAccount") instanceof Map<?, ?> account)
                || !(account.get("reference") instanceof String reference) || reference.isBlank() || reference.length() > 128
                || !currency.equals(account.get("currency")) || !(execution.get("command") instanceof Map<?, ?> command)
                || !tenant.equals(command.get("tenantId")) || !id.equals(command.get("id")) || !reference.equals(command.get("debitAccountReference"))
                || !amount.equals(command.get("amount")) || !payee.equals(command.get("payee")) || !Objects.equals(terms.get("binding"), command.get("binding"))) return null;
        var digest = MessageDigest.getInstance("SHA-256");
        for (String value : new String[] {"agentflow-cashier-debit-account-1", tenant, target, legal, currency, reference}) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array()); digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean canonical(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }
}
