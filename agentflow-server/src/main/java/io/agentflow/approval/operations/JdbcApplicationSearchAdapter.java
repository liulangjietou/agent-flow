package io.agentflow.approval.operations;

import io.agentflow.common.Actor;
import io.agentflow.approval.process.FlowableApplicationParticipationSql;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 申请检索直接投影有界摘要，不加载正文、审计或引擎实体。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcApplicationSearchAdapter implements ApplicationSearchPort {
    private final JdbcTemplate jdbc;

    /** 复用业务数据库连接。 */
    public JdbcApplicationSearchAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public List<Item> search(Actor actor, Query query) {
        var parameters = new ArrayList<Object>(List.of(actor.tenantId()));
        StringBuilder sql = new StringBuilder("""
                SELECT id,business_no,title,process_key,definition_version,created_by,status,round_no,created_at,updated_at
                FROM approval_application a WHERE tenant_id=?
                """);
        if (!actor.hasRole("ADMIN")) {
            sql.append(" AND (a.created_by=? OR "); parameters.add(actor.userId());
            sql.append(FlowableApplicationParticipationSql.predicate(actor, parameters));
            // 转交会改变引擎指派人，真实办理审计保留原办理人的读取权限。
            sql.append("""
                    OR EXISTS (SELECT 1 FROM audit_event e WHERE e.tenant_id=a.tenant_id AND e.application_id=a.id
                        AND e.actor_id=? AND e.aggregate_type='Task'
                        AND e.action IN ('APPROVE','RETURN','REJECT','TRANSFER','DELEGATE','RESOLVE')))
                    """);
            parameters.add(actor.userId());
        }
        if (!query.text().isEmpty()) {
            String pattern = "%" + query.text().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            sql.append(" AND (LOWER(title) LIKE ? ESCAPE '!' OR LOWER(business_no) LIKE ? ESCAPE '!')");
            parameters.addAll(List.of(pattern, pattern));
        }
        if (!query.status().isEmpty()) { sql.append(" AND status=?"); parameters.add(query.status()); }
        if (!query.processKey().isEmpty()) { sql.append(" AND process_key=?"); parameters.add(query.processKey()); }
        if (query.definitionVersion() != null) { sql.append(" AND definition_version=?"); parameters.add(query.definitionVersion()); }
        if (!query.applicant().isEmpty()) { sql.append(" AND created_by=?"); parameters.add(query.applicant()); }
        if (query.createdFrom() != null) { sql.append(" AND created_at>=?"); parameters.add(Timestamp.from(query.createdFrom())); }
        if (query.createdBefore() != null) { sql.append(" AND created_at<?"); parameters.add(Timestamp.from(query.createdBefore())); }
        if (query.beforeTime() != null) {
            sql.append(" AND (created_at<? OR (created_at=? AND id<?))");
            parameters.add(Timestamp.from(query.beforeTime())); parameters.add(Timestamp.from(query.beforeTime())); parameters.add(query.beforeId().toString());
        }
        sql.append(" ORDER BY created_at DESC,id DESC LIMIT ?"); parameters.add(query.limit() + 1);
        return jdbc.query(sql.toString(), (row, index) -> new Item(UUID.fromString(row.getString("id")), row.getString("business_no"),
                row.getString("title"), row.getString("process_key"), row.getLong("definition_version"), row.getString("created_by"),
                row.getString("status"), row.getInt("round_no"), row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant()), parameters.toArray());
    }
}
