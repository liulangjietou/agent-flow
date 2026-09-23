package io.agentflow.approval.operations;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 以追加事件时间做有界查询，关联申请仅限同租户，保留无法关联的旧事件。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcAuditSearchAdapter implements AuditSearchPort {
    private final JdbcTemplate jdbc;

    /** 复用业务数据库，只读取审计投影与申请摘要。 */
    public JdbcAuditSearchAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public List<Item> search(String tenantId, Query query) {
        var parameters = new ArrayList<Object>(List.of(tenantId));
        StringBuilder sql = new StringBuilder("""
                SELECT e.id,e.event_id,e.aggregate_type,e.aggregate_id,e.aggregate_version,e.action,e.actor_id,e.occurred_at,
                       a.id AS linked_id,a.business_no,a.title
                FROM audit_event e LEFT JOIN approval_application a
                  ON a.tenant_id=e.tenant_id AND a.id=COALESCE(e.application_id,
                      CASE WHEN e.aggregate_type='Application' THEN e.aggregate_id ELSE NULL END)
                WHERE e.tenant_id=?
                """);
        if (!query.text().isEmpty()) {
            String pattern = "%" + query.text().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            sql.append(" AND (LOWER(a.title) LIKE ? ESCAPE '!' OR LOWER(a.business_no) LIKE ? ESCAPE '!')");
            parameters.addAll(List.of(pattern, pattern));
        }
        if (!query.actor().isEmpty()) { sql.append(" AND e.actor_id=?"); parameters.add(query.actor()); }
        if (!query.action().isEmpty()) { sql.append(" AND e.action=?"); parameters.add(query.action()); }
        if (!query.source().isEmpty()) { sql.append(" AND e.aggregate_type=?"); parameters.add(query.source()); }
        if (query.applicationId() != null) { sql.append(" AND a.id=?"); parameters.add(query.applicationId().toString()); }
        if (query.occurredFrom() != null) { sql.append(" AND e.occurred_at>=?"); parameters.add(Timestamp.from(query.occurredFrom())); }
        if (query.occurredBefore() != null) { sql.append(" AND e.occurred_at<?"); parameters.add(Timestamp.from(query.occurredBefore())); }
        if (query.beforeTime() != null) {
            sql.append(" AND (e.occurred_at<? OR (e.occurred_at=? AND e.id<?))");
            parameters.add(Timestamp.from(query.beforeTime())); parameters.add(Timestamp.from(query.beforeTime())); parameters.add(query.beforeId().toString());
        }
        sql.append(" ORDER BY e.occurred_at DESC,e.id DESC LIMIT ?"); parameters.add(query.limit() + 1);
        return jdbc.query(sql.toString(), (row, index) -> new Item(UUID.fromString(row.getString("id")), row.getString("event_id"),
                row.getString("aggregate_type"), row.getString("aggregate_id"), row.getLong("aggregate_version"), row.getString("action"),
                row.getString("actor_id"), row.getTimestamp("occurred_at").toInstant(),
                row.getString("linked_id") == null ? null : UUID.fromString(row.getString("linked_id")),
                row.getString("business_no"), row.getString("title")), parameters.toArray());
    }
}
