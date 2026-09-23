package io.agentflow.approval.workspace;

import io.agentflow.approval.service.ApplicationParticipantPort;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 通过申请归属与真实审计操作人查询个人读模型；不扫描引擎内部表。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcWorkspaceReadAdapter implements WorkspaceReadPort, ApplicationParticipantPort {
    private static final String HANDLED_ACTIONS = "('APPROVE','RETURN','REJECT','TRANSFER','DELEGATE','RESOLVE')";
    private final JdbcTemplate jdbc;
    private final JsonUtil json;

    /** 注入业务存储与统一 JSON 工具。 */
    public JdbcWorkspaceReadAdapter(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }

    @Override
    public List<ApplicationItem> applications(Actor actor, Query query) {
        var parameters = new ArrayList<Object>(List.of(actor.tenantId(), actor.userId()));
        StringBuilder sql = new StringBuilder("""
                SELECT a.id,a.business_no,a.title,a.process_key,a.definition_version,a.status,a.round_no,a.created_at,a.updated_at
                FROM approval_application a WHERE a.tenant_id=? AND a.created_by=?
                """);
        if (query.drafts() || !query.status().isEmpty()) { sql.append(" AND a.status=?"); parameters.add(query.drafts() ? "DRAFT" : query.status()); }
        appendText(sql, parameters, query.text());
        appendPosition(sql, parameters, query, "a.created_at", "a.id");
        sql.append(" ORDER BY a.created_at DESC,a.id DESC LIMIT ?"); parameters.add(query.limit() + 1);
        return jdbc.query(sql.toString(), (row, index) -> new ApplicationItem(UUID.fromString(row.getString("id")),
                row.getString("business_no"), row.getString("title"), row.getString("process_key"), row.getLong("definition_version"),
                row.getString("status"), row.getInt("round_no"), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant()), parameters.toArray());
    }

    @Override
    public List<HandledItem> handled(Actor actor, Query query) {
        var parameters = new ArrayList<Object>(List.of(actor.tenantId(), actor.userId()));
        StringBuilder sql = new StringBuilder("""
                SELECT e.id,e.aggregate_id,e.application_id,e.action,e.occurred_at,e.payload_json,
                       a.business_no,a.title,a.process_key,a.definition_version,a.status
                FROM audit_event e JOIN approval_application a ON a.tenant_id=e.tenant_id AND a.id=e.application_id
                WHERE e.tenant_id=? AND e.actor_id=? AND e.aggregate_type='Task' AND e.action IN
                """).append(HANDLED_ACTIONS);
        if (!query.action().isEmpty()) { sql.append(" AND e.action=?"); parameters.add(query.action()); }
        appendText(sql, parameters, query.text());
        appendPosition(sql, parameters, query, "e.occurred_at", "e.id");
        sql.append(" ORDER BY e.occurred_at DESC,e.id DESC LIMIT ?"); parameters.add(query.limit() + 1);
        return jdbc.query(sql.toString(), (row, index) -> {
            Map<String, Object> payload = json.map(row.getString("payload_json"));
            Number round = payload.get("roundNo") instanceof Number value ? value : null;
            return new HandledItem(UUID.fromString(row.getString("id")), row.getString("aggregate_id"),
                    UUID.fromString(row.getString("application_id")), row.getString("business_no"), row.getString("title"),
                    row.getString("process_key"), row.getLong("definition_version"), row.getString("status"), row.getString("action"),
                    row.getTimestamp("occurred_at").toInstant(), round == null ? null : round.intValue(),
                    string(payload, "nodeName"), string(payload, "comment"), string(payload, "targetUser"), string(payload, "currentStatus"));
        }, parameters.toArray());
    }

    @Override
    public boolean isParticipant(String tenantId, UUID applicationId, Actor actor) {
        // 历史办理者不因转交后指派人变化而失去访问；仅真实办理动作建立此关系。
        if (!tenantId.equals(actor.tenantId())) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM audit_event WHERE tenant_id=? AND application_id=?
                AND actor_id=? AND aggregate_type='Task' AND action IN
                """ + HANDLED_ACTIONS + ")", Boolean.class, tenantId, applicationId.toString(), actor.userId()));
    }

    private static void appendText(StringBuilder sql, List<Object> parameters, String text) {
        if (text.isEmpty()) return;
        String pattern = "%" + text.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        sql.append(" AND (LOWER(a.title) LIKE ? ESCAPE '!' OR LOWER(a.business_no) LIKE ? ESCAPE '!' OR LOWER(a.process_key) LIKE ? ESCAPE '!')");
        parameters.addAll(List.of(pattern, pattern, pattern));
    }

    private static void appendPosition(StringBuilder sql, List<Object> parameters, Query query, String time, String id) {
        if (query.beforeTime() == null) return;
        sql.append(" AND (").append(time).append("<? OR (").append(time).append("=? AND ").append(id).append("<?))");
        parameters.add(Timestamp.from(query.beforeTime())); parameters.add(Timestamp.from(query.beforeTime())); parameters.add(query.beforeId().toString());
    }

    private static String string(Map<String, Object> values, String key) { return values.get(key) instanceof String value ? value : null; }
}
