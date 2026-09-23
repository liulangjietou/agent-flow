package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.sql.Connection;
import java.util.Map;
import java.util.Set;

/**
 * 从旧审计的明确操作人事实建立查询索引，不改写原正文、时间或动作。
 * @author owlzhangfq@gmail.com
 */
public class V10__Index_personal_workspace extends BaseJavaMigration {
    private static final Set<String> HANDLED_ACTIONS = Set.of("APPROVE", "RETURN", "REJECT", "TRANSFER", "DELEGATE");

    /** 固定迁移校验和；发布后迁移实现保持不可变。 */
    @Override
    public Integer getChecksum() { return 2026092301; }

    /** 使用 JDBC 与统一 JSON 工具兼容 H2 和 PostgreSQL。 */
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE audit_event ADD COLUMN actor_id VARCHAR(128) NULL");
            statement.execute("CREATE INDEX idx_audit_actor_time ON audit_event (tenant_id, actor_id, occurred_at DESC, id DESC)");
            statement.execute("CREATE INDEX idx_application_owner_created ON approval_application (tenant_id, created_by, created_at DESC, id DESC)");
        }
        JsonUtil json = new JsonUtil(new ObjectMapper());
        try (var select = connection.prepareStatement("""
                SELECT e.id, e.application_id, e.action, e.payload_json FROM audit_event e
                JOIN approval_application a ON a.tenant_id=e.tenant_id AND a.id=e.application_id
                WHERE e.aggregate_type='Task' AND e.application_id IS NOT NULL
                """); var update = connection.prepareStatement("UPDATE audit_event SET actor_id=? WHERE id=?")) {
            select.setFetchSize(256);
            try (var rows = select.executeQuery()) {
                while (rows.next()) {
                    Map<String, Object> payload;
                    try { payload = json.map(rows.getString("payload_json")); }
                    catch (DomainException invalidJson) { continue; }
                    if (payload == null || !(payload.get("actor") instanceof String actor) || actor.isBlank()
                            || actor.length() > 128 || !rows.getString("application_id").equals(payload.get("applicationId"))
                            || rows.getString("action") == null || !HANDLED_ACTIONS.contains(rows.getString("action"))
                            || !rows.getString("action").equals(payload.get("action"))) continue;
                    update.setString(1, actor); update.setString(2, rows.getString("id")); update.executeUpdate();
                }
            }
        }
    }
}
