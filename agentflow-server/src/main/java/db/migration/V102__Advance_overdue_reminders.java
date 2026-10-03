package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.JsonUtil;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.time.LocalDate;

/**
 * 从不可变放款背景补齐约定归还日；提醒证据独立保存，不修改原余额和资金历史。
 * @author owlzhangfq@gmail.com
 */
public class V102__Advance_overdue_reminders extends BaseJavaMigration {
    /** 固定历史迁移契约。 */
    @Override public Integer getChecksum() { return 2026100302; }

    /** 损坏日期直接阻断升级，不将无法核对的借款当作未逾期。 */
    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection(); var json = new JsonUtil(new ObjectMapper());
        try (var statement = connection.createStatement()) { statement.execute("ALTER TABLE employee_advance_order ADD COLUMN due_on DATE"); }
        try (var select = connection.prepareStatement("SELECT tenant_id,id,context_json FROM finance_resource WHERE resource_type='ADVANCE'");
             var update = connection.prepareStatement("UPDATE employee_advance_order SET due_on=? WHERE tenant_id=? AND advance_id=?")) {
            select.setFetchSize(256);
            try (var rows = select.executeQuery()) {
                while (rows.next()) {
                    LocalDate dueOn;
                    try { dueOn = LocalDate.parse((String) json.map(rows.getString("context_json")).get("dueOn")); }
                    catch (RuntimeException invalid) { throw new IllegalStateException("Cannot index advance with invalid due date: " + rows.getString("tenant_id") + "/" + rows.getString("id")); }
                    update.setDate(1, java.sql.Date.valueOf(dueOn)); update.setString(2, rows.getString("tenant_id")); update.setString(3, rows.getString("id"));
                    if (update.executeUpdate() != 1) throw new IllegalStateException("Advance due date index is missing");
                }
            }
        }
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE employee_advance_order ALTER COLUMN due_on SET NOT NULL");
            statement.execute("CREATE INDEX idx_advance_due_owner ON employee_advance_order(tenant_id,employee_id,legal_entity_id,due_on,advance_id)");
            statement.execute("CREATE INDEX idx_advance_due_scan ON employee_advance_order(due_on,tenant_id,advance_id)");
            statement.execute("""
                    CREATE TABLE advance_overdue_reminder (
                        tenant_id VARCHAR(64) NOT NULL,
                        advance_id VARCHAR(36) NOT NULL,
                        inbox_id VARCHAR(36) NOT NULL,
                        employee_id VARCHAR(128) NOT NULL,
                        advance_version BIGINT NOT NULL CHECK (advance_version > 0),
                        observed_on DATE NOT NULL,
                        time_zone VARCHAR(64) NOT NULL,
                        outstanding DECIMAL(17,2) NOT NULL CHECK (outstanding > 0),
                        currency VARCHAR(3) NOT NULL,
                        created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                        PRIMARY KEY (tenant_id,advance_id),
                        CONSTRAINT fk_overdue_advance FOREIGN KEY (tenant_id,advance_id) REFERENCES employee_advance_order(tenant_id,advance_id),
                        CONSTRAINT fk_overdue_inbox FOREIGN KEY (inbox_id) REFERENCES notification_inbox(id)
                    )
                    """);
        }
    }
}
