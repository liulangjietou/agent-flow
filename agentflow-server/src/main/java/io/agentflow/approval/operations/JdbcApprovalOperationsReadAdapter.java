package io.agentflow.approval.operations;

import io.agentflow.approval.history.JdbcSubmissionHistoryGapQuery;
import io.agentflow.approval.operations.mapper.ApprovalOperationsReadAdapterMapper;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 在数据库中聚合提交轮次与当前任务，不读取审批正文，不加载全量申请到内存。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcApprovalOperationsReadAdapter implements ApprovalOperationsReadPort {
    private static final int PROCESS_LIMIT = 50;
    // 使用 UTC epoch 日，避免不同数据库把带时区时间转 DATE 时重新应用会话时区。

    private static final int WAITING_LIMIT = 20;

    private final ApprovalOperationsReadAdapterMapper sqlMapper;
    private final JdbcSubmissionHistoryGapQuery historyGaps;

    /** 复用审批与 Flowable 数据源，只读查询不写入统计状态。 */
    public JdbcApprovalOperationsReadAdapter(
            ApprovalOperationsReadAdapterMapper sqlMapper,
            JdbcSubmissionHistoryGapQuery historyGaps) {
        this.sqlMapper = sqlMapper;
        this.historyGaps = historyGaps;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Report read(String tenantId, Query query, Instant generatedAt) {
        var roundParameters =
                new ArrayList<Object>(
                        List.of(
                                tenantId,
                                query.fromInclusive().atOffset(ZoneOffset.UTC),
                                query.toExclusive().atOffset(ZoneOffset.UTC)));

        filters(roundParameters, query);
        Metrics metrics =
                SqlRows.single(
                        SqlRows.map(
                                sqlMapper.readQuery(
                                        (!query.processKey().isEmpty()),
                                        (query.definitionVersion() != null),
                                        ((query.organization()).isEmpty()),
                                        roundParameters.toArray()),
                                row -> metrics(row)));

        var dailyCounts = new HashMap<LocalDate, Long>();
        sqlMapper
                .readQuery2(
                        (!query.processKey().isEmpty()),
                        (query.definitionVersion() != null),
                        ((query.organization()).isEmpty()),
                        roundParameters.toArray())
                .forEach(
                        row ->
                                dailyCounts.put(
                                        LocalDate.ofEpochDay(row.getLong("submitted_day")),
                                        row.getLong("submitted")));
        List<Daily> daily =
                query.from()
                        .datesUntil(query.to().plusDays(1))
                        .map(day -> new Daily(day, dailyCounts.getOrDefault(day, 0L)))
                        .toList();
        List<ProcessSummary> processes =
                SqlRows.map(
                        sqlMapper.readQuery3(
                                (!query.processKey().isEmpty()),
                                (query.definitionVersion() != null),
                                ((query.organization()).isEmpty()),
                                roundParameters.toArray()),
                        row ->
                                new ProcessSummary(
                                        row.getString("process_key"),
                                        row.getLong("definition_version"),
                                        metrics(row)));

        var taskParameters = new ArrayList<Object>(List.of(tenantId));

        filters(taskParameters, query);
        var nodeParameters =
                new ArrayList<Object>(
                        List.of(
                                java.sql.Timestamp.from(generatedAt),
                                java.sql.Timestamp.from(generatedAt)));
        nodeParameters.addAll(taskParameters);
        // 窗口在 LIMIT 前汇总全部分组，待办总数不会随展示截断，也不必重新联查引擎表。
        List<WaitingNodeRow> nodeRows =
                SqlRows.map(
                        sqlMapper.readQuery4(
                                query.organization().isEmpty(),
                                (!query.processKey().isEmpty()),
                                (query.definitionVersion() != null),
                                ((query.organization()).isEmpty()),
                                nodeParameters.toArray()),
                        row -> {
                            Instant oldest = row.getTimestamp("oldest").toInstant();
                            var node =
                                    new WaitingNode(
                                            row.getString("process_key"),
                                            row.getLong("definition_version"),
                                            row.getString("TASK_DEF_KEY_"),
                                            row.getString("NAME_"),
                                            row.getLong("tasks"),
                                            oldest,
                                            elapsed(oldest, generatedAt),
                                            row.getLong("overdue_tasks"));
                            return new WaitingNodeRow(
                                    node, row.getLong("total_tasks"), row.getLong("overdue_total"));
                        });
        long pending = nodeRows.isEmpty() ? 0 : nodeRows.get(0).totalTasks();
        long overdue = nodeRows.isEmpty() ? 0 : nodeRows.get(0).overdueTasks();
        List<WaitingNode> nodes = nodeRows.stream().map(WaitingNodeRow::node).toList();
        List<WaitingTask> oldestTasks =
                SqlRows.map(
                        sqlMapper.readQuery5(
                                query.organization().isEmpty(),
                                (!query.processKey().isEmpty()),
                                (query.definitionVersion() != null),
                                ((query.organization()).isEmpty()),
                                taskParameters.toArray()),
                        row -> {
                            Instant created = row.getTimestamp("CREATE_TIME_").toInstant();
                            return new WaitingTask(
                                    row.getString("ID_"),
                                    row.getString("NAME_"),
                                    row.getString("id"),
                                    row.getString("business_no"),
                                    row.getString("title"),
                                    row.getString("process_key"),
                                    row.getLong("definition_version"),
                                    row.getInt("round_no"),
                                    row.getString("ASSIGNEE_"),
                                    created,
                                    elapsed(created, generatedAt),
                                    row.getTimestamp("DUE_DATE_") == null
                                            ? null
                                            : row.getTimestamp("DUE_DATE_").toInstant());
                        });
        return new Report(
                generatedAt,
                query.from(),
                query.to(),
                "UTC",
                query.processKey(),
                query.definitionVersion(),
                query.organization(),
                metrics,
                daily,
                head(processes, PROCESS_LIMIT),
                processes.size() > PROCESS_LIMIT,
                pending,
                overdue,
                head(nodes, WAITING_LIMIT),
                nodes.size() > WAITING_LIMIT,
                head(oldestTasks, WAITING_LIMIT),
                oldestTasks.size() > WAITING_LIMIT,
                historyGaps.count(tenantId, query.processKey(), query.definitionVersion()),
                sla(query, roundParameters),
                notifications(query, roundParameters),
                agent(query, roundParameters));
    }

    // 三项结果共用原轮次筛选，避免重提后用当前组织、版本或状态覆盖旧队列。

    private SlaMetrics sla(Query query, List<Object> parameters) {
        return SqlRows.single(
                SqlRows.map(
                        sqlMapper.slaRows(
                                (!query.processKey().isEmpty()),
                                (query.definitionVersion() != null),
                                ((query.organization()).isEmpty()),
                                parameters.toArray()),
                        row ->
                                SlaMetrics.of(
                                        row.getLong("on_time"),
                                        row.getLong("violated"),
                                        row.getLong("no_deadline"),
                                        row.getLong("invalid_timing"),
                                        row.getLong("cancelled"),
                                        row.getLong("unfinished"),
                                        row.getLong("unrecorded"),
                                        row.getLong("unverified_rounds"))));
    }

    private NotificationMetrics notifications(Query query, List<Object> parameters) {
        return SqlRows.single(
                SqlRows.map(
                        sqlMapper.notificationsRows(
                                (!query.processKey().isEmpty()),
                                (query.definitionVersion() != null),
                                ((query.organization()).isEmpty()),
                                parameters.toArray()),
                        row ->
                                new NotificationMetrics(
                                        row.getLong("deliveries"),
                                        row.getLong("accepted"),
                                        row.getLong("failed"),
                                        row.getLong("retry_waiting"),
                                        row.getLong("unknown_count"),
                                        row.getLong("suppressed"),
                                        row.getLong("pending"),
                                        row.getLong("in_flight"),
                                        row.getLong("previously_failed"))));
    }

    private AgentMetrics agent(Query query, List<Object> parameters) {
        return SqlRows.single(
                SqlRows.map(
                        sqlMapper.agentRows(
                                (!query.processKey().isEmpty()),
                                (query.definitionVersion() != null),
                                ((query.organization()).isEmpty()),
                                parameters.toArray()),
                        row ->
                                AgentMetrics.of(
                                        row.getLong("queued"),
                                        row.getLong("running"),
                                        row.getLong("awaiting_review"),
                                        row.getLong("failed"),
                                        row.getLong("adopted"),
                                        row.getLong("dismissed"))));
    }

    private static Metrics metrics(SqlRow row) {
        var seconds = row.getBigDecimal("average_seconds");
        return Metrics.of(row.getLong("submitted"), row.getLong("applications"), row.getLong("in_approval"),
                row.getLong("approved"), row.getLong("returned"), row.getLong("rejected"), row.getLong("withdrawn"),
                row.getLong("duration_samples"), seconds == null ? null : seconds.setScale(0, RoundingMode.HALF_UP).longValueExact());
    }

    private static void filters(List<Object> parameters, Query query) {
        if (!query.processKey().isEmpty()) {
            parameters.add(query.processKey());
        }
        if (query.definitionVersion() != null) {
            parameters.add(query.definitionVersion());
        }
        io.agentflow.approval.RoundOrganizationSearchParameters.append(
                parameters, query.organization());
    }

    private static long elapsed(Instant start, Instant end) { return Math.max(0, Duration.between(start, end).getSeconds()); }

    private static <T> List<T> head(List<T> rows, int limit) { return List.copyOf(rows.subList(0, Math.min(rows.size(), limit))); }

    /**
     * 节点聚合行附带截断前的任务总数，仅用于基础设施层结果映射。
     *
     * @author owlzhangfq@gmail.com
     */
    private record WaitingNodeRow(WaitingNode node, long totalTasks, long overdueTasks) {}
}
