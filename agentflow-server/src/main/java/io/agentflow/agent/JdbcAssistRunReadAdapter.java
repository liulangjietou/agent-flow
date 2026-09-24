package io.agentflow.agent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 数据库执行有界摘要投影，列表不能带出 context_json 或 state_json。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcAssistRunReadAdapter implements AssistRunReadPort {
    private final JdbcTemplate jdbc;

    /** 注入与审批平台相同的数据源。 */
    public JdbcAssistRunReadAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public List<Item> list(String tenantId, UUID applicationId, Query query) {
        StringBuilder sql = new StringBuilder("""
                SELECT id,application_version,round_no,status,version,created_at
                FROM agent_assist_run WHERE tenant_id=? AND application_id=?
                """);
        var arguments = new ArrayList<Object>(List.of(tenantId, applicationId.toString()));
        if (query.roundNo() != null) { sql.append(" AND round_no=?"); arguments.add(query.roundNo()); }
        if (query.beforeTime() != null) {
            sql.append(" AND (created_at<? OR (created_at=? AND id<?))");
            Timestamp time = Timestamp.from(query.beforeTime());
            arguments.add(time); arguments.add(time); arguments.add(query.beforeId().toString());
        }
        sql.append(" ORDER BY created_at DESC,id DESC LIMIT ?");
        arguments.add(query.limit() + 1);
        return jdbc.query(sql.toString(), (row, index) -> new Item(UUID.fromString(row.getString("id")),
                row.getLong("application_version"), row.getInt("round_no"), AssistRun.Status.valueOf(row.getString("status")),
                row.getLong("version"), row.getTimestamp("created_at").toInstant()), arguments.toArray());
    }
}
