package io.agentflow.approval.workspace;

import io.agentflow.common.Actor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
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

    /** 将授权分页和完整计数组合读取，空的后续页在同一只读事务内补取计数。 */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Result read(Actor actor, Query query) {
        if (!actor.hasRole("APPROVER")) return new Result(List.of(), 0);
        var parameters = new ArrayList<Object>();
        // 窗口先统计全部授权匹配项，外层再应用游标与上限，避免翻页后总数缩水。
        StringBuilder sql = new StringBuilder("""
                SELECT p.* FROM (
                    SELECT t.ID_,t.NAME_,t.ASSIGNEE_,t.OWNER_,t.DELEGATION_,t.CREATE_TIME_,
                           a.id,a.business_no,a.title,a.process_key,a.definition_version,a.created_by,a.search_amount,a.round_no,
                           COUNT(*) OVER () AS matching_total
                """).append(where(actor, query, parameters)).append(") p");
        if (query.afterTime() != null) {
            sql.append(" WHERE (p.CREATE_TIME_>? OR (p.CREATE_TIME_=? AND p.ID_>?))");
            parameters.addAll(List.of(Timestamp.from(query.afterTime()), Timestamp.from(query.afterTime()), query.afterId()));
        }
        sql.append(" ORDER BY p.CREATE_TIME_ ASC,p.ID_ ASC LIMIT ?");
        parameters.add(query.limit() + 1);
        Result page = jdbc.query(sql.toString(), (ResultSet rows) -> {
            var items = new ArrayList<Item>();
            long total = 0;
            while (rows.next()) {
                total = rows.getLong("matching_total");
                items.add(item(rows));
            }
            return new Result(List.copyOf(items), total);
        }, parameters.toArray());
        if (!page.items().isEmpty() || query.afterTime() == null) return page;
        // 后续页可能因办理完成而变空；同一只读事务内补取总数，不能把前面的待办误报为零。
        parameters.clear();
        long total = jdbc.queryForObject("SELECT COUNT(*) " + where(actor, query, parameters), Long.class, parameters.toArray());
        return new Result(List.of(), total);
    }

    private Item item(ResultSet row) throws SQLException {
        var amount = row.getBigDecimal("search_amount");
        String delegation = row.getString("DELEGATION_");
        return new Item(row.getString("ID_"), row.getString("NAME_"), row.getString("id"), row.getString("business_no"),
                row.getString("title"), row.getString("process_key"), row.getLong("definition_version"), row.getString("created_by"),
                amount == null ? null : amount.stripTrailingZeros().toPlainString(), row.getInt("round_no"),
                row.getString("ASSIGNEE_"), row.getString("OWNER_"), delegation == null ? "NONE" : delegation,
                row.getTimestamp("CREATE_TIME_").toInstant());
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
