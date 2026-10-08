package io.agentflow.budget;


import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.budget.mapper.BudgetAdjustmentQueryMapper;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.mybatis.SqlRows;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 预算调整申请本人目录，限制分页和归属，不返回完整预算调整申请行或外部事实。
 *
 * @author owlzhangfq@gmail.com
 */
@Service
@Transactional(
        readOnly = true,
        isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class BudgetAdjustmentQuery {
    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;
    private final CurrentActor actors;
    private final BudgetAdjustmentQueryMapper sqlMapper;

    /** 查询边界始终来自当前身份，不接受调用方指定申请人。 */
    public BudgetAdjustmentQuery(CurrentActor actors, BudgetAdjustmentQueryMapper sqlMapper) {
        this.actors = actors;
        this.sqlMapper = sqlMapper;
    }

    /** 草稿和已提交预算调整申请共用一份本人列表，不把普通表单当成预算单据。 */
    public Page list(Map<String, String> parameters) {
        var query = page(parameters, Set.of("limit", "beforeId", "status"));
        var actor = actors.actor();
        var args = new ArrayList<Object>(List.of(actor.tenantId(), actor.userId()));
        String filter = "";
        if (parameters.containsKey("status")) {
            try {
                ApplicationStatus.valueOf(parameters.get("status"));
            } catch (IllegalArgumentException invalid) {
                throw invalid();
            }
            filter += " AND a.status=?";
            args.add(parameters.get("status"));
        }
        if (query.before() != null) {
            var found = sqlMapper.list(actor.tenantId(), actor.userId(), query.before().toString());
            if (found.isEmpty()) throw invalid();
            filter += " AND (r.created_at<? OR (r.created_at=? AND r.id<?))";
            args.add(found.get(0));
            args.add(found.get(0));
            args.add(query.before().toString());
        }
        args.add(query.limit() + 1);
        var rows =
                SqlRows.map(
                        sqlMapper.listQuery(
                                (parameters.containsKey("status")),
                                (query.before() != null),
                                args.toArray()),
                        row ->
                                new Item(
                                        UUID.fromString(row.getString("id")),
                                        UUID.fromString(row.getString("application_id")),
                                        row.getString("business_no"),
                                        row.getString("title"),
                                        ApplicationStatus.valueOf(row.getString("status")),
                                        row.getInt("round_no"),
                                        row.getLong("application_version"),
                                        row.getLong("request_version"),
                                        row.getTimestamp("created_at").toInstant()));
        var items = rows.stream().limit(query.limit()).toList();
        return new Page(
                items, rows.size() > query.limit() ? items.get(items.size() - 1).id() : null);
    }

    private static PageQuery page(Map<String, String> parameters, Set<String> allowed) {
        if (!allowed.containsAll(parameters.keySet())) throw invalid();
        try {
            String rawLimit = parameters.getOrDefault("limit", String.valueOf(DEFAULT_LIMIT));
            if (!rawLimit.matches("[1-9][0-9]{0,2}")) throw invalid();
            int limit = Integer.parseInt(rawLimit);
            if (limit > MAX_LIMIT) throw invalid();
            UUID before = parameters.containsKey("beforeId") ? UUID.fromString(parameters.get("beforeId")) : null;
            if (before != null && !before.toString().equals(parameters.get("beforeId"))) throw invalid();
            return new PageQuery(limit, before);
        } catch (IllegalArgumentException invalid) { throw invalid(); }
    }

    private static DomainException invalid() { return new DomainException("INVALID_BUDGET_ADJUSTMENT_QUERY", "Budget adjustment query or owned pagination boundary is invalid"); }

    /**
     * 有界本人分页参数。
     *
     * @author owlzhangfq@gmail.com
     */
    private record PageQuery(int limit, UUID before) {}

    /**
     * 本人预算调整申请摘要，不复制完整财务明细。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Item(
            UUID id,
            UUID applicationId,
            String businessNo,
            String title,
            ApplicationStatus status,
            int roundNo,
            long applicationVersion,
            long requestVersion,
            Instant createdAt) {}

    /**
     * 本人预算调整申请页按创建时间和编号稳定分页。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Page(List<Item> items, UUID nextBeforeId) {}
}
