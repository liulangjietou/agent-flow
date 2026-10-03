package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.JsonUtil;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * 从原借款不可变背景建立 FIFO 查询索引，不改变余额、占用、版本和历史原文。
 * @author owlzhangfq@gmail.com
 */
public class V101__Index_advance_paid_date extends BaseJavaMigration {
    private static final int FETCH_SIZE = 256;
    /** 固定本次迁移的历史 JSON 契约。 */
    @Override public Integer getChecksum() { return 2026100301; }

    /** 任一历史放款背景损坏即阻断升级，不能静默遗漏最早借款而产生错误 FIFO 建议。 */
    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE employee_advance_order (
                        tenant_id VARCHAR(64) NOT NULL,
                        resource_type VARCHAR(32) NOT NULL DEFAULT 'ADVANCE' CHECK (resource_type='ADVANCE'),
                        advance_id VARCHAR(36) NOT NULL,
                        employee_id VARCHAR(128) NOT NULL,
                        legal_entity_id VARCHAR(36) NOT NULL,
                        currency VARCHAR(3) NOT NULL,
                        paid_on DATE NOT NULL,
                        PRIMARY KEY (tenant_id,advance_id),
                        CONSTRAINT fk_advance_order_resource FOREIGN KEY (tenant_id,resource_type,advance_id)
                            REFERENCES finance_resource(tenant_id,resource_type,id)
                    )
                    """);
        }
        var json = new JsonUtil(new ObjectMapper());
        try (var select = connection.prepareStatement("SELECT tenant_id,id,owner_id,context_json FROM finance_resource WHERE resource_type='ADVANCE'");
             var insert = connection.prepareStatement("INSERT INTO employee_advance_order(tenant_id,advance_id,employee_id,legal_entity_id,currency,paid_on) VALUES(?,?,?,?,?,?)")) {
            select.setFetchSize(FETCH_SIZE);
            try (var rows = select.executeQuery()) {
                while (rows.next()) {
                    String entity, currency; LocalDate paidOn;
                    try {
                        var facts = json.map(rows.getString("context_json"));
                        entity = (String) facts.get("legalEntityId");
                        if (!UUID.fromString(entity).toString().equals(entity)) throw new IllegalArgumentException();
                        var amount = (Map<?, ?>) facts.get("paidAmount"); currency = (String) amount.get("currency");
                        if (!currency.matches("[A-Z]{3}") || java.util.Currency.getInstance(currency).getDefaultFractionDigits() != 2) throw new IllegalArgumentException();
                        paidOn = LocalDate.parse((String) facts.get("paidOn"));
                    } catch (RuntimeException invalid) {
                        throw new IllegalStateException("Cannot index advance with invalid immutable context: " + rows.getString("tenant_id") + "/" + rows.getString("id"));
                    }
                    insert.setString(1, rows.getString("tenant_id")); insert.setString(2, rows.getString("id"));
                    insert.setString(3, rows.getString("owner_id")); insert.setString(4, entity); insert.setString(5, currency);
                    insert.setDate(6, java.sql.Date.valueOf(paidOn)); insert.executeUpdate();
                }
            }
        }
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE INDEX idx_advance_paid_fifo ON employee_advance_order(tenant_id,employee_id,legal_entity_id,currency,paid_on,advance_id)");
        }
    }
}
