package io.agentflow.definition;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 目录查询只投影摘要列，使用绑定参数和租户条件执行有界分页。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcDefinitionCatalogAdapter implements DefinitionCatalogPort {
    private final JdbcTemplate jdbc;

    /** 复用业务数据库连接。 */
    public JdbcDefinitionCatalogAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public List<Item> search(String tenantId, Query query) {
        var arguments = new ArrayList<Object>(List.of(tenantId));
        StringBuilder sql = new StringBuilder("""
                SELECT id,process_key,name,status,version,revision,created_at,updated_at
                FROM approval_definition WHERE tenant_id=?
                """);
        if (!query.text().isEmpty()) {
            String pattern = "%" + query.text().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            sql.append(" AND (LOWER(name) LIKE ? ESCAPE '!' OR LOWER(process_key) LIKE ? ESCAPE '!')");
            arguments.addAll(List.of(pattern, pattern));
        }
        if (!query.status().isEmpty()) { sql.append(" AND status=?"); arguments.add(query.status()); }
        if (!query.processKey().isEmpty()) { sql.append(" AND process_key=?"); arguments.add(query.processKey()); }
        if (query.version() != null) { sql.append(" AND version=?"); arguments.add(query.version()); }
        if (query.beforeTime() != null) {
            sql.append(" AND (created_at<? OR (created_at=? AND id<?))");
            arguments.add(Timestamp.from(query.beforeTime())); arguments.add(Timestamp.from(query.beforeTime())); arguments.add(query.beforeId().toString());
        }
        sql.append(" ORDER BY created_at DESC,id DESC LIMIT ?"); arguments.add(query.limit() + 1);
        return jdbc.query(sql.toString(), (row, index) -> new Item(UUID.fromString(row.getString("id")),
                row.getString("process_key"), row.getString("name"), row.getString("status"), row.getLong("version"),
                row.getLong("revision"), row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant()), arguments.toArray());
    }
}
