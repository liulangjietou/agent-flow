package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.util.Map;
import java.util.Set;

/**
 * 增加租户审计时间索引，并从一致的旧审计事实补充操作人检索列。
 * @author owlzhangfq@gmail.com
 */
public class V16__Index_operation_audit extends BaseJavaMigration {
    private static final Set<String> APPLICATION_ACTIONS = Set.of("CREATE", "REVISE", "SUBMIT", "WITHDRAW", "CANCEL");
    private static final Set<String> TASK_ACTIONS = Set.of("CLAIM", "RELEASE", "TRANSFER", "DELEGATE", "RESOLVE", "RETURN", "REJECT", "APPROVE");

    /** 发布后固定迁移校验和。 */
    @Override
    public Integer getChecksum() { return 2026092341; }

    /** 不改写原事件、时间、动作、申请关联、正文或已有操作人。 */
    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE INDEX idx_audit_tenant_time ON audit_event (tenant_id, occurred_at DESC, id DESC)");
        }
        JsonUtil json = new JsonUtil(new ObjectMapper());
        try (var select = connection.prepareStatement("""
                SELECT e.id,e.aggregate_type,e.aggregate_id,a.id AS linked_id,e.action,e.payload_json
                FROM audit_event e JOIN approval_application a
                  ON a.tenant_id=e.tenant_id AND a.id=COALESCE(e.application_id,
                      CASE WHEN e.aggregate_type='Application' THEN e.aggregate_id ELSE NULL END)
                WHERE e.actor_id IS NULL AND e.aggregate_type IN ('Application','Task')
                """); var update = connection.prepareStatement("UPDATE audit_event SET actor_id=? WHERE id=? AND actor_id IS NULL")) {
            select.setFetchSize(256);
            try (var rows = select.executeQuery()) {
                while (rows.next()) {
                    String action = rows.getString("action"), applicationId = rows.getString("linked_id");
                    boolean application = "Application".equals(rows.getString("aggregate_type"));
                    if (action == null || !(application ? APPLICATION_ACTIONS : TASK_ACTIONS).contains(action)
                            || application && !applicationId.equals(rows.getString("aggregate_id"))) continue;
                    Map<String, Object> payload;
                    try { payload = json.map(rows.getString("payload_json")); }
                    catch (DomainException invalidJson) { continue; }
                    // 只使用原正文中能与已有申请、动作逐项核对的明确账号。
                    if (payload == null || !(payload.get("actor") instanceof String actor) || actor.isBlank()
                            || actor.length() > 128 || actor.chars().anyMatch(Character::isISOControl)
                            || !applicationId.equals(payload.get("applicationId")) || !action.equals(payload.get("action"))) continue;
                    update.setString(1, actor); update.setString(2, rows.getString("id")); update.executeUpdate();
                }
            }
        }
    }
}
