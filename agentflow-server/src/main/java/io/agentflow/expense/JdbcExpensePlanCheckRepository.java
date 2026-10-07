package io.agentflow.expense;

import io.agentflow.observability.DiagnosticContext;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
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
public class JdbcExpensePlanCheckRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    /** 与业务版本和幂等登记共用数据源。 */
    public JdbcExpensePlanCheckRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }

    /** 一个单据同时只允许一个活动预检，重复请求不会产生并行外发。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(ExpensePlanCheck job) {
        var input = job.input();
        if (job.status() != ExpensePlanCheck.Status.QUEUED) throw conflict();
        try {
            jdbc.update("""
                    INSERT INTO expense_plan_check_job(trace_id,tenant_id,id,plan_id,application_id,employee_id,application_version,
                    plan_version,attempt_no,input_json,state_json,version,status,active_plan_id,created_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,1,'QUEUED',?,?)
                    """, DiagnosticContext.capture().traceId(), input.tenantId(), input.id().toString(), input.planId().toString(), input.applicationId().toString(), input.employeeId(),
                    input.applicationVersion(), input.planVersion(), input.attempt(), json.write(input), json.write(job), input.planId().toString(), Timestamp.from(job.createdAt()));
        } catch (DuplicateKeyException duplicate) { throw new DomainException("EXPENSE_PLAN_CHECK_ACTIVE", "Expense plan already has an active precheck"); }
        append(job);
    }

    /** 原输入不可替换，任务转换和审计追加必须一起成功。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(ExpensePlanCheck job) {
        var input = job.input();
        int updated = jdbc.update("""
                UPDATE expense_plan_check_job SET version=?,status=?,state_json=?,active_plan_id=?,lease_until=?,completed_at=?
                WHERE tenant_id=? AND id=? AND input_json=? AND version=?
                """, job.version(), job.status().name(), json.write(job), job.active() ? input.planId().toString() : null,
                timestamp(job.leaseUntil()), timestamp(job.completedAt()), input.tenantId(), input.id().toString(), json.write(input), job.version() - 1);
        if (updated != 1) throw conflict();
        append(job);
    }

    /** 租户列和输入/状态 JSON 的身份必须一致。 */
    public Optional<ExpensePlanCheck> find(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM expense_plan_check_job WHERE tenant_id=? AND id=?", row(), tenant, id.toString()).stream().findFirst();
    }
    /** 申请锁内分配递增序号，不用 UUID 或时间戳推断新旧检查结论。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public long nextAttempt(String tenant, UUID planId) {
        return latestAttempt(tenant, planId) + 1;
    }
    /** 新尝试一旦登记，旧 READY 结果立即只供历史查看。 */
    public long latestAttempt(String tenant, UUID planId) {
        return jdbc.queryForObject("SELECT COALESCE(MAX(attempt_no),0) FROM expense_plan_check_job WHERE tenant_id=? AND plan_id=?", Long.class, tenant, planId.toString());
    }

    /** 最新任务按业务检查序号定位；历史 UUID 游标不能用于判断新旧。 */
    public Optional<UUID> latestId(String tenant, UUID planId) {
        return jdbc.queryForList("SELECT id FROM expense_plan_check_job WHERE tenant_id=? AND plan_id=? ORDER BY attempt_no DESC LIMIT 1",
                String.class, tenant, planId.toString()).stream().findFirst().map(UUID::fromString);
    }

    /** 排队前在单据锁下检查，唯一约束继续兜底并发。 */
    public boolean active(String tenant, UUID planId) {
        return !jdbc.queryForList("SELECT id FROM expense_plan_check_job WHERE tenant_id=? AND active_plan_id=?", String.class, tenant, planId.toString()).isEmpty();
    }
    /** 只读取十项；过期运行用于记录超时，不重复发送旧任务。 */
    public List<Candidate> due(Instant now) {
        return jdbc.query("""
                SELECT q.tenant_id,q.id,q.trace_id,a.business_no,NULL AS process_instance_id FROM expense_plan_check_job q
                LEFT JOIN approval_application a ON a.tenant_id=q.tenant_id AND a.id=q.application_id
                WHERE q.status='QUEUED' OR (q.status='RUNNING' AND q.lease_until<=?)
                ORDER BY q.created_at,q.id LIMIT 10
                """, (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("id")),
                row.getString("trace_id"), row.getString("business_no"), row.getString("process_instance_id")), Timestamp.from(now));
    }
    /** 历史只按单据归属查询，使用有界稳定游标。 */
    public List<ExpensePlanCheck> list(String tenant, UUID planId, UUID before, int limit) {
        String sql = "SELECT * FROM expense_plan_check_job WHERE tenant_id=? AND plan_id=? "
                + (before == null ? "" : "AND id<? ") + "ORDER BY id DESC LIMIT ?";
        return before == null ? jdbc.query(sql, row(), tenant, planId.toString(), limit)
                : jdbc.query(sql, row(), tenant, planId.toString(), before.toString(), limit);
    }

    private RowMapper<ExpensePlanCheck> row() {
        return (row, index) -> {
            var job = json.read(row.getString("state_json"), ExpensePlanCheck.class); var input = job.input();
            if (!input.equals(json.read(row.getString("input_json"), ExpensePlanCheck.Input.class))
                    || !input.tenantId().equals(row.getString("tenant_id")) || !input.id().toString().equals(row.getString("id"))
                    || !input.planId().toString().equals(row.getString("plan_id")) || !input.applicationId().toString().equals(row.getString("application_id"))
                    || !input.employeeId().equals(row.getString("employee_id")) || input.applicationVersion() != row.getLong("application_version")
                    || input.attempt() != row.getLong("attempt_no") || input.planVersion() != row.getLong("plan_version") || job.version() != row.getLong("version")
                    || !job.status().name().equals(row.getString("status")) || !job.createdAt().equals(row.getTimestamp("created_at").toInstant())
                    || !Objects.equals(job.active() ? input.planId().toString() : null, row.getString("active_plan_id"))
                    || !Objects.equals(job.leaseUntil(), instant(row.getTimestamp("lease_until")))
                    || !Objects.equals(job.completedAt(), instant(row.getTimestamp("completed_at")))) {
                throw new IllegalStateException("Persisted expense plan check identity is inconsistent");
            }
            return job;
        };
    }
    private void append(ExpensePlanCheck job) {
        jdbc.update("INSERT INTO expense_plan_check_revision(tenant_id,job_id,version,state_json) VALUES(?,?,?,?)",
                job.input().tenantId(), job.input().id().toString(), job.version(), json.write(job));
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Expense plan check input or version changed"); }

    /**
     * 扫描项不复制敏感快照，领取后在锁内重新读取。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID id, String traceId, String businessNo, String processInstanceId) {
        /** 缺少原业务事实时保持空值，不继承工作线程残留值。 */
        public Candidate(String tenantId, UUID id, String traceId) { this(tenantId, id, traceId, null, null); }
        /** 旧候选缺来源时由工作器建立稳定诊断作用域。 */
        public Candidate(String tenantId, UUID id) { this(tenantId, id, null); }
    }
}
