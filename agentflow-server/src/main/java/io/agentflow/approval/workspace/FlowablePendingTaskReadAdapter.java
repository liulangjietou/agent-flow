package io.agentflow.approval.workspace;

import io.agentflow.approval.model.SubmissionRisk;
import io.agentflow.approval.process.FlowableApprovalProxyAccess;
import io.agentflow.approval.process.FlowableTaskAuthorization;
import io.agentflow.approval.service.TaskRecipientDirectory;
import io.agentflow.approval.workspace.mapper.FlowablePendingTaskReadAdapterMapper;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Flowable 7.2 防腐层的只读联查；任务事实来自引擎，不新增任务状态副本。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class FlowablePendingTaskReadAdapter implements PendingTaskReadPort {
    private final FlowablePendingTaskReadAdapterMapper sqlMapper;
    private final JsonUtil json;
    private final TaskRecipientDirectory recipients;
    private final FlowableApprovalProxyAccess proxies;
    private final FlowableTaskAuthorization authorization;

    /** 共享审批与引擎数据源；所有写入仍通过原审批应用服务。 */
    public FlowablePendingTaskReadAdapter(
            FlowablePendingTaskReadAdapterMapper sqlMapper,
            TaskRecipientDirectory recipients,
            JsonUtil json,
            FlowableApprovalProxyAccess proxies,
            FlowableTaskAuthorization authorization) {
        this.sqlMapper = sqlMapper;
        this.recipients = recipients;
        this.json = json;
        this.proxies = proxies;
        this.authorization = authorization;
    }

    /** 将授权分页和完整计数组合读取，空的后续页在同一只读事务内补取计数。 */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Result read(Actor actor, Query query) {
        if (!actor.hasRole("APPROVER") || !recipients.eligible(actor.tenantId(), actor.userId()))
            return new Result(List.of(), 0);
        var proxyTaskIds = proxies.forActor(actor, query.deadlineAt()).taskIds();
        var conflictingTasks = authorization.conflictingExpenseTaskIds(actor);
        var parameters = new ArrayList<Object>();
        // 窗口先统计全部授权匹配项，外层再应用游标与上限，避免翻页后总数缩水。
        where(actor, query, proxyTaskIds, conflictingTasks, parameters);
        if (query.afterTime() != null) {

            parameters.addAll(
                    List.of(
                            Timestamp.from(query.afterTime()),
                            Timestamp.from(query.afterTime()),
                            query.afterId()));
        }

        parameters.add(query.limit() + 1);
        Result page =
                restoreRead(
                        actor,
                        query,
                        sqlMapper.readRows(
                                actor.roles().size(),
                                (!actor.roles().isEmpty()),
                                proxyTaskIds.size(),
                                (!proxyTaskIds.isEmpty()),
                                conflictingTasks.size(),
                                (!conflictingTasks.isEmpty()),
                                java.util.Objects.equals(query.assignment(), "assigned"),
                                java.util.Objects.equals(query.assignment(), "unclaimed"),
                                java.util.Objects.equals(query.assignment(), "delegated"),
                                query.deadline().name().equals("OVERDUE"),
                                query.deadline().name().equals("PENDING"),
                                query.deadline().name().equals("UNRECORDED"),
                                (query.risk() != null),
                                (!query.text().isEmpty()),
                                ((query.organization()).isEmpty()),
                                (!query.processKey().isEmpty()),
                                (!query.applicant().isEmpty()),
                                (query.minAmount() != null),
                                (query.maxAmount() != null),
                                (query.afterTime() != null),
                                parameters.toArray()));
        if (!page.items().isEmpty() || query.afterTime() == null) return page;
        // 后续页可能因办理完成而变空；同一只读事务内补取总数，不能把前面的待办误报为零。
        parameters.clear();
        where(actor, query, proxyTaskIds, conflictingTasks, parameters);
        long total =
                SqlRows.single(
                        sqlMapper.readQuery(
                                actor.roles().size(),
                                (!actor.roles().isEmpty()),
                                proxyTaskIds.size(),
                                (!proxyTaskIds.isEmpty()),
                                conflictingTasks.size(),
                                (!conflictingTasks.isEmpty()),
                                java.util.Objects.equals(query.assignment(), "assigned"),
                                java.util.Objects.equals(query.assignment(), "unclaimed"),
                                java.util.Objects.equals(query.assignment(), "delegated"),
                                query.deadline().name().equals("OVERDUE"),
                                query.deadline().name().equals("PENDING"),
                                query.deadline().name().equals("UNRECORDED"),
                                (query.risk() != null),
                                (!query.text().isEmpty()),
                                ((query.organization()).isEmpty()),
                                (!query.processKey().isEmpty()),
                                (!query.applicant().isEmpty()),
                                (query.minAmount() != null),
                                (query.maxAmount() != null),
                                parameters.toArray()));
        return new Result(List.of(), total);
    }

    private Item item(SqlRow row) {
        var amount = row.getBigDecimal("search_amount");
        String delegation = row.getString("DELEGATION_");
        return new Item(row.getString("ID_"), row.getString("NAME_"), row.getString("id"), row.getString("business_no"),
                row.getString("title"), row.getString("process_key"), row.getLong("definition_version"), row.getString("created_by"),
                amount == null ? null : amount.stripTrailingZeros().toPlainString(), row.getInt("round_no"),
                row.getString("ASSIGNEE_"), row.getString("OWNER_"), delegation == null ? "NONE" : delegation,
                row.getTimestamp("CREATE_TIME_").toInstant(),
                row.getTimestamp("DUE_DATE_") == null ? null : row.getTimestamp("DUE_DATE_").toInstant(),
                row.getString("initiator_legal_entity_name"), row.getString("initiator_department_name"), row.getString("initiator_position_name"),
                row.getString("risk_json") == null ? SubmissionRisk.unassessed()
                        : json.read(row.getString("risk_json"), SubmissionRisk.class));
    }

    private void where(
            Actor actor,
            Query query,
            List<String> proxyTaskIds,
            List<String> conflictingTasks,
            List<Object> parameters) {
        parameters.add(actor.tenantId());

        parameters.add(actor.userId());
        parameters.add(actor.userId());
        if (!actor.roles().isEmpty()) {

            parameters.addAll(actor.roles().stream().sorted().toList());
        }

        if (!proxyTaskIds.isEmpty()) {

            parameters.addAll(proxyTaskIds);
        }

        if (!conflictingTasks.isEmpty()) {

            parameters.addAll(conflictingTasks);
        }

        // 列表、窗口计数和空后续页补计数共用同一个服务端时刻。
        switch (query.deadline()) {
            case OVERDUE -> {
                parameters.add(Timestamp.from(query.deadlineAt()));
            }
            case PENDING -> {
                parameters.add(Timestamp.from(query.deadlineAt()));
            }
            case UNRECORDED -> {}
            case ALL -> {}
        }
        if (query.risk() != null) {

            parameters.add(query.risk().name());
        }
        if (!query.text().isEmpty()) {
            String pattern = literalPattern(query.text());

            parameters.addAll(List.of(pattern, pattern, pattern));
        }
        io.agentflow.approval.RoundOrganizationSearchParameters.append(
                parameters, query.organization());
        if (!query.processKey().isEmpty()) {
            parameters.add(query.processKey());
        }
        if (!query.applicant().isEmpty()) {
            parameters.add(query.applicant());
        }
        if (query.minAmount() != null) {
            parameters.add(query.minAmount());
        }
        if (query.maxAmount() != null) {
            parameters.add(query.maxAmount());
        }
        return;
    }

    private static String literalPattern(String value) {
        return "%" + value.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    /** 根据完整查询投影恢复 read 的结果。 */
    private Result restoreRead(Actor actor, Query query, java.util.List<SqlRow> persistedRows) {
        var items = new ArrayList<Item>();
        long total = 0;
        for (var rows : persistedRows) {
            total = rows.getLong("matching_total");
            items.add(item(rows));
        }
        return new Result(List.copyOf(items), total);
    }
}
