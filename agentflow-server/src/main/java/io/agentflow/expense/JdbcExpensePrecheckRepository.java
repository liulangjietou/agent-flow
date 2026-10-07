package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.observability.DiagnosticContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 独立预检任务及其原始输入、转换证据；本地进程不持有唯一执行状态。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcExpensePrecheckRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    /** 与业务版本和幂等登记共用数据源。 */
    public JdbcExpensePrecheckRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }

    /** 一个单据同时只允许一个活动预检，重复请求不会产生并行外发。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(ExpensePrecheckJob job) {
        var input = job.input();
        if (job.status() != ExpensePrecheckJob.Status.QUEUED) throw conflict();
        try {
            jdbc.update("""
                    INSERT INTO expense_precheck_job(tenant_id,id,report_id,application_id,employee_id,application_version,
                    financial_version,attempt_no,input_json,state_json,version,status,active_report_id,created_at,trace_id)
                    VALUES(?,?,?,?,?,?,?,?,?,?,1,'QUEUED',?,?,?)
                    """, input.tenantId(), input.id().toString(), input.reportId().toString(), input.applicationId().toString(), input.employeeId(),
                    input.applicationVersion(), input.financialVersion(), input.attempt(), json.write(input), json.write(job), input.reportId().toString(), Timestamp.from(job.createdAt()), DiagnosticContext.capture().traceId());
        } catch (DuplicateKeyException duplicate) { throw new DomainException("EXPENSE_PRECHECK_ACTIVE", "Expense report already has an active precheck"); }
        append(job);
    }

    /** 原输入不可替换，任务转换和审计追加必须一起成功。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(ExpensePrecheckJob job) {
        var input = job.input();
        int updated = jdbc.update("""
                UPDATE expense_precheck_job SET version=?,status=?,state_json=?,active_report_id=?,lease_until=?,completed_at=?
                WHERE tenant_id=? AND id=? AND input_json=? AND version=?
                """, job.version(), job.status().name(), json.write(job), job.active() ? input.reportId().toString() : null,
                timestamp(job.leaseUntil()), timestamp(job.completedAt()), input.tenantId(), input.id().toString(), json.write(input), job.version() - 1);
        if (updated != 1) throw conflict();
        append(job);
    }

    /** 租户列和输入/状态 JSON 的身份必须一致。 */
    public Optional<ExpensePrecheckJob> find(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM expense_precheck_job WHERE tenant_id=? AND id=?", row(), tenant, id.toString()).stream().findFirst();
    }
    /** 申请锁内分配递增序号，不用 UUID 或时间戳推断新旧检查结论。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public long nextAttempt(String tenant, UUID reportId) {
        return latestAttempt(tenant, reportId) + 1;
    }
    /** 新尝试一旦登记，旧 READY 结果立即只供历史查看。 */
    public long latestAttempt(String tenant, UUID reportId) {
        return jdbc.queryForObject("SELECT COALESCE(MAX(attempt_no),0) FROM expense_precheck_job WHERE tenant_id=? AND report_id=?", Long.class, tenant, reportId.toString());
    }

    /** 最新任务按业务检查序号定位；历史 UUID 游标不能用于判断新旧。 */
    public Optional<UUID> latestId(String tenant, UUID reportId) {
        return jdbc.queryForList("SELECT id FROM expense_precheck_job WHERE tenant_id=? AND report_id=? ORDER BY attempt_no DESC LIMIT 1",
                String.class, tenant, reportId.toString()).stream().findFirst().map(UUID::fromString);
    }

    /** 排队前在单据锁下检查，唯一约束继续兜底并发。 */
    public boolean active(String tenant, UUID reportId) {
        return !jdbc.queryForList("SELECT id FROM expense_precheck_job WHERE tenant_id=? AND active_report_id=?", String.class, tenant, reportId.toString()).isEmpty();
    }
    /** 只读取十项；过期运行用于记录超时，不重复发送旧任务。 */
    public List<Candidate> due(Instant now) {
        return jdbc.query("""
                SELECT tenant_id,id,trace_id FROM expense_precheck_job WHERE status='QUEUED' OR (status='RUNNING' AND lease_until<=?)
                ORDER BY created_at,id LIMIT 10
                """, (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("id")), row.getString("trace_id")), Timestamp.from(now));
    }
    /** 历史只按单据归属查询，使用有界稳定游标。 */
    public List<ExpensePrecheckJob> list(String tenant, UUID reportId, UUID before, int limit) {
        String sql = "SELECT * FROM expense_precheck_job WHERE tenant_id=? AND report_id=? "
                + (before == null ? "" : "AND id<? ") + "ORDER BY id DESC LIMIT ?";
        return before == null ? jdbc.query(sql, row(), tenant, reportId.toString(), limit)
                : jdbc.query(sql, row(), tenant, reportId.toString(), before.toString(), limit);
    }

    private RowMapper<ExpensePrecheckJob> row() {
        return (row, index) -> {
            var job = json.read(row.getString("state_json"), ExpensePrecheckJob.class); var input = job.input();
            if (!input.equals(json.read(row.getString("input_json"), ExpensePrecheckJob.Input.class))
                    || !input.tenantId().equals(row.getString("tenant_id")) || !input.id().toString().equals(row.getString("id"))
                    || !input.reportId().toString().equals(row.getString("report_id")) || !input.applicationId().toString().equals(row.getString("application_id"))
                    || !input.employeeId().equals(row.getString("employee_id")) || input.applicationVersion() != row.getLong("application_version")
                    || input.attempt() != row.getLong("attempt_no") || input.financialVersion() != row.getLong("financial_version") || job.version() != row.getLong("version")
                    || !job.status().name().equals(row.getString("status")) || !job.createdAt().equals(row.getTimestamp("created_at").toInstant())
                    || !Objects.equals(job.active() ? input.reportId().toString() : null, row.getString("active_report_id"))
                    || !Objects.equals(job.leaseUntil(), instant(row.getTimestamp("lease_until")))
                    || !Objects.equals(job.completedAt(), instant(row.getTimestamp("completed_at")))) {
                throw new IllegalStateException("Persisted expense precheck identity is inconsistent");
            }
            return job;
        };
    }
    private void append(ExpensePrecheckJob job) {
        jdbc.update("INSERT INTO expense_precheck_revision(tenant_id,job_id,version,state_json) VALUES(?,?,?,?)",
                job.input().tenantId(), job.input().id().toString(), job.version(), json.write(job));
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Expense precheck input or version changed"); }

    /**
     * 扫描项不复制敏感快照，领取后在锁内重新读取。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID id, String traceId) { }
}
