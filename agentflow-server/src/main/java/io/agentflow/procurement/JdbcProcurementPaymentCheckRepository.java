package io.agentflow.procurement;

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
 * 独立预检任务及其原始输入、三单匹配依据；本地进程不持有唯一执行状态。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcProcurementPaymentCheckRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    /** 与业务版本和幂等登记共用数据源。 */
    public JdbcProcurementPaymentCheckRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }

    /** 一个单据同时只允许一个活动预检，重复请求不会产生并行外发。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(ProcurementPaymentCheck job) {
        var input = job.input();
        if (job.status() != ProcurementPaymentCheck.Status.QUEUED) throw conflict();
        var source = jdbc.query("SELECT state_json FROM procurement_payment WHERE tenant_id=? AND id=? AND application_id=? AND employee_id=? AND version=?",
                (row, index) -> ProcurementPaymentRequest.restore(json.read(row.getString("state_json"), ProcurementPaymentRequest.State.class)),
                input.tenantId(), input.requestId().toString(), input.applicationId().toString(), input.employeeId(), input.requestVersion());
        if (source.size() != 1 || !source.get(0).content().equals(input.content()) || source.get(0).rounds().size() + 1 != input.roundNo()) throw conflict();
        try {
            jdbc.update("""
                    INSERT INTO procurement_payment_check_job(trace_id,tenant_id,id,request_id,application_id,employee_id,application_version,
                    request_version,attempt_no,input_json,state_json,version,status,active_request_id,created_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,1,'QUEUED',?,?)
                    """, DiagnosticContext.capture().traceId(), input.tenantId(), input.id().toString(), input.requestId().toString(), input.applicationId().toString(), input.employeeId(),
                    input.applicationVersion(), input.requestVersion(), input.attempt(), json.write(input), json.write(job), input.requestId().toString(), Timestamp.from(job.createdAt()));
        } catch (DuplicateKeyException duplicate) { throw new DomainException("PROCUREMENT_CHECK_ACTIVE", "Procurement payment already has an active precheck"); }
        append(job);
    }

    /** 原输入不可替换，任务转换和审计追加必须一起成功。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(ProcurementPaymentCheck job) {
        var input = job.input();
        var before = find(input.tenantId(), input.id()).orElseThrow(JdbcProcurementPaymentCheckRepository::conflict);
        if (!before.input().equals(input) || !before.createdAt().equals(job.createdAt())) throw conflict();
        var expected = before.status() == ProcurementPaymentCheck.Status.QUEUED
                ? before.start(job.startedAt(), job.leaseUntil()) : before.finish(job.result(), job.completedAt());
        if (!expected.equals(job)) throw conflict();
        int updated = jdbc.update("""
                UPDATE procurement_payment_check_job SET version=?,status=?,state_json=?,active_request_id=?,lease_until=?,completed_at=?
                WHERE tenant_id=? AND id=? AND input_json=? AND version=?
                """, job.version(), job.status().name(), json.write(job), job.active() ? input.requestId().toString() : null,
                timestamp(job.leaseUntil()), timestamp(job.completedAt()), input.tenantId(), input.id().toString(), json.write(input), job.version() - 1);
        if (updated != 1) throw conflict();
        append(job);
    }

    /** 租户列和输入/状态 JSON 的身份必须一致。 */
    public Optional<ProcurementPaymentCheck> find(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM procurement_payment_check_job WHERE tenant_id=? AND id=?", row(), tenant, id.toString()).stream().findFirst();
    }
    /** 申请锁内分配递增序号，不用 UUID 或时间戳推断新旧检查结论。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public long nextAttempt(String tenant, UUID requestId) {
        return latestAttempt(tenant, requestId) + 1;
    }
    /** 新尝试一旦登记，旧 READY 结果立即只供历史查看。 */
    public long latestAttempt(String tenant, UUID requestId) {
        return jdbc.queryForObject("SELECT COALESCE(MAX(attempt_no),0) FROM procurement_payment_check_job WHERE tenant_id=? AND request_id=?", Long.class, tenant, requestId.toString());
    }

    /** 最新任务按业务检查序号定位；历史 UUID 游标不能用于判断新旧。 */
    public Optional<UUID> latestId(String tenant, UUID requestId) {
        return jdbc.queryForList("SELECT id FROM procurement_payment_check_job WHERE tenant_id=? AND request_id=? ORDER BY attempt_no DESC LIMIT 1",
                String.class, tenant, requestId.toString()).stream().findFirst().map(UUID::fromString);
    }

    /** 排队前在单据锁下检查，唯一约束继续兜底并发。 */
    public boolean active(String tenant, UUID requestId) {
        return !jdbc.queryForList("SELECT id FROM procurement_payment_check_job WHERE tenant_id=? AND active_request_id=?", String.class, tenant, requestId.toString()).isEmpty();
    }
    /** 只读取十项；过期运行用于记录超时，不重复发送旧任务。 */
    public List<Candidate> due(Instant now) {
        return jdbc.query("""
                SELECT q.tenant_id,q.id,q.trace_id,a.business_no,NULL AS process_instance_id FROM procurement_payment_check_job q
                LEFT JOIN approval_application a ON a.tenant_id=q.tenant_id AND a.id=q.application_id
                WHERE q.status='QUEUED' OR (q.status='RUNNING' AND q.lease_until<=?)
                ORDER BY q.created_at,q.id LIMIT 10
                """, (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("id")),
                row.getString("trace_id"), row.getString("business_no"), row.getString("process_instance_id")), Timestamp.from(now));
    }
    /** 历史只按单据归属查询，使用有界稳定游标。 */
    public List<ProcurementPaymentCheck> list(String tenant, UUID requestId, UUID before, int limit) {
        String sql = "SELECT * FROM procurement_payment_check_job WHERE tenant_id=? AND request_id=? "
                + (before == null ? "" : "AND id<? ") + "ORDER BY id DESC LIMIT ?";
        return before == null ? jdbc.query(sql, row(), tenant, requestId.toString(), limit)
                : jdbc.query(sql, row(), tenant, requestId.toString(), before.toString(), limit);
    }

    private RowMapper<ProcurementPaymentCheck> row() {
        return (row, index) -> {
            var job = json.read(row.getString("state_json"), ProcurementPaymentCheck.class); var input = job.input();
            if (!input.equals(json.read(row.getString("input_json"), ProcurementPaymentCheck.Input.class))
                    || !input.tenantId().equals(row.getString("tenant_id")) || !input.id().toString().equals(row.getString("id"))
                    || !input.requestId().toString().equals(row.getString("request_id")) || !input.applicationId().toString().equals(row.getString("application_id"))
                    || !input.employeeId().equals(row.getString("employee_id")) || input.applicationVersion() != row.getLong("application_version")
                    || input.attempt() != row.getLong("attempt_no") || input.requestVersion() != row.getLong("request_version") || job.version() != row.getLong("version")
                    || !job.status().name().equals(row.getString("status")) || !job.createdAt().equals(row.getTimestamp("created_at").toInstant())
                    || !Objects.equals(job.active() ? input.requestId().toString() : null, row.getString("active_request_id"))
                    || !Objects.equals(job.leaseUntil(), instant(row.getTimestamp("lease_until")))
                    || !Objects.equals(job.completedAt(), instant(row.getTimestamp("completed_at")))) {
                throw new IllegalStateException("Persisted procurement payment check identity is inconsistent");
            }
            return job;
        };
    }
    private void append(ProcurementPaymentCheck job) {
        jdbc.update("INSERT INTO procurement_payment_check_revision(tenant_id,job_id,version,state_json) VALUES(?,?,?,?)",
                job.input().tenantId(), job.input().id().toString(), job.version(), json.write(job));
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Procurement payment check input or version changed"); }

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
