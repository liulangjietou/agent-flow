package io.agentflow.approval.operations;

import io.agentflow.approval.history.JdbcSubmissionHistoryGapQuery;
import io.agentflow.approval.process.FlowableActiveTaskSql;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 在数据库中聚合提交轮次与当前任务，不读取审批正文，不加载全量申请到内存。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcApprovalOperationsReadAdapter implements ApprovalOperationsReadPort {
    private static final int PROCESS_LIMIT = 50;
    // 使用 UTC epoch 日，避免不同数据库把带时区时间转 DATE 时重新应用会话时区。
    private static final String UTC_DAY = "FLOOR(EXTRACT(EPOCH FROM r.submitted_at)/86400)";
    private static final int WAITING_LIMIT = 20;
    private static final String ROUND_FROM = """
            FROM approval_submission_round r JOIN approval_application a
              ON a.tenant_id=r.tenant_id AND a.id=r.application_id
            WHERE r.tenant_id=? AND r.submitted_at>=? AND r.submitted_at<?
            """;
    private static final String METRICS = """
            COUNT(*) AS submitted, COUNT(DISTINCT r.application_id) AS applications,
            COALESCE(SUM(CASE WHEN r.status='IN_APPROVAL' THEN 1 ELSE 0 END),0) AS in_approval,
            COALESCE(SUM(CASE WHEN r.status='APPROVED' THEN 1 ELSE 0 END),0) AS approved,
            COALESCE(SUM(CASE WHEN r.status='RETURNED' THEN 1 ELSE 0 END),0) AS returned,
            COALESCE(SUM(CASE WHEN r.status='REJECTED' THEN 1 ELSE 0 END),0) AS rejected,
            COALESCE(SUM(CASE WHEN r.status='WITHDRAWN' THEN 1 ELSE 0 END),0) AS withdrawn,
            COUNT(CASE WHEN r.status='APPROVED' AND r.completed_at>=r.submitted_at THEN 1 END) AS duration_samples,
            AVG(CASE WHEN r.status='APPROVED' AND r.completed_at>=r.submitted_at
                THEN EXTRACT(EPOCH FROM r.completed_at)-EXTRACT(EPOCH FROM r.submitted_at) END) AS average_seconds
            """;
    private final JdbcTemplate jdbc;
    private final JdbcSubmissionHistoryGapQuery historyGaps;

    /** 复用审批与 Flowable 数据源，只读查询不写入统计状态。 */
    public JdbcApprovalOperationsReadAdapter(JdbcTemplate jdbc, JdbcSubmissionHistoryGapQuery historyGaps) {
        this.jdbc = jdbc; this.historyGaps = historyGaps;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Report read(String tenantId, Query query, Instant generatedAt) {
        var roundParameters = new ArrayList<Object>(List.of(tenantId, query.fromInclusive().atOffset(ZoneOffset.UTC),
                query.toExclusive().atOffset(ZoneOffset.UTC)));
        StringBuilder rounds = new StringBuilder(ROUND_FROM);
        filters(rounds, roundParameters, query, "r.definition_version");
        Metrics metrics = jdbc.queryForObject("SELECT " + METRICS + rounds, (row, index) -> metrics(row), roundParameters.toArray());

        var dailyCounts = new HashMap<LocalDate, Long>();
        jdbc.query("SELECT " + UTC_DAY + " AS submitted_day,COUNT(*) AS submitted " + rounds + " GROUP BY " + UTC_DAY,
                (org.springframework.jdbc.core.RowCallbackHandler) row -> dailyCounts.put(LocalDate.ofEpochDay(row.getLong("submitted_day")), row.getLong("submitted")),
                roundParameters.toArray());
        List<Daily> daily = query.from().datesUntil(query.to().plusDays(1))
                .map(day -> new Daily(day, dailyCounts.getOrDefault(day, 0L))).toList();
        List<ProcessSummary> processes = jdbc.query("SELECT a.process_key,r.definition_version," + METRICS + rounds
                        + " GROUP BY a.process_key,r.definition_version ORDER BY submitted DESC,a.process_key,r.definition_version LIMIT " + (PROCESS_LIMIT + 1),
                (row, index) -> new ProcessSummary(row.getString("process_key"), row.getLong("definition_version"), metrics(row)), roundParameters.toArray());

        var taskParameters = new ArrayList<Object>(List.of(tenantId));
        StringBuilder tasks = new StringBuilder(query.organization().isEmpty()
                ? FlowableActiveTaskSql.fromCurrentApplications() : FlowableActiveTaskSql.fromCurrentApplicationsWithRound());
        filters(tasks, taskParameters, query, "a.definition_version");
        var nodeParameters = new ArrayList<Object>(List.of(java.sql.Timestamp.from(generatedAt), java.sql.Timestamp.from(generatedAt)));
        nodeParameters.addAll(taskParameters);
        // 窗口在 LIMIT 前汇总全部分组，待办总数不会随展示截断，也不必重新联查引擎表。
        List<WaitingNodeRow> nodeRows = jdbc.query("""
                SELECT a.process_key,a.definition_version,t.TASK_DEF_KEY_,t.NAME_,COUNT(*) AS tasks,
                       MIN(t.CREATE_TIME_) AS oldest,SUM(COUNT(*)) OVER () AS total_tasks,
                       SUM(CASE WHEN t.DUE_DATE_<=? THEN 1 ELSE 0 END) AS overdue_tasks,
                       SUM(SUM(CASE WHEN t.DUE_DATE_<=? THEN 1 ELSE 0 END)) OVER () AS overdue_total
                """ + tasks + "\n" + """
                GROUP BY a.process_key,a.definition_version,t.TASK_DEF_KEY_,t.NAME_
                ORDER BY oldest ASC,a.process_key,a.definition_version,t.TASK_DEF_KEY_,t.NAME_
                """ + " LIMIT " + (WAITING_LIMIT + 1), (row, index) -> {
            Instant oldest = row.getTimestamp("oldest").toInstant();
            var node = new WaitingNode(row.getString("process_key"), row.getLong("definition_version"), row.getString("TASK_DEF_KEY_"),
                    row.getString("NAME_"), row.getLong("tasks"), oldest, elapsed(oldest, generatedAt), row.getLong("overdue_tasks"));
            return new WaitingNodeRow(node, row.getLong("total_tasks"), row.getLong("overdue_total"));
        }, nodeParameters.toArray());
        long pending = nodeRows.isEmpty() ? 0 : nodeRows.get(0).totalTasks();
        long overdue = nodeRows.isEmpty() ? 0 : nodeRows.get(0).overdueTasks();
        List<WaitingNode> nodes = nodeRows.stream().map(WaitingNodeRow::node).toList();
        List<WaitingTask> oldestTasks = jdbc.query("""
                SELECT t.ID_,t.NAME_,t.ASSIGNEE_,t.CREATE_TIME_,t.DUE_DATE_,a.id,a.business_no,a.title,a.process_key,a.definition_version,a.round_no
                """ + tasks + " ORDER BY t.CREATE_TIME_,t.ID_ LIMIT " + (WAITING_LIMIT + 1), (row, index) -> {
            Instant created = row.getTimestamp("CREATE_TIME_").toInstant();
            return new WaitingTask(row.getString("ID_"), row.getString("NAME_"), row.getString("id"), row.getString("business_no"),
                    row.getString("title"), row.getString("process_key"), row.getLong("definition_version"), row.getInt("round_no"),
                    row.getString("ASSIGNEE_"), created, elapsed(created, generatedAt),
                    row.getTimestamp("DUE_DATE_") == null ? null : row.getTimestamp("DUE_DATE_").toInstant());
        }, taskParameters.toArray());
        return new Report(generatedAt, query.from(), query.to(), "UTC", query.processKey(), query.definitionVersion(), query.organization(), metrics, daily,
                head(processes, PROCESS_LIMIT), processes.size() > PROCESS_LIMIT, pending, overdue, head(nodes, WAITING_LIMIT),
                nodes.size() > WAITING_LIMIT, head(oldestTasks, WAITING_LIMIT), oldestTasks.size() > WAITING_LIMIT,
                historyGaps.count(tenantId, query.processKey(), query.definitionVersion()),
                sla(rounds.toString(), roundParameters), notifications(rounds.toString(), roundParameters), agent(rounds.toString(), roundParameters));
    }

    // 三项结果共用原轮次筛选，避免重提后用当前组织、版本或状态覆盖旧队列。
    private static String selectedRounds(String rounds) {
        return "WITH selected_rounds AS (SELECT r.tenant_id,r.application_id,r.round_no,r.process_instance_id " + rounds + ") ";
    }

    private SlaMetrics sla(String rounds, List<Object> parameters) {
        return jdbc.queryForObject(selectedRounds(rounds) + """
                , bound_rounds AS (SELECT r.* FROM selected_rounds r WHERE 1=1
                    AND EXISTS (SELECT 1 FROM ACT_HI_VARINST v WHERE v.PROC_INST_ID_=r.process_instance_id
                        AND v.EXECUTION_ID_=r.process_instance_id AND v.TASK_ID_ IS NULL AND v.NAME_='tenantId' AND v.TEXT_=r.tenant_id)
                    AND EXISTS (SELECT 1 FROM ACT_HI_VARINST v WHERE v.PROC_INST_ID_=r.process_instance_id
                        AND v.EXECUTION_ID_=r.process_instance_id AND v.TASK_ID_ IS NULL AND v.NAME_='applicationId' AND v.TEXT_=r.application_id)
                    AND EXISTS (SELECT 1 FROM ACT_HI_VARINST v WHERE v.PROC_INST_ID_=r.process_instance_id
                        AND v.EXECUTION_ID_=r.process_instance_id AND v.TASK_ID_ IS NULL AND v.NAME_='roundNo' AND v.LONG_=r.round_no)
                ), classified AS (
                    SELECT CASE
                        WHEN h.END_TIME_ IS NULL THEN 'UNFINISHED'
                        WHEN NOT EXISTS (SELECT 1 FROM audit_event e WHERE e.tenant_id=r.tenant_id
                            AND e.application_id=r.application_id AND e.aggregate_type='Task' AND e.aggregate_id=h.ID_
                            AND e.action IN ('APPROVE','RETURN','REJECT'))
                            THEN CASE WHEN h.DELETE_REASON_ IS NOT NULL AND h.DELETE_REASON_<>'completed'
                                THEN 'CANCELLED' ELSE 'UNRECORDED_DECISION' END
                        WHEN h.START_TIME_ IS NULL OR h.END_TIME_<h.START_TIME_ OR h.DUE_DATE_<h.START_TIME_ THEN 'INVALID_TIMING'
                        WHEN h.DUE_DATE_ IS NULL THEN 'NO_DEADLINE'
                        WHEN h.END_TIME_>h.DUE_DATE_ THEN 'VIOLATED' ELSE 'ON_TIME' END AS outcome
                    FROM ACT_HI_TASKINST h JOIN bound_rounds r ON h.PROC_INST_ID_=r.process_instance_id
                    WHERE (h.TENANT_ID_ IS NULL OR h.TENANT_ID_='' OR h.TENANT_ID_=r.tenant_id)

                )
                SELECT COALESCE(SUM(CASE WHEN outcome='ON_TIME' THEN 1 ELSE 0 END),0) AS on_time,
                    COALESCE(SUM(CASE WHEN outcome='VIOLATED' THEN 1 ELSE 0 END),0) AS violated,
                    COALESCE(SUM(CASE WHEN outcome='NO_DEADLINE' THEN 1 ELSE 0 END),0) AS no_deadline,
                    COALESCE(SUM(CASE WHEN outcome='INVALID_TIMING' THEN 1 ELSE 0 END),0) AS invalid_timing,
                    COALESCE(SUM(CASE WHEN outcome='CANCELLED' THEN 1 ELSE 0 END),0) AS cancelled,
                    COALESCE(SUM(CASE WHEN outcome='UNFINISHED' THEN 1 ELSE 0 END),0) AS unfinished,
                    COALESCE(SUM(CASE WHEN outcome='UNRECORDED_DECISION' THEN 1 ELSE 0 END),0) AS unrecorded,
                    (SELECT COUNT(*) FROM selected_rounds)-(SELECT COUNT(*) FROM bound_rounds) AS unverified_rounds
                FROM classified
                """, (row, index) -> SlaMetrics.of(row.getLong("on_time"), row.getLong("violated"), row.getLong("no_deadline"),
                row.getLong("invalid_timing"), row.getLong("cancelled"), row.getLong("unfinished"), row.getLong("unrecorded"), row.getLong("unverified_rounds")), parameters.toArray());
    }

    private NotificationMetrics notifications(String rounds, List<Object> parameters) {
        return jdbc.queryForObject(selectedRounds(rounds) + """
                SELECT COUNT(*) AS deliveries,
                    COALESCE(SUM(CASE WHEN d.status='ACCEPTED' THEN 1 ELSE 0 END),0) AS accepted,
                    COALESCE(SUM(CASE WHEN d.status='FAILED' THEN 1 ELSE 0 END),0) AS failed,
                    COALESCE(SUM(CASE WHEN d.status='RETRY_WAIT' THEN 1 ELSE 0 END),0) AS retry_waiting,
                    COALESCE(SUM(CASE WHEN d.status='UNKNOWN' THEN 1 ELSE 0 END),0) AS unknown_count,
                    COALESCE(SUM(CASE WHEN d.status='SUPPRESSED' THEN 1 ELSE 0 END),0) AS suppressed,
                    COALESCE(SUM(CASE WHEN d.status='PENDING' THEN 1 ELSE 0 END),0) AS pending,
                    COALESCE(SUM(CASE WHEN d.status='IN_FLIGHT' THEN 1 ELSE 0 END),0) AS in_flight,
                    COALESCE(SUM(CASE WHEN EXISTS(SELECT 1 FROM notification_delivery_event e
                        WHERE e.delivery_id=d.id AND e.status IN ('FAILED','RETRY_WAIT')) THEN 1 ELSE 0 END),0) AS previously_failed
                FROM notification_dispatch d JOIN notification_inbox n
                    ON n.id=d.inbox_id AND n.tenant_id=d.tenant_id AND n.recipient_id=d.recipient_id
                JOIN selected_rounds r ON r.tenant_id=n.tenant_id AND r.application_id=n.application_id AND r.round_no=n.round_no
                """, (row, index) -> new NotificationMetrics(row.getLong("deliveries"), row.getLong("accepted"), row.getLong("failed"),
                row.getLong("retry_waiting"), row.getLong("unknown_count"), row.getLong("suppressed"), row.getLong("pending"),
                row.getLong("in_flight"), row.getLong("previously_failed")), parameters.toArray());
    }

    private AgentMetrics agent(String rounds, List<Object> parameters) {
        return jdbc.queryForObject(selectedRounds(rounds) + """
                SELECT COALESCE(SUM(CASE WHEN g.status='QUEUED' THEN 1 ELSE 0 END),0) AS queued,
                    COALESCE(SUM(CASE WHEN g.status='RUNNING' THEN 1 ELSE 0 END),0) AS running,
                    COALESCE(SUM(CASE WHEN g.status='COMPLETED' THEN 1 ELSE 0 END),0) AS awaiting_review,
                    COALESCE(SUM(CASE WHEN g.status='FAILED' THEN 1 ELSE 0 END),0) AS failed,
                    COALESCE(SUM(CASE WHEN g.status='ADOPTED' THEN 1 ELSE 0 END),0) AS adopted,
                    COALESCE(SUM(CASE WHEN g.status='DISMISSED' THEN 1 ELSE 0 END),0) AS dismissed
                FROM agent_assist_run g JOIN selected_rounds r
                    ON g.tenant_id=r.tenant_id AND g.application_id=r.application_id AND g.round_no=r.round_no
                """, (row, index) -> AgentMetrics.of(row.getLong("queued"), row.getLong("running"), row.getLong("awaiting_review"),
                row.getLong("failed"), row.getLong("adopted"), row.getLong("dismissed")), parameters.toArray());
    }

    private static Metrics metrics(ResultSet row) throws SQLException {
        var seconds = row.getBigDecimal("average_seconds");
        return Metrics.of(row.getLong("submitted"), row.getLong("applications"), row.getLong("in_approval"),
                row.getLong("approved"), row.getLong("returned"), row.getLong("rejected"), row.getLong("withdrawn"),
                row.getLong("duration_samples"), seconds == null ? null : seconds.setScale(0, RoundingMode.HALF_UP).longValueExact());
    }

    private static void filters(StringBuilder sql, List<Object> parameters, Query query, String versionColumn) {
        if (!query.processKey().isEmpty()) { sql.append(" AND a.process_key=?"); parameters.add(query.processKey()); }
        if (query.definitionVersion() != null) { sql.append(" AND ").append(versionColumn).append("=?"); parameters.add(query.definitionVersion()); }
        io.agentflow.approval.RoundOrganizationSearchSql.append(sql, parameters, query.organization());
    }

    private static long elapsed(Instant start, Instant end) { return Math.max(0, Duration.between(start, end).getSeconds()); }
    private static <T> List<T> head(List<T> rows, int limit) { return List.copyOf(rows.subList(0, Math.min(rows.size(), limit))); }

    /**
     * 节点聚合行附带截断前的任务总数，仅用于基础设施层结果映射。
     * @author owlzhangfq@gmail.com
     */
    private record WaitingNodeRow(WaitingNode node, long totalTasks, long overdueTasks) { }
}
