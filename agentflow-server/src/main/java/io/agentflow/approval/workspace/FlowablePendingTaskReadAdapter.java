package io.agentflow.approval.workspace;

import io.agentflow.common.Actor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Flowable 7.2 防腐层的只读联查；任务事实来自引擎，不新增任务状态副本。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class FlowablePendingTaskReadAdapter implements PendingTaskReadPort {
    private final JdbcTemplate jdbc;


    /** 共享审批与引擎数据源；所有写入仍通过原审批应用服务。 */
    public FlowablePendingTaskReadAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public List<Item> list(Actor actor, Query query) {
        if (!actor.hasRole("APPROVER")) return List.of();
        var parameters = new ArrayList<Object>();
        StringBuilder sql = new StringBuilder("""
                SELECT t.ID_,t.NAME_,t.ASSIGNEE_,t.OWNER_,t.DELEGATION_,t.CREATE_TIME_,
                       a.id,a.business_no,a.title,a.process_key,a.definition_version,a.created_by,a.search_amount,a.round_no
                """).append(where(actor, query, parameters));
        if (query.afterTime() != null) {
            sql.append(" AND (t.CREATE_TIME_>? OR (t.CREATE_TIME_=? AND t.ID_>?))");
            parameters.addAll(List.of(Timestamp.from(query.afterTime()), Timestamp.from(query.afterTime()), query.afterId()));
        }
        sql.append(" ORDER BY t.CREATE_TIME_ ASC,t.ID_ ASC LIMIT ?"); parameters.add(query.limit() + 1);
        return jdbc.query(sql.toString(), (row, index) -> {
            var amount = row.getBigDecimal("search_amount");
            String delegation = row.getString("DELEGATION_");
            return new Item(row.getString("ID_"), row.getString("NAME_"), row.getString("id"), row.getString("business_no"),
                    row.getString("title"), row.getString("process_key"), row.getLong("definition_version"), row.getString("created_by"),
                    amount == null ? null : amount.stripTrailingZeros().toPlainString(), row.getInt("round_no"),
                    row.getString("ASSIGNEE_"), row.getString("OWNER_"), delegation == null ? "NONE" : delegation,
                    row.getTimestamp("CREATE_TIME_").toInstant());
        }, parameters.toArray());
    }

    @Override
    public long count(Actor actor, Query query) {
        if (!actor.hasRole("APPROVER")) return 0;
        var parameters = new ArrayList<Object>();
        return jdbc.queryForObject("SELECT COUNT(*) " + where(actor, query, parameters), Long.class, parameters.toArray());
    }

    private StringBuilder where(Actor actor, Query query, List<Object> parameters) {
        var sql = new StringBuilder(io.agentflow.approval.process.FlowableActiveTaskSql.fromCurrentApplications()); parameters.add(actor.tenantId());
        sql.append(" AND (t.ASSIGNEE_=? OR (t.ASSIGNEE_ IS NULL AND EXISTS (SELECT 1 FROM ACT_RU_IDENTITYLINK i WHERE i.TASK_ID_=t.ID_ AND i.TYPE_='candidate' AND (i.USER_ID_=?");
        parameters.add(actor.userId()); parameters.add(actor.userId());
        if (!actor.roles().isEmpty()) {
            sql.append(" OR i.GROUP_ID_ IN (").append(String.join(",", Collections.nCopies(actor.roles().size(), "?"))).append(")");
            parameters.addAll(actor.roles().stream().sorted().toList());
        }
        sql.append("))))");
        switch (query.assignment()) {
            case "assigned" -> sql.append(" AND t.ASSIGNEE_ IS NOT NULL");
            case "unclaimed" -> sql.append(" AND t.ASSIGNEE_ IS NULL");
            case "delegated" -> sql.append(" AND t.DELEGATION_='PENDING'");
            default -> { }
        }
        if (!query.text().isEmpty()) {
            String pattern = "%" + query.text().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            sql.append(" AND (LOWER(a.title) LIKE ? ESCAPE '!' OR LOWER(a.business_no) LIKE ? ESCAPE '!' OR LOWER(t.NAME_) LIKE ? ESCAPE '!')");
            parameters.addAll(List.of(pattern, pattern, pattern));
        }
        if (!query.processKey().isEmpty()) { sql.append(" AND a.process_key=?"); parameters.add(query.processKey()); }
        if (!query.applicant().isEmpty()) { sql.append(" AND a.created_by=?"); parameters.add(query.applicant()); }
        if (query.minAmount() != null) { sql.append(" AND a.search_amount>=?"); parameters.add(query.minAmount()); }
        if (query.maxAmount() != null) { sql.append(" AND a.search_amount<=?"); parameters.add(query.maxAmount()); }
        return sql;
    }
}
