package io.agentflow.approval.operations;

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

    /** 复用审批与 Flowable 数据源，只读查询不写入统计状态。 */
    public JdbcApprovalOperationsReadAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

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
        StringBuilder tasks = new StringBuilder(FlowableActiveTaskSql.fromCurrentApplications());
        filters(tasks, taskParameters, query, "a.definition_version");
        long pending = jdbc.queryForObject("SELECT COUNT(*) " + tasks, Long.class, taskParameters.toArray());
        List<WaitingNode> nodes = jdbc.query("""
                SELECT a.process_key,a.definition_version,t.TASK_DEF_KEY_,t.NAME_,COUNT(*) AS tasks,MIN(t.CREATE_TIME_) AS oldest
                """ + tasks + "\n" + """
                GROUP BY a.process_key,a.definition_version,t.TASK_DEF_KEY_,t.NAME_
                ORDER BY oldest ASC,a.process_key,a.definition_version,t.TASK_DEF_KEY_,t.NAME_
                """ + " LIMIT " + (WAITING_LIMIT + 1), (row, index) -> {
            Instant oldest = row.getTimestamp("oldest").toInstant();
            return new WaitingNode(row.getString("process_key"), row.getLong("definition_version"), row.getString("TASK_DEF_KEY_"),
                    row.getString("NAME_"), row.getLong("tasks"), oldest, elapsed(oldest, generatedAt));
        }, taskParameters.toArray());
        List<WaitingTask> oldestTasks = jdbc.query("""
                SELECT t.ID_,t.NAME_,t.ASSIGNEE_,t.CREATE_TIME_,a.id,a.business_no,a.title,a.process_key,a.definition_version,a.round_no
                """ + tasks + " ORDER BY t.CREATE_TIME_,t.ID_ LIMIT " + (WAITING_LIMIT + 1), (row, index) -> {
            Instant created = row.getTimestamp("CREATE_TIME_").toInstant();
            return new WaitingTask(row.getString("ID_"), row.getString("NAME_"), row.getString("id"), row.getString("business_no"),
                    row.getString("title"), row.getString("process_key"), row.getLong("definition_version"), row.getInt("round_no"),
                    row.getString("ASSIGNEE_"), created, elapsed(created, generatedAt));
        }, taskParameters.toArray());
        return new Report(generatedAt, query.from(), query.to(), "UTC", query.processKey(), query.definitionVersion(), metrics, daily,
                head(processes, PROCESS_LIMIT), processes.size() > PROCESS_LIMIT, pending, head(nodes, WAITING_LIMIT),
                nodes.size() > WAITING_LIMIT, head(oldestTasks, WAITING_LIMIT), oldestTasks.size() > WAITING_LIMIT,
                unrecorded(tenantId, query));
    }

    private long unrecorded(String tenantId, Query query) {
        var parameters = new ArrayList<Object>(List.of(tenantId));
        var sql = new StringBuilder("""
                SELECT COALESCE(SUM(GREATEST(0,a.round_no-(SELECT COUNT(*) FROM approval_submission_round r
                    WHERE r.tenant_id=a.tenant_id AND r.application_id=a.id))),0)
                FROM approval_application a WHERE a.tenant_id=?
                """);
        // 旧轮次没有可信提交日期，单独披露缺失量，不能归入任意日期或按当前状态补造历史。
        filters(sql, parameters, query, "a.definition_version");
        return jdbc.queryForObject(sql.toString(), Long.class, parameters.toArray());
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
    }

    private static long elapsed(Instant start, Instant end) { return Math.max(0, Duration.between(start, end).getSeconds()); }
    private static <T> List<T> head(List<T> rows, int limit) { return List.copyOf(rows.subList(0, Math.min(rows.size(), limit))); }
}
